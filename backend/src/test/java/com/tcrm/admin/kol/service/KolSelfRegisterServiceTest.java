package com.tcrm.admin.kol.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.tcrm.admin.kol.mapper.KolMapper;
import com.tcrm.admin.kol.mapper.KolRegisterCodeMapper;
import com.tcrm.admin.kol.model.KolDO;
import com.tcrm.admin.kol.model.KolRegisterCodeDO;
import com.tcrm.admin.kol.service.KolAuthService.LoginResult;
import com.tcrm.admin.security.api.KolAccountDTO;
import com.tcrm.admin.shared.BusinessException;
import com.tcrm.admin.shared.ErrorCode;
import com.tcrm.admin.system.api.BdUserApi;
import com.tcrm.admin.system.api.BdUserDTO;
import com.tcrm.admin.system.api.ExternalAuditApi;
import com.tcrm.admin.system.api.TenantDirectoryApi;
import com.tcrm.admin.system.api.TenantDirectoryApi.TenantRefDTO;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

class KolSelfRegisterServiceTest {
  private static final String CODE = "A".repeat(32);
  private static final String IP = "198.51.100.9";

  private final KolRegisterCodeMapper codes = mock(KolRegisterCodeMapper.class);
  private final BdUserApi bdUsers = mock(BdUserApi.class);
  private final TenantDirectoryApi tenants = mock(TenantDirectoryApi.class);
  private final KolMapper kols = mock(KolMapper.class);
  private final KolWriter writer = mock(KolWriter.class);
  private final BCryptPasswordEncoder passwords = mock(BCryptPasswordEncoder.class);
  private final KolEmailCodes emailCodes = mock(KolEmailCodes.class);
  private final KolMails mails = mock(KolMails.class);
  private final ExternalAuditApi audit = mock(ExternalAuditApi.class);
  private final KolAuthService auth = mock(KolAuthService.class);
  private final KolSelfRegisterService service =
      new KolSelfRegisterService(
          codes, bdUsers, tenants, kols, writer, passwords, emailCodes, mails, audit, auth);

  @BeforeEach
  void setUp() {
    KolRegisterCodeDO row = new KolRegisterCodeDO();
    row.setTenantId(7L);
    row.setBdUserId(3L);
    row.setCode(CODE);
    row.setStatus(KolRegisterCodeDO.STATUS_ACTIVE);
    when(codes.selectOne(any())).thenReturn(row);
    when(bdUsers.getById(3L)).thenReturn(new BdUserDTO(3L, "bd", "BD", true));
    when(tenants.listByIds(List.of(7L))).thenReturn(List.of(new TenantRefDTO(7L, "Acme", true)));
    when(kols.selectCount(any())).thenReturn(0L);
    when(passwords.encode(anyString())).thenReturn("bcrypt-hash");
    when(emailCodes.issue(7L, "a@b.co")).thenReturn("123456");
    when(writer.insert(any(), isNull(), isNull()))
        .thenAnswer(
            invocation -> {
              KolDO kol = invocation.getArgument(0);
              kol.setId(42L);
              return kol;
            });
    when(auth.loginEstablished(42L))
        .thenReturn(
            new LoginResult(
                "X-Kol-Token",
                "token",
                60,
                new KolAccountDTO(
                    42L, "KOL0001", "handle", "a@b.co", "US", "hash", 1, 1, 0, null, 7L, "Acme",
                    true)));
  }

  @Test
  void shouldPreviewOnlyAUsableLink() {
    assertThat(service.preview(CODE).tenantName()).isEqualTo("Acme");
  }

  @Test
  void shouldHideEveryReasonALinkIsUnusable() {
    assertThatThrownBy(() -> service.preview("short"))
        .isInstanceOf(BusinessException.class)
        .hasMessage(ErrorCode.KOL_REGISTER_CODE_INVALID.message());
    verify(codes, never()).selectOne(any());

    when(codes.selectOne(any())).thenReturn(revoked());
    assertInvalid(service);

    KolRegisterCodeDO active = active();
    when(codes.selectOne(any())).thenReturn(active);
    when(bdUsers.getById(3L)).thenReturn(new BdUserDTO(3L, "bd", "BD", false));
    assertInvalid(service);

    when(bdUsers.getById(3L)).thenReturn(new BdUserDTO(3L, "bd", "BD", true));
    when(tenants.listByIds(List.of(7L))).thenReturn(List.of(new TenantRefDTO(7L, "Acme", false)));
    assertInvalid(service);

    when(tenants.listByIds(List.of(7L))).thenReturn(List.of());
    assertInvalid(service);
  }

