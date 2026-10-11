package com.tcrm.admin.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.SecureRandom;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class SecretCipherTest {
  private static final byte[] KEY = randomKey();

  @Test
  void shouldEncryptAndDecrypt_whenUsingValidKey() {
    SecretCipher cipher = new SecretCipher(Base64.getEncoder().encodeToString(KEY));

    String encrypted = cipher.encrypt("private-token");

    assertThat(encrypted).startsWith("v1:").doesNotContain("private-token");
    assertThat(cipher.decrypt(encrypted)).isEqualTo("private-token");
  }

  @Test
  void shouldUseDifferentNonce_whenEncryptingSamePlaintextTwice() {
    SecretCipher cipher = new SecretCipher(Base64.getEncoder().encodeToString(KEY));

    assertThat(cipher.encrypt("same-value")).isNotEqualTo(cipher.encrypt("same-value"));
  }

  @Test
  void shouldRejectModifiedCiphertext_whenAuthenticationFails() {
    SecretCipher cipher = new SecretCipher(Base64.getEncoder().encodeToString(KEY));
    String encrypted = cipher.encrypt("private-token");
    char replacement = encrypted.charAt(encrypted.length() - 1) == 'A' ? 'B' : 'A';
    String modified = encrypted.substring(0, encrypted.length() - 1) + replacement;

    assertThatThrownBy(() -> cipher.decrypt(modified)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void shouldRejectCiphertextFromAnotherKey_whenAuthenticationFails() {
    SecretCipher first = new SecretCipher(Base64.getEncoder().encodeToString(KEY));
    SecretCipher second = new SecretCipher(Base64.getEncoder().encodeToString(randomKey()));

    assertThatThrownBy(() -> second.decrypt(first.encrypt("private-token")))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void shouldRejectUnknownVersion_whenDecrypting() {
    SecretCipher cipher = new SecretCipher(Base64.getEncoder().encodeToString(KEY));

    assertThatThrownBy(() -> cipher.decrypt("v2:YWJj"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void shouldNameRequiredEnvironmentVariable_whenKeyIsMissingOrInvalid() {
    assertThatThrownBy(() -> new SecretCipher(null))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("TCRM_SECRET_KEY");
    assertThatThrownBy(() -> new SecretCipher(Base64.getEncoder().encodeToString(new byte[31])))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("TCRM_SECRET_KEY");
  }

  private static byte[] randomKey() {
    byte[] key = new byte[32];
    new SecureRandom().nextBytes(key);
    return key;
  }
}
