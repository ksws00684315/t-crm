package com.tcrm.admin.commission.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.tcrm.admin.commission.service.CommissionSettlementService;
import com.tcrm.admin.kol.api.KolApi;
import com.tcrm.admin.kol.api.KolRefDTO;
import com.tcrm.admin.security.api.AccountDTO;
import com.tcrm.admin.security.api.CurrentUserContext;
import com.tcrm.admin.shared.BusinessException;
import com.tcrm.admin.shared.ErrorCode;
import com.tcrm.admin.shared.PageResult;
import com.tcrm.admin.shared.R;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RestController;

/** Order-level settlement projection that keeps mixed-KOL money private in OWN scope. */
@RestController
public class CommissionSettlementDetailsController {
  private final JdbcTemplate jdbc;
  private final CurrentUserContext current;
  private final KolApi kols;
  private final CommissionSettlementService settlement;

  public CommissionSettlementDetailsController(
      JdbcTemplate jdbc,
      CurrentUserContext current,
      KolApi kols,
      CommissionSettlementService settlement) {
    this.jdbc = jdbc;
    this.current = current;
    this.kols = kols;
    this.settlement = settlement;
  }

  public record Query(String orderNo, String channel, String shop, Integer page, Integer size) {}

  public record Settlement(
      String id,
      String orderNo,
      String channel,
      String shop,
      List<String> kolNames,
      String payment,
      String refund,
      String net,
      String commission,
      List<String> rates,
      String retained,
      String status,
      Instant businessAt,
      List<CommissionSettlementService.ItemLine> lines,
      boolean scopeLimited) {}

  private record OrderRow(
      long id,
      String orderNo,
      String shop,
      BigDecimal payment,
      BigDecimal refund,
      Instant businessAt) {}

  private record ItemState(
      long kolId,
      String kolName,
      boolean verified,
      BigDecimal basis,
      BigDecimal refunded,
      boolean includesRefund,
      BigDecimal commission,
      String rate) {}

  @GetMapping("/admin-api/commission/settlement-details")
  @SaCheckPermission("commission:view")
  public R<PageResult<Settlement>> list(@ModelAttribute Query query) {
    AccountDTO user = current.user();
    long tenant = requireTenant(user);
    int page = query.page() == null ? 1 : query.page();
    int size = query.size() == null ? 20 : query.size();
    if (page < 1
        || page > 10000
        || size < 1
        || size > 100
        || query.channel() != null
            && !query.channel().isBlank()
            && !"WEIDIAN".equals(query.channel())) {
      throw new BusinessException(ErrorCode.COMMON_VALIDATION_FAILED);
    }
    List<Long> visible =
        user.ownScope()
            ? kols.listByBdUserId(user.id()).stream().map(KolRefDTO::id).distinct().toList()
            : null;
    if (visible != null && visible.isEmpty()) {
      return R.ok(new PageResult<>(List.of(), 0L, page, size));
    }
    StringBuilder from =
        new StringBuilder(
            " FROM crm_order o JOIN crm_shop sh ON sh.id=o.shop_id AND sh.is_deleted=FALSE WHERE o.tenant_id=? AND o.is_deleted=FALSE AND EXISTS(SELECT 1 FROM crm_order_item i WHERE i.tenant_id=o.tenant_id AND i.order_id=o.id AND i.is_deleted=FALSE AND i.kol_id IS NOT NULL)");
    List<Object> args = new ArrayList<>(List.of(tenant));
    if (query.orderNo() != null && !query.orderNo().isBlank()) {
      from.append(" AND o.weidian_order_id ILIKE ?");
      args.add("%" + query.orderNo().strip() + "%");
    }
    if (query.shop() != null && !query.shop().isBlank()) {
      from.append(" AND sh.shop_name ILIKE ?");
      args.add("%" + query.shop().strip() + "%");
    }
    if (visible != null) {
      from.append(
              " AND EXISTS(SELECT 1 FROM crm_order_item i WHERE i.tenant_id=o.tenant_id AND i.order_id=o.id AND i.is_deleted=FALSE AND i.kol_id IN (")
          .append(String.join(",", java.util.Collections.nCopies(visible.size(), "?")))
          .append("))");
      args.addAll(visible);
    }
    Long total = jdbc.queryForObject("SELECT count(*)" + from, args.toArray(), Long.class);
    List<OrderRow> rows =
        jdbc.query(
            "SELECT o.id,o.weidian_order_id,sh.shop_name,o.pay_amount,(SELECT SUM(r.amount) FROM crm_refund r WHERE r.tenant_id=o.tenant_id AND r.order_id=o.id AND r.is_deleted=FALSE AND r.refund_status='SUCCESS'),COALESCE(o.pay_time,o.order_time)"
                + from
                + " ORDER BY o.pay_time DESC NULLS LAST,o.id DESC LIMIT ? OFFSET ?",
            (rs, index) ->
                new OrderRow(
                    rs.getLong(1),
                    rs.getString(2),
                    rs.getString(3),
                    rs.getBigDecimal(4),
                    rs.getBigDecimal(5),
                    rs.getTimestamp(6).toInstant()),
            append(args, size, (long) (page - 1) * size));
    List<Settlement> result = rows.stream().map(row -> project(tenant, row, visible)).toList();
    return R.ok(new PageResult<>(result, total == null ? 0L : total, page, size));
  }

