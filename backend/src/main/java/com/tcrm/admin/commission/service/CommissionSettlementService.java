package com.tcrm.admin.commission.service;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.tcrm.admin.shared.BusinessException;
import com.tcrm.admin.shared.ErrorCode;
import com.tcrm.admin.shared.api.CommissionSettlementApi;
import com.tcrm.admin.shared.api.SystemConfigApi;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Commission source snapshots, historical rates, release rules and order-line accounting. */
@Service
public class CommissionSettlementService implements CommissionSettlementApi {
  private static final BigDecimal ZERO = new BigDecimal("0.00");
  private final JdbcTemplate jdbc;
  private final CommissionWalletService wallet;
  private final SystemConfigApi configs;
  private final Clock clock;

  public CommissionSettlementService(
      JdbcTemplate jdbc, CommissionWalletService wallet, SystemConfigApi configs) {
    this(jdbc, wallet, configs, Clock.systemUTC());
  }

  CommissionSettlementService(
      JdbcTemplate jdbc, CommissionWalletService wallet, SystemConfigApi configs, Clock clock) {
    this.jdbc = jdbc;
    this.wallet = wallet;
    this.configs = configs;
    this.clock = clock;
  }

  public record ItemLine(
      String id,
      String orderNo,
      String itemId,
      String product,
      String sku,
      String base,
      String rate,
      String rateVersion,
      String ruleVersion,
      String commission,
      String adjustment,
      String releaseBasis,
      Instant businessAt,
      String direction,
      String kol) {}

  public record Summary(
      String estimated,
      String frozen,
      String available,
      String occupied,
      String cumulativePaid,
      String deducted,
      String pendingDeduction,
      long unverified) {}

  public record Config(String currency, String minimum, String version, int days) {}

  @Transactional(readOnly = true)
  public Config currentConfig(long tenantId) {
    List<Config> rows =
        jdbc.query(
            "SELECT version_no,minimum_withdrawal_cny,channel_rules::text FROM crm_commission_rule_version WHERE tenant_id=? ORDER BY version_no DESC LIMIT 1",
            (rs, index) ->
                new Config(
                    "CNY",
                    money(rs.getBigDecimal(2)),
                    Long.toString(rs.getLong(1)),
                    freezeDays(rs.getString(3))),
            tenantId);
    return rows.isEmpty()
        ? new Config(
            "CNY",
            new BigDecimal(configs.getInt("commission.minimum-withdrawal-cny"))
                .setScale(2)
                .toPlainString(),
            "0",
            7)
        : rows.getFirst();
  }

  @Transactional(rollbackFor = Exception.class)
  public Config saveConfig(
      long tenantId, long actorId, String expectedVersion, BigDecimal minimum, int days) {
    if (tenantId <= 0
        || actorId <= 0
        || minimum == null
        || minimum.signum() < 0
        || minimum.scale() > 2
        || days < 0
        || days > 365) {
      throw new BusinessException(ErrorCode.COMMON_VALIDATION_FAILED);
    }
    Config current = currentConfig(tenantId);
    if (!current.version().equals(expectedVersion)) {
      throw new BusinessException(ErrorCode.COMMON_CONFLICT);
    }
    long version = Long.parseLong(current.version()) + 1;
    jdbc.update(
        "INSERT INTO crm_commission_rule_version(id,tenant_id,version_no,currency,minimum_withdrawal_cny,channel_rules,actor_id,create_time) VALUES(?,?,?,'CNY',?,?::jsonb,?,?)",
        IdWorker.getId(),
        tenantId,
        version,
        minimum.setScale(2, RoundingMode.UNNECESSARY),
        "{\"WEIDIAN\":{\"days\":" + days + "}}",
        actorId,
        Timestamp.from(now()));
    return new Config("CNY", money(minimum), Long.toString(version), days);
  }

