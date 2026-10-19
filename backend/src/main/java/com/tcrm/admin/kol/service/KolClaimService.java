package com.tcrm.admin.kol.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.tcrm.admin.kol.mapper.KolMapper;
import com.tcrm.admin.kol.model.KolDO;
import com.tcrm.admin.kol.model.RelationStatus;
import com.tcrm.admin.security.api.AccountDTO;
import com.tcrm.admin.security.api.CurrentUserContext;
import com.tcrm.admin.shared.BusinessException;
import com.tcrm.admin.shared.ErrorCode;
import com.tcrm.admin.system.api.BdUserApi;
import com.tcrm.admin.system.api.BdUserDTO;
import java.util.Locale;
import org.springframework.stereotype.Service;

/** Exact search for claimable KOLs and claim entry (task 25a / 29). */
@Service
public class KolClaimService {
  private final KolMapper kols;
  private final BdUserApi bdUsers;
  private final BdTransferService transfers;
  private final CurrentUserContext current;

  public KolClaimService(
      KolMapper kols, BdUserApi bdUsers, BdTransferService transfers, CurrentUserContext current) {
    this.kols = kols;
    this.bdUsers = bdUsers;
    this.transfers = transfers;
    this.current = current;
  }

  /**
   * Exact match by KOL code or email (case-insensitive). Returns null when not found or already
   * bound to a usable BD — callers must not distinguish the two.
   */
  public ClaimableKolDTO findClaimable(String keyword) {
    requireUsableBd();
    String key = keyword == null ? "" : keyword.strip();
    if (key.isEmpty()) {
      return null;
    }
    KolDO kol =
        kols.selectOne(
            new LambdaQueryWrapper<KolDO>()
                .and(
                    w ->
                        w.apply("lower(kol_code) = {0}", key.toLowerCase(Locale.ROOT))
                            .or()
                            .apply("lower(email) = {0}", key.toLowerCase(Locale.ROOT)))
                .last("LIMIT 1"));
    if (kol == null || !transfers.isClaimable(kol)) {
      return null;
    }
    String relation =
        kol.getRelationStatus() == null
            ? RelationStatus.PENDING_PROMOTION.name()
            : kol.getRelationStatus();
    BdUserDTO bd = kol.getBdUserId() == null ? null : bdUsers.getById(kol.getBdUserId());
    return new ClaimableKolDTO(
        kol.getId(),
        kol.getKolCode(),
        kol.getName(),
        maskEmail(kol.getEmail()),
        KolPromotionSupport.displayRelationStatus(relation, bd));
  }

  public void claim(Long kolId) {
    requireUsableBd();
    transfers.claim(kolId);
  }

  private void requireUsableBd() {
    AccountDTO user = current.user();
    BdUserDTO self = bdUsers.getById(user.id());
    if (self == null || !self.usable()) {
      throw new BusinessException(ErrorCode.AUTH_FORBIDDEN);
    }
  }

  /** First letter + ***@domain (ASCII-safe for the local part). */
  static String maskEmail(String email) {
    if (email == null || email.isBlank() || !email.contains("@")) {
      return "***";
    }
    String local = email.substring(0, email.indexOf('@'));
    String domain = email.substring(email.indexOf('@') + 1);
    String head = local.isEmpty() ? "*" : local.substring(0, 1);
    return head + "***@" + domain;
  }

  public record ClaimableKolDTO(
      Long id, String kolCode, String name, String maskedEmail, String displayRelationStatus) {}
}