  @Test
  void shouldNotSendOrReserve_whenTheEmailAlreadyBelongsToTheCompany() {
    when(kols.selectCount(any())).thenReturn(1L);
    assertThatThrownBy(() -> service.sendEmailCode(CODE, " A@B.CO ", IP))
        .isInstanceOf(BusinessException.class)
        .hasMessage(ErrorCode.KOL_EMAIL_EXISTS.message());
    verify(emailCodes).requireSendIp(IP);
    verify(emailCodes, never()).reserveSend(anyLong(), anyString());
    verify(emailCodes, never()).issue(anyLong(), anyString());
    verifyNoInteractions(mails);
  }

  @Test
  void shouldReserveThenStoreThenSend() {
    service.sendEmailCode(CODE, " A@B.CO ", IP);
    InOrder order = inOrder(emailCodes, mails);
    order.verify(emailCodes).requireSendIp(IP);
    order.verify(emailCodes).reserveSend(7L, "a@b.co");
    order.verify(emailCodes).issue(7L, "a@b.co");
    order.verify(mails).sendRegisterCode("a@b.co", "Acme", "123456");
  }

  @Test
  void shouldNotConsumeTheCode_whenTheEmailIsTaken() {
    when(kols.selectCount(any())).thenReturn(1L);
    assertThatThrownBy(() -> service.register(registration("  "), IP))
        .hasMessage(ErrorCode.KOL_EMAIL_EXISTS.message());
    verify(emailCodes, never()).consume(anyLong(), anyString(), anyString());
    verifyNoInteractions(writer, audit, auth);
  }

  @Test
  void shouldCreateAnActivatedKol_andSignItIn() {
    LoginResult result = service.register(registration(null), IP);
    assertThat(result.token()).isEqualTo("token");
    InOrder order = inOrder(emailCodes, writer, audit, auth);
    order.verify(emailCodes).requireRegisterIp(IP);
    order.verify(emailCodes).requireRegisterEmail("a@b.co");
    order.verify(emailCodes).consume(7L, "a@b.co", "123456");
    order.verify(writer).insert(any(KolDO.class), isNull(), isNull());
    order
        .verify(audit)
        .recordKolOperation(
            7L,
            "a@b.co",
            "KolRegister",
            "Self register",
            "/kol-api/register",
            IP,
            "{\"kolId\":\"42\"}");
    order.verify(auth).loginEstablished(42L);
    verify(writer)
        .insert(
            org.mockito.ArgumentMatchers.argThat(
                kol ->
                    kol.getName().equals("creator_handle")
                        && kol.getEmail().equals("a@b.co")
                        && "US".equals(kol.getCountry())
                        && kol.getBdUserId().equals(3L)
                        && kol.getStatus().equals(1)
                        && kol.getRegisterSource().equals(2)
                        && kol.getAccountStatus().equals(1)
                        && "bcrypt-hash".equals(kol.getPasswordHash())
                        && kol.getCredentialVersion().equals(0)
                        && kol.getWhatsapp().equals("+15551234567")
                        && kol.getSocialAccounts().contains("\"primary\":true")
                        && kol.getSocialAccounts().contains("TikTok")),
            isNull(),
            isNull());
  }

  private static void assertInvalid(KolSelfRegisterService service) {
    assertThatThrownBy(() -> service.preview(CODE))
        .isInstanceOf(BusinessException.class)
        .hasMessage(ErrorCode.KOL_REGISTER_CODE_INVALID.message());
  }

  private static KolRegisterCodeDO active() {
    KolRegisterCodeDO row = new KolRegisterCodeDO();
    row.setTenantId(7L);
    row.setBdUserId(3L);
    row.setCode(CODE);
    row.setStatus(KolRegisterCodeDO.STATUS_ACTIVE);
    return row;
  }

  private static KolRegisterCodeDO revoked() {
    KolRegisterCodeDO row = active();
    row.setStatus(KolRegisterCodeDO.STATUS_REVOKED);
    return row;
  }

  private static KolRegistration registration(String name) {
    return new KolRegistration(
        CODE,
        " A@B.CO ",
        "123456",
        "register-pass-1",
        " +15551234567 ",
        "TikTok",
        " creator_handle ",
        "  ",
        name,
        " us ");
  }
}