  /** Stores a source version and appends only the commission difference for that version. */
  @Transactional(rollbackFor = Exception.class)
  public void applySourceSnapshot(CommissionSettlementApi.SourceSnapshot s) {
    validateSource(s);
    SourceItem item = lockSourceItem(s.tenantId(), s.orderItemId());
    if (item == null) {
      throw new BusinessException(ErrorCode.COMMON_NOT_FOUND);
    }
    if (s.sourceUpdatedAt()
        .isBefore(item.sourceUpdatedAt() == null ? Instant.EPOCH : item.sourceUpdatedAt())) {
      return;
    }
    if (s.sourceVersion().equals(item.sourceVersion())) {
      return;
    }
    BigDecimal effectiveBasis = CommissionSettlementRules.effectiveBasis(s);
    boolean attributed =
        item.kolId() != null && (item.attributionType() == 1 || item.attributionType() == 2);
    Rate rate =
        attributed && effectiveBasis != null
            ? rateAt(s.tenantId(), item.kolId(), item.payTime())
            : null;
    boolean verified = attributed && effectiveBasis != null && rate != null;
    BigDecimal accrued =
        verified ? CommissionSettlementRules.commission(effectiveBasis, rate.value()) : ZERO;
    Rule rule = ruleAt(s.tenantId(), item.payTime());
    String releaseBasis = CommissionSettlementRules.releaseBasis(s, verified);
    Instant releaseAt = CommissionSettlementRules.releaseAt(s, releaseBasis, rule.days());
    boolean released = releaseAt != null && !releaseAt.isAfter(clock.instant());
    BigDecimal posted = released ? accrued : ZERO;
    BigDecimal delta =
        posted.subtract(item.postedCommission()).setScale(2, RoundingMode.UNNECESSARY);
    Instant now = now();

    jdbc.update(
        "INSERT INTO crm_commission_item_revision (id, tenant_id, item_state_id, source_version, basis_cny, successful_refund_cny, rate, rate_version_id, rule_version_id, accrued_cny, delta_cny, release_basis, evidence, create_time) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        IdWorker.getId(),
        s.tenantId(),
        item.stateId(),
        s.sourceVersion(),
        verified ? s.basisCny() : null,
        verified ? s.successfulRefundCny() : null,
        rate == null ? null : rate.value(),
        rate == null ? null : rate.id(),
        rule.id() == 0 ? null : rule.id(),
        verified ? accrued : null,
        verified ? delta : null,
        releaseBasis,
        truncate(s.evidence(), 1000),
        Timestamp.from(now));

    jdbc.update(
        "UPDATE crm_commission_item_state SET source_key = ?, source_version = ?, source_updated_at = ?, basis_cny = ?, successful_refund_cny = ?, basis_semantics = ?, basis_includes_refund = ?, settlement_state = ?, settlement_time = ?, completed_time = ?, after_sales = ?, verified = ?, rate_version_id = ?, rule_version_id = ?, accrued_commission_cny = ?, released_commission_cny = ?, release_basis = ?, release_at = ?, update_time = ? WHERE tenant_id = ? AND id = ?",
        s.sourceKey(),
        s.sourceVersion(),
        Timestamp.from(s.sourceUpdatedAt()),
        verified ? s.basisCny() : null,
        verified ? s.successfulRefundCny() : null,
        verified ? s.basisSemantics() : null,
        verified ? s.basisIncludesRefund() : null,
        s.settlementState(),
        timestamp(s.settlementTime()),
        timestamp(s.completedTime()),
        s.afterSales(),
        verified,
        rate == null ? null : rate.id(),
        rule.id() == 0 ? null : rule.id(),
        verified ? accrued : ZERO,
        posted,
        releaseBasis,
        timestamp(releaseAt),
        Timestamp.from(now),
        s.tenantId(),
        item.stateId());
    if (verified && delta.signum() != 0) {
      wallet.postSignedEntry(
          s.tenantId(),
          item.kolId(),
          item.orderItemId(),
          snapshotLedgerKey(item.orderItemId(), s.sourceVersion()),
          "COMMISSION_ADJUSTMENT",
          delta,
          s.sourceUpdatedAt(),
          null);
    }
    updateRefundBlocks(s.tenantId(), item.orderItemId());
  }

