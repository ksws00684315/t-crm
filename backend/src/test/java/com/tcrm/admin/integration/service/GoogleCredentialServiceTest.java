package com.tcrm.admin.integration.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tcrm.admin.integration.mapper.GoogleCredentialMapper;
import com.tcrm.admin.integration.model.GoogleCredentialDO;
import com.tcrm.admin.security.api.AccountDTO;
import com.tcrm.admin.security.api.CurrentUserContext;
import com.tcrm.admin.security.api.DataScope;
import com.tcrm.admin.security.api.Role;
import com.tcrm.admin.shared.BusinessException;
import com.tcrm.admin.shared.ErrorCode;
import com.tcrm.admin.shared.SecretCipher;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;

class GoogleCredentialServiceTest {
  private static final String TOKEN_URI = "https://oauth2.googleapis.com/token";
  private final GoogleCredentialMapper mapper = mock(GoogleCredentialMapper.class);
  private final SecretCipher cipher =
      new SecretCipher(Base64.getEncoder().encodeToString(new byte[32]));
  private final CurrentUserContext current = mock(CurrentUserContext.class);
  private final GoogleAccessTokens tokens = mock(GoogleAccessTokens.class);
  private GoogleCredentialService service;

  @BeforeEach
  void setUp() {
    service =
        new GoogleCredentialService(mapper, cipher, current, new ObjectMapper(), tokens, TOKEN_URI);
    when(current.user())
        .thenReturn(
            new AccountDTO(
                9L,
                "admin",
                null,
                "Admin",
                null,
                Role.SUPER_ADMIN,
                DataScope.ALL,
                1,
                null,
                null,
                0,
                7L,
                "Co",
                true,
                Set.of(),
                null));
  }

  @Test
  void shouldStoreOnlyCipherText_andShowTheEmail() {
    service.save(GoogleTestKeys.json(TOKEN_URI));

    var saved = ArgumentCaptor.forClass(GoogleCredentialDO.class);
    verify(mapper).insert(saved.capture());
    GoogleCredentialDO row = saved.getValue();
    assertThat(row.getTenantId()).isEqualTo(7L);
    assertThat(row.getClientEmail()).isEqualTo(GoogleTestKeys.EMAIL);
    assertThat(row.getPrivateKeyId()).isEqualTo(GoogleTestKeys.KEY_ID);
    assertThat(row.getPrivateKeyCipher()).startsWith("v1:").doesNotContain("PRIVATE KEY");
    assertThat(cipher.decrypt(row.getPrivateKeyCipher()))
        .isEqualTo(GoogleTestKeys.pem(GoogleTestKeys.pair()).strip());
    verify(tokens).evict(7L);
  }

  @Test
  void shouldDecryptTheAccount_forSigning() {
    var row = new GoogleCredentialDO();
    row.setClientEmail(GoogleTestKeys.EMAIL);
    row.setPrivateKeyId(GoogleTestKeys.KEY_ID);
    row.setPrivateKeyCipher(cipher.encrypt(GoogleTestKeys.pem(GoogleTestKeys.pair())));
    when(mapper.selectOne(any())).thenReturn(row);

    GoogleServiceAccount account = service.account();

    assertThat(account.privateKey().getEncoded())
        .isEqualTo(GoogleTestKeys.pair().getPrivate().getEncoded());
    assertThat(account.toString()).doesNotContain("PRIVATE").contains("redacted");
    assertThat(service.view().configured()).isTrue();
  }

  @Test
  void shouldReportMissing_whenNoAccountIsSaved() {
    assertThat(service.view().configured()).isFalse();
    assertThatThrownBy(service::account)
        .extracting(e -> ((BusinessException) e).getErrorCode())
        .isEqualTo(ErrorCode.GOOGLE_CREDENTIAL_MISSING);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "not json",
        "{\"type\":\"authorized_user\"}",
        "{\"type\":\"service_account\",\"client_email\":\"a@gmail.com\"}",
      })
  void shouldRejectFilesThatAreNotServiceAccountKeys(String content) {
    assertInvalid(content);
  }

  @Test
  void shouldRejectAKeyForAnotherTokenEndpoint() {
    assertInvalid(GoogleTestKeys.json("https://evil.test/token"));
  }

  @Test
  void shouldRejectAMalformedOrShortKey() throws Exception {
    assertInvalid(
        GoogleTestKeys.json(TOKEN_URI)
            .replaceFirst(
                "-----BEGIN PRIVATE KEY-----\\\\n[^\\\\]+",
                "-----BEGIN PRIVATE KEY-----\\\\nAAAA"));
    var shortRsa = GoogleTestKeys.generate(1024);
    assertInvalid(
        GoogleTestKeys.json(TOKEN_URI)
            .replace(
                GoogleTestKeys.pem(GoogleTestKeys.pair()).replace("\n", "\\n"),
                GoogleTestKeys.pem(shortRsa).replace("\n", "\\n")));
    var ec = KeyPairGenerator.getInstance("EC").generateKeyPair();
    String ecPem =
        "-----BEGIN PRIVATE KEY-----\\n"
            + Base64.getEncoder().encodeToString(ec.getPrivate().getEncoded())
            + "\\n-----END PRIVATE KEY-----\\n";
    assertInvalid(
        GoogleTestKeys.json(TOKEN_URI)
            .replace(GoogleTestKeys.pem(GoogleTestKeys.pair()).replace("\n", "\\n"), ecPem));
  }

  private void assertInvalid(String content) {
    assertThatThrownBy(() -> service.save(content))
        .isInstanceOf(BusinessException.class)
        .extracting(e -> ((BusinessException) e).getErrorCode())
        .isEqualTo(ErrorCode.GOOGLE_CREDENTIAL_INVALID);
    verify(mapper, never()).insert(any(GoogleCredentialDO.class));
  }
}
