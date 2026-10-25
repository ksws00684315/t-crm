package com.tcrm.admin.commission.service;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.tcrm.admin.commission.api.CommissionItemApi;
import com.tcrm.admin.commission.mapper.CommissionItemRevisionMapper;
import com.tcrm.admin.commission.mapper.CommissionItemStateMapper;
import com.tcrm.admin.commission.mapper.CommissionRuleVersionMapper;
import com.tcrm.admin.commission.model.CommissionItemRevisionDO;
import com.tcrm.admin.commission.model.CommissionItemStateDO;
import com.tcrm.admin.commission.model.CommissionRuleVersionDO;
import com.tcrm.admin.kol.api.KolApi;
import com.tcrm.admin.kol.api.KolPayTimeRateDTO;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Commission source snapshot engine (task 62, design §4.8.1 / §8.3, ADR-032 §2-§5). Registers one
 * {@code crm_commission_item_state} row per order item line and appends a revision whenever the
 * source version changes. Wallet postings stay with the wallet service: positive releases go
 * through {@code postCommissionCredit} (FIFO prepay recovery), clawbacks through {@code
 * postCommissionAdjustment}.
 */
@Service
public class CommissionItemSnapshotService implements CommissionItemApi {
  private static final Logger LOG = LoggerFactory.getLogger(CommissionItemSnapshotService.class);
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2);

  private final CommissionItemStateMapper states;
  private final CommissionItemRevisionMapper revisions;
  private final CommissionRuleVersionMapper ruleVersions;
  private final CommissionWalletService wallet;
  private final KolApi kols;
  private final com.tcrm.admin.shared.CurrentTenantResolver currentTenant;
  private final Clock clock;

  @Autowired
  public CommissionItemSnapshotService(
      CommissionItemStateMapper states,
      CommissionItemRevisionMapper revisions,
      CommissionRuleVersionMapper ruleVersions,
      CommissionWalletService wallet,
      KolApi kols,
      com.tcrm.admin.shared.CurrentTenantResolver currentTenant) {
    this(states, revisions, ruleVersions, wallet, kols, currentTenant, Clock.systemUTC());
  }

  CommissionItemSnapshotService(
      CommissionItemStateMapper states,
      CommissionItemRevisionMapper revisions,
      CommissionRuleVersionMapper ruleVersions,
      CommissionWalletService wallet,
      KolApi kols,
      com.tcrm.admin.shared.CurrentTenantResolver currentTenant,
      Clock clock) {
    this.states = states;
    this.revisions = revisions;
    this.ruleVersions = ruleVersions;
    this.wallet = wallet;
    this.kols = kols;
    this.currentTenant = currentTenant;
    this.clock = clock;
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public void syncOrderItems(OrderSourceDTO order, List<ItemSourceDTO> items) {
    if (order == null || items == null || items.isEmpty()) {
      return;
    }
    Instant now = clock.instant();
    Map<Long, PayTimeRule> kolRates = new HashMap<>();
    RuleSnapshot rule = ruleEffectiveAt(order.payTime());
    for (ItemSourceDTO item : items) {
      long tenantId = currentTenant();
      CommissionItemStateDO row = states.lockByOrderItemId(item.orderItemId());
      boolean exists = row != null;
      if (!exists) {
        row = newRow(item, order, now);
      }
      ResolvedRate rate = resolveRate(order, item, kolRates);
      String version = fingerprint(order, item, rate, rule);
      if (exists && version.equals(row.getSourceVersion())) {
        continue;
      }
      applyRevision(tenantId, row, order, item, rate, rule, version, now);
    }
    deleteStaleRows(order, items, now);
  }

  /** Snapshots of item lines dropped by a later full sync stop counting (logical delete). */
  private void deleteStaleRows(OrderSourceDTO order, List<ItemSourceDTO> items, Instant now) {
    List<Long> itemIds = items.stream().map(ItemSourceDTO::orderItemId).toList();
    states.deleteStaleByOrder(order.orderId(), itemIds, now);
  }

  private void applyRevision(
      long tenantId,
      CommissionItemStateDO row,
      OrderSourceDTO order,
      ItemSourceDTO item,
      ResolvedRate rate,
      RuleSnapshot rule,
      String version,
      Instant now) {
    CommissionSettlementRules.Result result =
        CommissionSettlementRules.evaluate(
            new CommissionSettlementRules.Inputs(
                item.basisCny(),
                item.basisIncludesRefund(),
                item.successfulRefundCny(),
                item.kolId(),
                item.attributionType(),
                rate.rate(),
                item.settlementState(),
                item.settlementTime(),
                item.completedTime(),
                item.afterSales(),
                rule.freezeDays()));

    Long previousKol = row.getKolId();
    BigDecimal previousReleased =
        row.getReleasedCommissionCny() == null ? ZERO : row.getReleasedCommissionCny();
    BigDecimal released = previousReleased;
    BigDecimal walletDelta = ZERO;
    // Ownership change: claw the released amount back from the previous KOL's wallet (design
    // §8.3 / ADR-032 §10); the new owner accrues from zero and releases later.
    boolean ownershipChanged =
        previousKol != null
            && !Objects.equals(previousKol, item.kolId())
            && previousReleased.signum() > 0;
    if (ownershipChanged) {
      BigDecimal clawback = previousReleased.negate();
      wallet.postCommissionAdjustment(
          tenantId,
          previousKol,
          row.getId(),
          clawbackSourceKey(row.getId(), version, previousKol),
          clawback,
          now);
      walletDelta = walletDelta.add(clawback);
      released = ZERO;
    }
    // Same-owner decrease after release (refund, source correction): negative adjustment now.
    if (item.kolId() != null && result.accruedCny().compareTo(released) < 0) {
      BigDecimal decrease = result.accruedCny().subtract(released);
      wallet.postCommissionAdjustment(
          tenantId,
          item.kolId(),
          row.getId(),
          releaseSourceKey(row.getId(), version),
          decrease,
          now);
      walletDelta = walletDelta.add(decrease);
      released = result.accruedCny();
    }

    row.setKolId(item.kolId());
    row.setSourceVersion(version);
    row.setSourceUpdatedAt(now);
    row.setBasisCny(scale(item.basisCny()));
    row.setSuccessfulRefundCny(scale(item.successfulRefundCny()));
    row.setBasisIncludesRefund(item.basisIncludesRefund());
    row.setSettlementState(item.settlementState() == null ? "UNKNOWN" : item.settlementState());
    row.setSettlementTime(item.settlementTime());
    row.setCompletedTime(item.completedTime());
    row.setAfterSales(item.afterSales());
    row.setVerified(result.verified());
    row.setConfigVersion(rate.configVersion());
    row.setRuleVersionId(rule.id());
    row.setAccruedCommissionCny(result.accruedCny());
    row.setReleasedCommissionCny(released);
    row.setReleaseBasis(result.releaseBasis());
    row.setReleaseAt(result.releaseAt());
    row.setUpdateTime(now);
    if (row.getId() == null) {
      row.setId(IdWorker.getId());
      row.setCreateTime(now);
      states.insert(row);
    } else {
      states.updateById(row);
    }

    CommissionItemRevisionDO revision = new CommissionItemRevisionDO();
    revision.setId(IdWorker.getId());
    revision.setItemStateId(row.getId());
    revision.setSourceVersion(version);
    revision.setBasisCny(scale(item.basisCny()));
    revision.setSuccessfulRefundCny(scale(item.successfulRefundCny()));
    revision.setRate(rate.rate());
    revision.setConfigVersion(rate.configVersion());
    revision.setRuleVersionId(rule.id());
    revision.setAccruedCny(result.accruedCny());
    revision.setDeltaCny(walletDelta);
    revision.setReleaseBasis(result.releaseBasis());
    revision.setEvidence(evidence(item, rate, result));
    revision.setCreateTime(now);
    revisions.insert(revision);
    LOG.info(
        "commission item revised: order={} itemState={} orderItem={} verified={} accrued={}"
            + " delta={}",
        order.orderId(),
        row.getId(),
        item.orderItemId(),
        result.verified(),
        result.accruedCny(),
        walletDelta);
  }

  private CommissionItemStateDO newRow(ItemSourceDTO item, OrderSourceDTO order, Instant now) {
    CommissionItemStateDO row = new CommissionItemStateDO();
    row.setOrderId(order.orderId());
    row.setOrderItemId(item.orderItemId());
    row.setSourceKey("ORDER_ITEM:" + item.orderItemId());
    row.setAccruedCommissionCny(ZERO);
    row.setReleasedCommissionCny(ZERO);
    row.setReleaseBasis(CommissionSettlementRules.RELEASE_UNVERIFIED);
    row.setVerified(false);
    row.setAfterSales(false);
    row.setSettlementState("UNKNOWN");
    row.setCreateTime(now);
    return row;
  }

  private ResolvedRate resolveRate(
      OrderSourceDTO order, ItemSourceDTO item, Map<Long, PayTimeRule> kolRates) {
    // One unified rate per KOL from the promotion config effective at pay time; no product-level
    // override (2026-10-25 decision, ADR-032 §3, design §8.3).
    if (item.kolId() == null || order.payTime() == null) {
      return new ResolvedRate(null, null);
    }
    PayTimeRule cached = kolRates.get(item.kolId());
    if (cached == null) {
      KolPayTimeRateDTO found = kols.rateEffectiveAt(item.kolId(), order.payTime());
      cached =
          new PayTimeRule(
              found == null ? null : found.rate(), found == null ? null : found.versionNo());
      kolRates.put(item.kolId(), cached);
    }
    return new ResolvedRate(cached.rate(), cached.versionNo());
  }

  private RuleSnapshot ruleEffectiveAt(Instant payTime) {
    if (payTime == null) {
      return new RuleSnapshot(null, CommissionSettlementRules.DEFAULT_FREEZE_DAYS, "SETTLE_TIME");
    }
    CommissionRuleVersionDO row = ruleVersions.findEffectiveAt(payTime);
    if (row == null) {
      return new RuleSnapshot(null, CommissionSettlementRules.DEFAULT_FREEZE_DAYS, "SETTLE_TIME");
    }
    Integer days = null;
    String basis = null;
    try {
      JsonNode channel = JSON.readTree(row.getChannelRules()).get("WEIDIAN");
      if (channel != null) {
        days = channel.get("days") == null ? null : channel.get("days").asInt();
        basis = channel.get("basis") == null ? null : channel.get("basis").asString();
      }
    } catch (RuntimeException e) {
      LOG.warn("unreadable channel rules for rule version {}: {}", row.getId(), e.getMessage());
    }
    if (!"COMPLETED_PLUS_DAYS".equals(basis)) {
      basis = "SETTLE_TIME";
    }
    return new RuleSnapshot(
        row.getId(), days == null ? CommissionSettlementRules.DEFAULT_FREEZE_DAYS : days, basis);
  }

  private String fingerprint(
      OrderSourceDTO order, ItemSourceDTO item, ResolvedRate rate, RuleSnapshot rule) {
    String canonical =
        String.join(
            "|",
            "v1",
            text(order.payTime()),
            text(item.kolId()),
            String.valueOf(item.attributionType()),
            text(item.productId()),
            text(item.basisCny()),
            text(item.basisIncludesRefund()),
            text(item.successfulRefundCny()),
            item.settlementState() == null ? "" : item.settlementState(),
            text(item.settlementTime()),
            text(item.completedTime()),
            String.valueOf(item.afterSales()),
            text(rate.rate()),
            text(rate.configVersion()),
            text(rule.id()));
    return md5(canonical);
  }

  private static String evidence(
      ItemSourceDTO item, ResolvedRate rate, CommissionSettlementRules.Result result) {
    if (!result.verified()) {
      if (item.basisCny() == null) {
        return "Unverified: source commissionable amount pending M0 (Q-1017-1/2)";
      }
      if (item.kolId() == null) {
        return "Unverified: no KOL attribution";
      }
      return "Unverified: no pay-time rate";
    }
    // Verified rows always accrue from the pay-time promotion config (2026-10-25 decision).
    String source = "promotion config v" + rate.configVersion();
    return "Accrued from " + source + ", release " + result.releaseBasis();
  }

  static String releaseSourceKey(long itemStateId, String sourceVersion) {
    return "COMMISSION_ITEM:" + itemStateId + ":" + sourceVersion;
  }

  static String clawbackSourceKey(long itemStateId, String sourceVersion, long previousKol) {
    return releaseSourceKey(itemStateId, sourceVersion) + ":K" + previousKol;
  }

  private long currentTenant() {
    // Same resolution as the tenant line handler: programmatic scope first, then the login.
    Long tenant = com.tcrm.admin.shared.TenantScope.companyId();
    if (tenant == null) {
      tenant = currentTenant.currentLoginTenantId();
    }
    if (tenant == null) {
      throw new IllegalStateException("Commission snapshot sync requires a company scope");
    }
    return tenant;
  }

  private static BigDecimal scale(BigDecimal value) {
    return value == null ? null : value.setScale(2, java.math.RoundingMode.HALF_UP);
  }

  private static String text(Object value) {
    return value == null ? "" : value.toString();
  }

  private static String md5(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("MD5").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("MD5 unavailable", e);
    }
  }

  private record ResolvedRate(BigDecimal rate, Integer configVersion) {}

  private record PayTimeRule(BigDecimal rate, Integer versionNo) {}

  /** Rule version effective at pay time: id null when only defaults apply. */
  record RuleSnapshot(Long id, Integer freezeDays, String basis) {}
}
