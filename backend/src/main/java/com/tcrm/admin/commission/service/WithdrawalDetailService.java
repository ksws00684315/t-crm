package com.tcrm.admin.commission.service;

import com.tcrm.admin.commission.mapper.WithdrawalDetailMapper;
import com.tcrm.admin.commission.model.WithdrawalDetailRow;
import com.tcrm.admin.kol.api.KolApi;
import com.tcrm.admin.security.api.AccountDTO;
import com.tcrm.admin.security.api.PostTemplate;
import com.tcrm.admin.security.api.Role;
import com.tcrm.admin.shared.BusinessException;
import com.tcrm.admin.shared.ErrorCode;
import com.tcrm.admin.shared.PageResult;
import com.tcrm.admin.shared.SecretCipher;
import com.tcrm.admin.shared.TenantScope;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/** Reads only persisted application-time snapshots. No current balance is used as history. */
@Service
public class WithdrawalDetailService {
  private final WithdrawalDetailMapper mapper;
  private final KolApi kols;
  private final ObjectMapper json;
  private final SecretCipher cipher;

  public WithdrawalDetailService(
      WithdrawalDetailMapper mapper, KolApi kols, ObjectMapper json, SecretCipher cipher) {
    this.mapper = mapper;
    this.kols = kols;
    this.json = json;
    this.cipher = cipher;
  }

  @Transactional(readOnly = true)
  public WithdrawalDetailDTO detail(AccountDTO user, long id) {
    authorize(user);
    return TenantScope.callCompany(
        user.tenantId(),
        () -> {
          var row = accessible(user, id);
          var detail = json.readValue(row.detailJson(), WithdrawalDetailDTO.class);
          var account =
              json.readValue(
                  cipher.decrypt(row.accountCipher()), WithdrawalDetailDTO.Account.class);
          boolean admin = user.role() == Role.SUPER_ADMIN;
          if (!admin) {
            account =
                new WithdrawalDetailDTO.Account(
                    account.type(),
                    AccountMasker.mask(account.account()),
                    AccountMasker.mask(account.holder()),
                    account.currency(),
                    account.network());
          }
          return detail.withIdentityAndAccount(
              row.id().toString(),
              row.kolId().toString(),
              account,
              admin || user.postTemplate() == PostTemplate.FINANCE);
        });
  }

  @Transactional(readOnly = true)
  public PageResult<WithdrawalDetailDTO.Order> orders(
      AccountDTO user, long id, int page, int size) {
    authorize(user);
    if (page < 1 || page > PageResult.MAX_PAGE || size < 1 || size > 100) {
      throw new BusinessException(ErrorCode.COMMON_VALIDATION_FAILED);
    }
    return TenantScope.callCompany(
        user.tenantId(),
        () -> {
          accessible(user, id);
          var items =
              mapper.orders(user.tenantId(), id, size, (long) (page - 1) * size).stream()
                  .map(
                      row ->
                          new WithdrawalDetailDTO.Order(
                              row.id().toString(),
                              row.orderNo(),
                              json.readValue(
                                  row.productsJson(),
                                  new TypeReference<List<WithdrawalDetailDTO.Product>>() {}),
                              money(row.occupied()),
                              money(row.remainingSnapshot())))
                  .toList();
          return new PageResult<>(items, mapper.countOrders(user.tenantId(), id), page, size);
        });
  }

  private WithdrawalDetailRow accessible(AccountDTO user, long id) {
    var row = mapper.find(user.tenantId(), id);
    if (row == null || !user.tenantId().equals(row.tenantId())) {
      throw new BusinessException(ErrorCode.COMMON_NOT_FOUND);
    }
    if (user.ownScope()) {
      var refs = kols.listByIdsIncludingDeleted(List.of(row.kolId()));
      if (refs.size() != 1 || !user.id().equals(refs.getFirst().bdUserId())) {
        throw new BusinessException(ErrorCode.COMMON_NOT_FOUND);
      }
    }
    return row;
  }

  private static void authorize(AccountDTO user) {
    if (user == null
        || user.role() == Role.PLATFORM_ADMIN
        || user.tenantId() == null
        || (user.role() != Role.SUPER_ADMIN
            && !user.permissions().contains("commission:view")
            && !user.permissions().contains("withdrawal:review"))) {
      throw new BusinessException(ErrorCode.AUTH_FORBIDDEN);
    }
  }

  private static String money(BigDecimal value) {
    return value == null ? null : value.setScale(2).toPlainString();
  }
}