  private Settlement project(long tenant, OrderRow order, List<Long> visible) {
    List<ItemState> states =
        jdbc.query(
            "SELECT i.kol_id,k.name,s.verified,s.basis_cny,s.successful_refund_cny,s.basis_includes_refund,s.accrued_commission_cny,rv.rate FROM crm_order_item i JOIN crm_kol k ON k.id=i.kol_id AND k.tenant_id=i.tenant_id LEFT JOIN crm_commission_item_state s ON s.tenant_id=i.tenant_id AND s.order_item_id=i.id LEFT JOIN crm_kol_commission_rate_version rv ON rv.id=s.rate_version_id WHERE i.tenant_id=? AND i.order_id=? AND i.is_deleted=FALSE AND i.kol_id IS NOT NULL ORDER BY i.id",
            (rs, index) ->
                new ItemState(
                    rs.getLong(1),
                    rs.getString(2),
                    rs.getObject(3) != null && rs.getBoolean(3),
                    rs.getBigDecimal(4),
                    rs.getBigDecimal(5),
                    rs.getObject(6) != null && rs.getBoolean(6),
                    rs.getBigDecimal(7),
                    rs.getBigDecimal(8) == null
                        ? null
                        : rs.getBigDecimal(8).setScale(4).toPlainString()),
            tenant,
            order.id());
    boolean scopeLimited =
        visible != null && states.stream().anyMatch(item -> !visible.contains(item.kolId()));
    List<ItemState> inScope =
        visible == null
            ? states
            : states.stream().filter(item -> visible.contains(item.kolId())).toList();
    boolean verified = !inScope.isEmpty() && inScope.stream().allMatch(ItemState::verified);
    BigDecimal commission =
        verified
            ? inScope.stream().map(ItemState::commission).reduce(BigDecimal.ZERO, BigDecimal::add)
            : null;
    BigDecimal basis =
        verified
            ? inScope.stream()
                .map(
                    item ->
                        item.includesRefund()
                            ? item.basis()
                            : item.basis().subtract(item.refunded()))
                .reduce(BigDecimal.ZERO, BigDecimal::add)
            : null;
    List<String> rates =
        inScope.stream()
            .map(ItemState::rate)
            .filter(java.util.Objects::nonNull)
            .distinct()
            .toList();
    List<Long> visibleIds =
        visible == null ? null : inScope.stream().map(ItemState::kolId).distinct().toList();
    List<CommissionSettlementService.ItemLine> lines =
        settlement.linesForOrder(tenant, order.id(), visibleIds);
    return new Settlement(
        Long.toString(order.id()),
        order.orderNo(),
        "WEIDIAN",
        order.shop(),
        inScope.stream().map(ItemState::kolName).distinct().toList(),
        scopeLimited ? null : money(order.payment()),
        scopeLimited || order.refund() == null ? null : money(order.refund()),
        null,
        scopeLimited || commission == null ? null : money(commission),
        rates,
        scopeLimited || commission == null || basis == null
            ? null
            : money(basis.subtract(commission)),
        verified ? "POSTED" : "UNVERIFIED",
        order.businessAt(),
        lines,
        scopeLimited);
  }

  private static long requireTenant(AccountDTO user) {
    if (user.tenantId() == null || user.tenantId() <= 0) {
      throw new BusinessException(ErrorCode.AUTH_FORBIDDEN);
    }
    return user.tenantId();
  }

  private static Object[] append(List<Object> values, Object... tail) {
    List<Object> result = new ArrayList<>(values);
    result.addAll(List.of(tail));
    return result.toArray();
  }

  private static String money(BigDecimal value) {
    return value.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString();
  }
}
