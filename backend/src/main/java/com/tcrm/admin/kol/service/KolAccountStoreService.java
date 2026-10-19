package com.tcrm.admin.kol.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.tcrm.admin.kol.mapper.KolMapper;
import com.tcrm.admin.kol.model.KolDO;
import com.tcrm.admin.security.api.KolAccountDTO;
import com.tcrm.admin.security.api.KolAccountStore;
import com.tcrm.admin.shared.TenantScope;
import com.tcrm.admin.system.api.TenantDirectoryApi;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/**
 * KOL account snapshots for the security module, mirroring the administrator {@code
 * AccountStoreService}. Every read runs in system scope: kol login does not know the company yet,
 * and the per-request re-read is how the company status is learned, so filtering those queries
 * would recurse through the tenant line handler.
 */
@Service
public class KolAccountStoreService implements KolAccountStore {
  private final KolMapper kols;
  private final TenantDirectoryApi tenants;

  public KolAccountStoreService(KolMapper kols, TenantDirectoryApi tenants) {
    this.kols = kols;
    this.tenants = tenants;
  }

  @Override
  public List<KolAccountDTO> listByEmail(String email) {
    return TenantScope.callSystem(
        "load KOL accounts by email",
        () ->
            kols
                .selectList(new LambdaQueryWrapper<KolDO>().apply("lower(email) = {0}", email))
                .stream()
                .map(kol -> snapshot(kol, tenantNames(List.of(kol))))
                .toList());
  }

  @Override
  public KolAccountDTO getById(Long id) {
    return TenantScope.callSystem(
        "load KOL account by id",
        () -> {
          KolDO kol = kols.selectById(id);
          return kol == null ? null : snapshot(kol, tenantNames(List.of(kol)));
        });
  }

  @Override
  public void updateLastLoginTime(Long id, Instant time) {
    TenantScope.runSystem(
        "record KOL last login time",
        () -> {
          KolDO change = new KolDO();
          change.setId(id);
          change.setLastLoginTime(time);
          kols.updateById(change);
        });
  }

  @Override
  public void activate(Long id, String hash) {
    TenantScope.runSystem(
        "activate KOL account",
        () ->
            kols.update(
                null,
                new LambdaUpdateWrapper<KolDO>()
                    .eq(KolDO::getId, id)
                    .set(KolDO::getPasswordHash, hash)
                    .set(KolDO::getAccountStatus, 1)
                    .setSql("credential_version = credential_version + 1")
                    .set(KolDO::getUpdateTime, Instant.now())));
  }

  @Override
  public void updatePassword(Long id, String hash) {
    TenantScope.runSystem(
        "update KOL password and credential version",
        () ->
            kols.update(
                null,
                new LambdaUpdateWrapper<KolDO>()
                    .eq(KolDO::getId, id)
                    .set(KolDO::getPasswordHash, hash)
                    .setSql("credential_version = credential_version + 1")
                    .set(KolDO::getUpdateTime, Instant.now())));
  }

  private Map<Long, TenantDirectoryApi.TenantRefDTO> tenantNames(List<KolDO> rows) {
    return tenants
        .listByIds(rows.stream().map(KolDO::getTenantId).collect(Collectors.toSet()))
        .stream()
        .collect(Collectors.toMap(TenantDirectoryApi.TenantRefDTO::id, t -> t));
  }

  private static KolAccountDTO snapshot(
      KolDO kol, Map<Long, TenantDirectoryApi.TenantRefDTO> tenants) {
    var tenant = tenants.get(kol.getTenantId());
    return new KolAccountDTO(
        kol.getId(),
        kol.getKolCode(),
        kol.getName(),
        kol.getEmail(),
        kol.getCountry(),
        kol.getPasswordHash(),
        kol.getStatus(),
        kol.getAccountStatus(),
        kol.getCredentialVersion() == null ? 0 : kol.getCredentialVersion(),
        kol.getLastLoginTime(),
        kol.getTenantId(),
        tenant == null ? null : tenant.name(),
        tenant != null && tenant.enabled(),
        kol.getPhone(),
        kol.getWhatsapp(),
        kol.getSheetUrl());
  }
}