  /** Materializes newly imported order lines as explicitly unverified accounting rows. */
  @Transactional(rollbackFor = Exception.class)
  public void ensureOrderItems(long tenantId, long kolId) {
    jdbc.update(
        "INSERT INTO crm_commission_item_state (id,tenant_id,order_id,order_item_id,kol_id,source_key,settlement_state,after_sales,verified,accrued_commission_cny,released_commission_cny,release_basis,create_time,update_time) SELECT oi.id,oi.tenant_id,o.id,oi.id,oi.kol_id,'ORDER_ITEM:'||oi.id,'UNKNOWN',FALSE,FALSE,0,0,'UNVERIFIED',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP FROM crm_order_item oi JOIN crm_order o ON o.id=oi.order_id AND o.tenant_id=oi.tenant_id WHERE oi.tenant_id=? AND oi.kol_id=? AND oi.is_deleted=FALSE AND o.is_deleted=FALSE ON CONFLICT (tenant_id,order_item_id) DO NOTHING",
        tenantId,
        kolId);
  }

  /**
   * Holds refund lines from new withdrawals until a trusted source refresh confirms the net basis.
   */
  @Override
  @Transactional(rollbackFor = Exception.class)
  public void refreshRefundState(long tenantId, long orderItemId) {
    List<Long> states =
        jdbc.query(
            "SELECT id FROM crm_commission_item_state WHERE tenant_id=? AND order_item_id=? FOR UPDATE",
            (rs, index) -> rs.getLong(1),
            tenantId,
            orderItemId);
    if (states.isEmpty()) {
      return;
    }
    Boolean refundNeedsReview =
        jdbc.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM crm_refund WHERE tenant_id=? AND order_item_id=? AND is_deleted=FALSE AND refund_status IN ('PROCESSING','SUCCESS','UNKNOWN'))",
            Boolean.class,
            tenantId,
            orderItemId);
    boolean blocked = Boolean.TRUE.equals(refundNeedsReview);
    jdbc.update(
        "UPDATE crm_commission_item_state SET after_sales=?,release_basis=CASE WHEN ? THEN 'AFTER_SALES' ELSE release_basis END,release_at=CASE WHEN ? THEN NULL ELSE release_at END,update_time=? WHERE tenant_id=? AND id=?",
        blocked,
        blocked,
        blocked,
        Timestamp.from(now()),
        tenantId,
        states.getFirst());
    updateRefundBlocks(tenantId, orderItemId);
  }

  /** Releases eligible fallback rows on reads and before withdrawal validation. */
  @Transactional(rollbackFor = Exception.class)
  public int releaseMatured(long tenantId, long kolId) {
    List<ReleaseRow> rows =
        jdbc.query(
            "SELECT id, order_item_id, accrued_commission_cny, released_commission_cny, release_at FROM crm_commission_item_state WHERE tenant_id = ? AND kol_id = ? AND verified = TRUE AND release_basis IN ('CONFIRMED','FALLBACK') AND release_at <= ? ORDER BY release_at, order_item_id FOR UPDATE",
            (rs, index) ->
                new ReleaseRow(
                    rs.getLong("id"),
                    rs.getLong("order_item_id"),
                    rs.getBigDecimal("accrued_commission_cny"),
                    rs.getBigDecimal("released_commission_cny"),
                    rs.getTimestamp("release_at").toInstant()),
            tenantId,
            kolId,
            Timestamp.from(clock.instant()));
    int changed = 0;
    for (ReleaseRow row : rows) {
      BigDecimal delta =
          row.accrued().subtract(row.released()).setScale(2, RoundingMode.UNNECESSARY);
      if (delta.signum() == 0) {
        continue;
      }
      wallet.postSignedEntry(
          tenantId,
          kolId,
          row.itemId(),
          "COMMISSION_RELEASE:" + row.itemId(),
          "COMMISSION_ADJUSTMENT",
          delta,
          row.releaseAt(),
          null);
      jdbc.update(
          "UPDATE crm_commission_item_state SET released_commission_cny = accrued_commission_cny, update_time = ? WHERE tenant_id = ? AND id = ?",
          Timestamp.from(now()),
          tenantId,
          row.stateId());
      changed++;
    }
    return changed;
  }

  @Transactional(rollbackFor = Exception.class)
  public long setRate(
      long tenantId,
      long kolId,
      BigDecimal rate,
      Instant effectiveFrom,
      long actorId,
      String evidence) {
    if (tenantId <= 0
        || kolId <= 0
        || actorId <= 0
        || rate == null
        || rate.scale() > 4
        || rate.signum() < 0
        || rate.compareTo(BigDecimal.ONE) > 0
        || effectiveFrom == null) {
      throw new BusinessException(ErrorCode.COMMON_VALIDATION_FAILED);
    }
    if (com.tcrm.admin.shared.TenantScope.callCompany(
            tenantId,
            () ->
                jdbc.queryForObject(
                    "SELECT COUNT(*) FROM crm_kol WHERE tenant_id=? AND id=? AND is_deleted=FALSE",
                    Long.class,
                    tenantId,
                    kolId))
        != 1L) {
      throw new BusinessException(ErrorCode.COMMON_NOT_FOUND);
    }
    long id = IdWorker.getId();
    jdbc.update(
        "INSERT INTO crm_kol_commission_rate_version (id, tenant_id, kol_id, rate, effective_from, actor_id, evidence, create_time) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
        id,
        tenantId,
        kolId,
        rate.setScale(4, RoundingMode.UNNECESSARY),
        Timestamp.from(effectiveFrom),
        actorId,
        truncate(evidence, 1000),
        Timestamp.from(now()));
    jdbc.update(
        "UPDATE crm_kol SET default_rate=?,update_by=?,update_time=? WHERE tenant_id=? AND id=? AND is_deleted=FALSE",
        rate.setScale(4, RoundingMode.UNNECESSARY),
        actorId,
        Timestamp.from(now()),
        tenantId,
        kolId);
    return id;
  }

  @Override
  public long recordRateVersion(
      long tenantId,
      long kolId,
      BigDecimal rate,
      Instant effectiveFrom,
      long actorId,
      String evidence) {
    return setRate(tenantId, kolId, rate, effectiveFrom, actorId, evidence);
  }

  @Transactional(readOnly = true)
  public Summary summary(long tenantId, long kolId) {
    BigDecimal estimated =
        scalar(
            "SELECT COALESCE(SUM(accrued_commission_cny),0) FROM crm_commission_item_state WHERE tenant_id=? AND kol_id=? AND verified=TRUE",
            tenantId,
            kolId);
    BigDecimal frozen =
        scalar(
            "SELECT COALESCE(SUM(accrued_commission_cny-released_commission_cny),0) FROM crm_commission_item_state WHERE tenant_id=? AND kol_id=? AND verified=TRUE",
            tenantId,
            kolId);
    BigDecimal occupied =
        scalar(
            "SELECT COALESCE(SUM(a.occupied_cny),0) FROM crm_commission_withdrawal_allocation a JOIN crm_commission_withdrawal w ON w.id=a.withdrawal_id AND w.tenant_id=a.tenant_id WHERE w.tenant_id=? AND w.kol_id=? AND w.status IN ('PENDING_REVIEW','PENDING_PAYMENT')",
            tenantId,
            kolId);
    BigDecimal paid =
        scalar(
            "SELECT COALESCE(-SUM(amount_cny),0) FROM crm_commission_ledger WHERE tenant_id=? AND kol_id=? AND entry_type='COMMISSION_WITHDRAWAL'",
            tenantId,
            kolId);
    BigDecimal balance = wallet.currentBalance(tenantId, kolId);
    long unverified =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM crm_commission_item_state WHERE tenant_id=? AND kol_id=? AND verified=FALSE",
            Long.class,
            tenantId,
            kolId);
    BigDecimal available = balance.subtract(occupied).setScale(2, RoundingMode.UNNECESSARY);
    BigDecimal deducted =
        scalar(
            "SELECT COALESCE(-SUM(amount_cny),0) FROM crm_commission_ledger WHERE tenant_id=? AND kol_id=? AND entry_type='PREPAY_DEDUCT'",
            tenantId,
            kolId);
    BigDecimal pendingDeduction =
        scalar(
            "SELECT COALESCE(SUM(-l.amount_cny-COALESCE(r.recovered,0)),0) FROM crm_commission_ledger l LEFT JOIN (SELECT deduction_ledger_id,SUM(amount_cny) recovered FROM crm_commission_recovery WHERE tenant_id=? AND kol_id=? GROUP BY deduction_ledger_id) r ON r.deduction_ledger_id=l.id WHERE l.tenant_id=? AND l.kol_id=? AND l.entry_type='PREPAY_DEDUCT'",
            tenantId,
            kolId,
            tenantId,
            kolId);
    return new Summary(
        money(estimated),
        money(frozen),
        money(available),
        money(occupied),
        money(paid),
        money(deducted),
        money(pendingDeduction),
        unverified);
  }

  @Transactional(readOnly = true)
  public List<ItemLine> lines(long tenantId, long kolId, int limit, int offset) {
    return jdbc.query(
        "SELECT id,order_no,item_id,product,sku,base,rate,rate_version,rule_version,commission,adjustment,release_basis,business_at,direction,kol FROM (SELECT r.id::varchar id,o.weidian_order_id order_no,i.sub_order_id item_id,i.item_name product,i.sku_name sku,r.basis_cny base,rate.rate,r.rate_version_id rate_version,r.rule_version_id rule_version,r.delta_cny commission,CASE WHEN r.delta_cny<0 THEN r.delta_cny ELSE 0::numeric END adjustment,r.release_basis,COALESCE(s.source_updated_at,o.pay_time,o.order_time) business_at,CASE WHEN r.delta_cny<0 THEN 'DEBIT' ELSE 'CREDIT' END direction,s.kol_id kol FROM crm_commission_item_revision r JOIN crm_commission_item_state s ON s.id=r.item_state_id AND s.tenant_id=r.tenant_id JOIN crm_order o ON o.id=s.order_id AND o.tenant_id=s.tenant_id JOIN crm_order_item i ON i.id=s.order_item_id AND i.tenant_id=s.tenant_id LEFT JOIN crm_kol_commission_rate_version rate ON rate.id=r.rate_version_id WHERE r.tenant_id=? AND s.kol_id=? UNION ALL SELECT s.id::varchar,o.weidian_order_id,i.sub_order_id,i.item_name,i.sku_name,NULL::numeric,NULL::numeric,NULL::bigint,s.rule_version_id,NULL::numeric,NULL::numeric,s.release_basis,COALESCE(o.pay_time,o.order_time),'CREDIT',s.kol_id FROM crm_commission_item_state s JOIN crm_order o ON o.id=s.order_id AND o.tenant_id=s.tenant_id JOIN crm_order_item i ON i.id=s.order_item_id AND i.tenant_id=s.tenant_id WHERE s.tenant_id=? AND s.kol_id=? AND NOT EXISTS(SELECT 1 FROM crm_commission_item_revision r WHERE r.tenant_id=s.tenant_id AND r.item_state_id=s.id)) line ORDER BY business_at DESC,id DESC LIMIT ? OFFSET ?",
        (rs, index) ->
            new ItemLine(
                rs.getString("id"),
                rs.getString("order_no"),
                rs.getString("item_id"),
                rs.getString("product"),
                rs.getString("sku"),
                rs.getBigDecimal("base") == null ? null : money(rs.getBigDecimal("base")),
                rs.getBigDecimal("rate") == null
                    ? null
                    : rs.getBigDecimal("rate").setScale(4).toPlainString(),
                nullableId(rs, "rate_version"),
                nullableId(rs, "rule_version"),
                rs.getBigDecimal("commission") == null
                    ? null
                    : money(rs.getBigDecimal("commission")),
                rs.getBigDecimal("adjustment") == null
                    ? null
                    : money(rs.getBigDecimal("adjustment")),
                rs.getString("release_basis"),
                rs.getTimestamp("business_at").toInstant(),
                rs.getString("direction"),
                Long.toString(rs.getLong("kol"))),
        tenantId,
        kolId,
        tenantId,
        kolId,
        limit,
        offset);
  }

  @Transactional(readOnly = true)
  public List<ItemLine> linesForOrder(long tenantId, long orderId, List<Long> kolIds) {
    if (kolIds != null && kolIds.isEmpty()) {
      return List.of();
    }
    String sql =
        "SELECT COALESCE(r.id,s.id)::varchar id,o.weidian_order_id order_no,i.sub_order_id item_id,i.item_name product,i.sku_name sku,r.basis_cny base,rv.rate,r.rate_version_id rate_version,r.rule_version_id rule_version,r.delta_cny commission,CASE WHEN r.delta_cny<0 THEN r.delta_cny ELSE 0::numeric END adjustment,COALESCE(r.release_basis,s.release_basis) release_basis,COALESCE(s.source_updated_at,o.pay_time,o.order_time) business_at,CASE WHEN r.delta_cny<0 THEN 'DEBIT' ELSE 'CREDIT' END direction,s.kol_id kol FROM crm_commission_item_state s JOIN crm_order o ON o.id=s.order_id AND o.tenant_id=s.tenant_id JOIN crm_order_item i ON i.id=s.order_item_id AND i.tenant_id=s.tenant_id LEFT JOIN crm_commission_item_revision r ON r.tenant_id=s.tenant_id AND r.item_state_id=s.id LEFT JOIN crm_kol_commission_rate_version rv ON rv.id=r.rate_version_id WHERE s.tenant_id=? AND s.order_id=?";
    List<Object> args = new java.util.ArrayList<>(List.of(tenantId, orderId));
    if (kolIds != null) {
      sql +=
          " AND s.kol_id IN ("
              + String.join(",", java.util.Collections.nCopies(kolIds.size(), "?"))
              + ")";
      args.addAll(kolIds);
    }
    sql += " ORDER BY business_at DESC,id DESC LIMIT 10001";
    List<ItemLine> rows = jdbc.query(sql, this::mapItemLine, args.toArray());
    if (rows.size() > 10000) {
      throw new BusinessException(ErrorCode.COMMON_VALIDATION_FAILED);
    }
    return rows;
  }

  private ItemLine mapItemLine(ResultSet rs, int index) throws SQLException {
    return new ItemLine(
        rs.getString("id"),
        rs.getString("order_no"),
        rs.getString("item_id"),
        rs.getString("product"),
        rs.getString("sku"),
        rs.getBigDecimal("base") == null ? null : money(rs.getBigDecimal("base")),
        rs.getBigDecimal("rate") == null
            ? null
            : rs.getBigDecimal("rate").setScale(4).toPlainString(),
        nullableId(rs, "rate_version"),
        nullableId(rs, "rule_version"),
        rs.getBigDecimal("commission") == null ? null : money(rs.getBigDecimal("commission")),
        rs.getBigDecimal("adjustment") == null ? null : money(rs.getBigDecimal("adjustment")),
        rs.getString("release_basis"),
        rs.getTimestamp("business_at").toInstant(),
        rs.getString("direction"),
        Long.toString(rs.getLong("kol")));
  }

  private SourceItem lockSourceItem(long tenantId, long orderItemId) {
    List<SourceItem> rows =
        jdbc.query(
            "SELECT s.id,s.order_item_id,s.kol_id,s.source_version,s.source_updated_at,s.released_commission_cny,i.kol_id,i.attribution_type,o.pay_time FROM crm_commission_item_state s JOIN crm_order_item i ON i.id=s.order_item_id AND i.tenant_id=s.tenant_id JOIN crm_order o ON o.id=s.order_id AND o.tenant_id=s.tenant_id WHERE s.tenant_id=? AND s.order_item_id=? FOR UPDATE OF s",
            (rs, index) ->
                new SourceItem(
                    rs.getLong(1),
                    rs.getLong(2),
                    nullableLong(rs, 3),
                    rs.getString(4),
                    instant(rs, 5),
                    rs.getBigDecimal(6),
                    nullableLong(rs, 7),
                    rs.getInt(8),
                    instant(rs, 9)),
            tenantId,
            orderItemId);
    return rows.isEmpty() ? null : rows.getFirst();
  }

  private Rate rateAt(long tenantId, long kolId, Instant payTime) {
    if (payTime == null) {
      return null;
    }
    List<Rate> rates =
        jdbc.query(
            "SELECT id,rate FROM crm_kol_commission_rate_version WHERE tenant_id=? AND kol_id=? AND effective_from<=? ORDER BY effective_from DESC,id DESC LIMIT 1",
            (rs, index) -> new Rate(rs.getLong(1), rs.getBigDecimal(2)),
            tenantId,
            kolId,
            Timestamp.from(payTime));
    return rates.isEmpty() ? null : rates.getFirst();
  }

  private Rule ruleAt(long tenantId, Instant payTime) {
    List<Rule> rules =
        jdbc.query(
            "SELECT id,channel_rules FROM crm_commission_rule_version WHERE tenant_id=? AND create_time<=? ORDER BY version_no DESC LIMIT 1",
            (rs, index) -> {
              String rulesJson = rs.getString(2);
              return new Rule(rs.getLong(1), freezeDays(rulesJson));
            },
            tenantId,
            Timestamp.from(payTime == null ? clock.instant() : payTime));
    return rules.isEmpty() ? new Rule(0L, 7) : rules.getFirst();
  }

  private static int freezeDays(String json) {
    java.util.regex.Matcher m =
        java.util.regex.Pattern.compile(
                "\\\"WEIDIAN\\\"\\s*:\\s*\\{[^}]*\\\"days\\\"\\s*:\\s*(\\d+)")
            .matcher(json);
    return m.find() ? Math.min(Integer.parseInt(m.group(1)), 365) : 7;
  }

  private void updateRefundBlocks(long tenantId, long orderItemId) {
    List<WithdrawalLink> linked =
        jdbc.query(
            "SELECT w.id,w.kol_id,w.paused,w.pause_reason FROM crm_commission_withdrawal w JOIN crm_commission_withdrawal_allocation a ON a.tenant_id=w.tenant_id AND a.withdrawal_id=w.id WHERE w.tenant_id=? AND a.order_item_id=? AND w.status IN ('PENDING_REVIEW','PENDING_PAYMENT') ORDER BY w.id FOR UPDATE OF w",
            (rs, index) ->
                new WithdrawalLink(rs.getLong(1), rs.getLong(2), rs.getBoolean(3), rs.getString(4)),
            tenantId,
            orderItemId);
    for (WithdrawalLink row : linked) {
      Boolean anyBlocked =
          jdbc.queryForObject(
              "SELECT EXISTS(SELECT 1 FROM crm_commission_withdrawal_allocation a JOIN crm_commission_item_state s ON s.tenant_id=a.tenant_id AND s.id=a.item_state_id WHERE a.tenant_id=? AND a.withdrawal_id=? AND (s.verified=FALSE OR s.after_sales=TRUE OR a.occupied_cny>s.released_commission_cny))",
              Boolean.class,
              tenantId,
              row.withdrawalId());
      boolean shouldPause = Boolean.TRUE.equals(anyBlocked);
      String reason = shouldPause ? "退款或提现来源金额需要核实" : null;
      if (shouldPause == row.paused() && java.util.Objects.equals(reason, row.pauseReason())) {
        continue;
      }
      jdbc.update(
          "UPDATE crm_commission_withdrawal SET paused=?,pause_reason=?,update_time=? WHERE tenant_id=? AND id=?",
          shouldPause,
          reason,
          Timestamp.from(now()),
          tenantId,
          row.withdrawalId());
      jdbc.update(
          "INSERT INTO crm_commission_withdrawal_history(id,tenant_id,withdrawal_id,action,actor_id,note,create_time) VALUES(?,?,?,?,NULL,?,?)",
          IdWorker.getId(),
          tenantId,
          row.withdrawalId(),
          shouldPause ? "PAUSED" : "RESUMED",
          shouldPause ? "退款或佣金来源需要核实" : "来源核实完成",
          Timestamp.from(now()));
    }
  }

  private static void validateSource(CommissionSettlementApi.SourceSnapshot s) {
    if (s == null
        || s.tenantId() <= 0
        || s.orderItemId() <= 0
        || s.sourceKey() == null
        || s.sourceKey().isBlank()
        || s.sourceKey().length() > 160
        || s.sourceVersion() == null
        || s.sourceVersion().isBlank()
        || s.sourceVersion().length() > 160
        || s.sourceUpdatedAt() == null
        || !List.of("CONFIRMED", "UNSETTLED", "UNKNOWN").contains(s.settlementState())
        || s.basisCny() != null && (s.basisCny().signum() < 0 || s.basisCny().scale() > 2)
        || s.successfulRefundCny() != null
            && (s.successfulRefundCny().signum() < 0 || s.successfulRefundCny().scale() > 2)) {
      throw new BusinessException(ErrorCode.COMMON_VALIDATION_FAILED);
    }
  }

  private BigDecimal scalar(String sql, Object... args) {
    BigDecimal value = jdbc.queryForObject(sql, BigDecimal.class, args);
    return value == null ? ZERO : value.setScale(2, RoundingMode.HALF_UP);
  }

  private static String nullableId(ResultSet rs, String name) throws SQLException {
    long value = rs.getLong(name);
    return rs.wasNull() ? null : Long.toString(value);
  }

  private static Long nullableLong(ResultSet rs, int index) throws SQLException {
    long value = rs.getLong(index);
    return rs.wasNull() ? null : value;
  }

  private static Instant instant(ResultSet rs, int index) throws SQLException {
    Timestamp value = rs.getTimestamp(index);
    return value == null ? null : value.toInstant();
  }

  private static Timestamp timestamp(Instant value) {
    return value == null ? null : Timestamp.from(value);
  }

  private Instant now() {
    return clock.instant().truncatedTo(ChronoUnit.MILLIS);
  }

  private static String truncate(String value, int max) {
    return value == null || value.length() <= max ? value : value.substring(0, max);
  }

  private static String snapshotLedgerKey(long itemId, String sourceVersion) {
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256")
              .digest(sourceVersion.getBytes(StandardCharsets.UTF_8));
      return "COMMISSION_ITEM:" + itemId + ":" + HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is required by the Java runtime", impossible);
    }
  }

  private static String money(BigDecimal value) {
    return value.setScale(2, RoundingMode.HALF_UP).toPlainString();
  }

  private record Rate(long id, BigDecimal value) {}

  private record Rule(long id, int days) {}

  private record WithdrawalLink(
      long withdrawalId, long kolId, boolean paused, String pauseReason) {}

  private record ReleaseRow(
      long stateId, long itemId, BigDecimal accrued, BigDecimal released, Instant releaseAt) {}

  private record SourceItem(
      long stateId,
      long orderItemId,
      Long kolId,
      String sourceVersion,
      Instant sourceUpdatedAt,
      BigDecimal postedCommission,
      Long itemKol,
      int attributionType,
      Instant payTime) {}
}
