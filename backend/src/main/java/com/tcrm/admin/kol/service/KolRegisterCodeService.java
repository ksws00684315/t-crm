package com.tcrm.admin.kol.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.tcrm.admin.kol.mapper.KolRegisterCodeMapper;
import com.tcrm.admin.kol.model.KolRegisterCodeDO;
import com.tcrm.admin.security.api.AccountDTO;
import com.tcrm.admin.security.api.CurrentUserContext;
import com.tcrm.admin.security.api.Role;
import com.tcrm.admin.shared.BusinessException;
import com.tcrm.admin.shared.ErrorCode;
import com.tcrm.admin.system.api.BdUserApi;
import com.tcrm.admin.system.api.BdUserDTO;
import java.time.Instant;
import java.util.function.Supplier;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The signed-in BD's register link (task 12c §4.2). Only an account {@link BdUserApi} treats as a
 * usable BD may hold one; everyone else, including a super administrator, is {@code
 * KOL_INVALID_BD_USER}.
 */
@Service
public class KolRegisterCodeService {
  static final int MAX_ATTEMPTS = 3;

  private final KolRegisterCodeMapper codes;
  private final BdUserApi bdUsers;
  private final CurrentUserContext current;
  private final KolPortalUrls links;
  private final TransactionTemplate tx;

  public KolRegisterCodeService(
      KolRegisterCodeMapper codes,
      BdUserApi bdUsers,
      CurrentUserContext current,
      KolPortalUrls links,
      TransactionTemplate tx) {
    this.codes = codes;
    this.bdUsers = bdUsers;
    this.current = current;
    this.links = links;
    this.tx = tx;
  }

  /** Returns the active code, creating one when this BD does not have one yet. */
  public KolRegisterCodeDTO current() {
    AccountDTO user = requireBd();
    return withRetry(() -> tx.execute(status -> loadOrCreate(user)));
  }

  /** Revokes the active code and issues a new one in the same transaction. */
  public KolRegisterCodeDTO rotate() {
    AccountDTO user = requireBd();
    return withRetry(() -> tx.execute(status -> replace(user)));
  }

  private KolRegisterCodeDTO loadOrCreate(AccountDTO user) {
    codes.lockByBd(user.id());
    KolRegisterCodeDO active = findActive(user.id());
    return active == null ? toDto(insert(user.id(), user.id())) : toDto(active);
  }

  private KolRegisterCodeDTO replace(AccountDTO user) {
    codes.lockByBd(user.id());
    codes.revokeActive(user.id(), user.id(), Instant.now());
    return toDto(insert(user.id(), user.id()));
  }

  /**
   * Same predicate as the BD picker: enabled staff, OWN scope, {@code kol:edit}, this company. The
   * tenant plugin limits {@code getById} to the caller's company.
   */
  private AccountDTO requireBd() {
    AccountDTO user = current.user();
    if (user.role() == Role.SUPER_ADMIN && !user.ownScope()) {
      return user;
    }
    BdUserDTO bd = bdUsers.getById(user.id());
    if (bd == null || !bd.usable()) {
      throw new BusinessException(ErrorCode.KOL_INVALID_BD_USER);
    }
    return user;
  }

  private KolRegisterCodeDO findActive(long bdUserId) {
    return codes.selectOne(
        new LambdaQueryWrapper<KolRegisterCodeDO>()
            .eq(KolRegisterCodeDO::getBdUserId, bdUserId)
            .eq(KolRegisterCodeDO::getStatus, KolRegisterCodeDO.STATUS_ACTIVE));
  }

  private KolRegisterCodeDO insert(long bdUserId, long actor) {
    Instant now = Instant.now();
    KolRegisterCodeDO row = new KolRegisterCodeDO();
    row.setBdUserId(bdUserId);
    row.setCode(KolRegisterCodeGenerator.next());
    row.setStatus(KolRegisterCodeDO.STATUS_ACTIVE);
    row.setCreateBy(actor);
    row.setUpdateBy(actor);
    row.setCreateTime(now);
    row.setUpdateTime(now);
    codes.insert(row);
    return row;
  }

  private KolRegisterCodeDTO toDto(KolRegisterCodeDO row) {
    return new KolRegisterCodeDTO(row.getCode(), links.registerUrl(row.getCode()), row.getStatus());
  }

  /**
   * A lost race on the active-BD unique index aborts the transaction (the revoke rolls back with
   * it). The next attempt locks the winner's row. A code collision is the same retry.
   */
  private KolRegisterCodeDTO withRetry(Supplier<KolRegisterCodeDTO> action) {
    for (int attempt = 1; ; attempt++) {
      try {
        KolRegisterCodeDTO created = action.get();
        if (created == null) {
          throw new BusinessException(ErrorCode.COMMON_CONFLICT);
        }
        return created;
      } catch (DuplicateKeyException e) {
        if (attempt >= MAX_ATTEMPTS) {
          throw new BusinessException(ErrorCode.COMMON_CONFLICT);
        }
      }
    }
  }
}
