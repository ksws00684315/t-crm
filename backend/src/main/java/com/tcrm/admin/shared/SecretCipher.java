package com.tcrm.admin.shared;

import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.Objects;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** AES-256-GCM encryption for credentials persisted by backend integrations. */
@Component
public class SecretCipher {
  private static final String PREFIX = "v1:";
  private static final int KEY_BYTES = 32;
  private static final int NONCE_BYTES = 12;
  private static final int TAG_BITS = 128;

  private final byte[] key;
  private final SecureRandom random = new SecureRandom();

  public SecretCipher(@Value("${crm.weidian.secret-key:}") String encodedKey) {
    if (encodedKey == null || encodedKey.isBlank()) {
      throw new IllegalStateException("TCRM_SECRET_KEY is required and must encode 32 bytes");
    }
    try {
      this.key = Base64.getDecoder().decode(encodedKey);
    } catch (IllegalArgumentException e) {
      throw new IllegalStateException("TCRM_SECRET_KEY must be valid Base64 encoding of 32 bytes");
    }
    if (this.key.length != KEY_BYTES) {
      Arrays.fill(this.key, (byte) 0);
      throw new IllegalStateException("TCRM_SECRET_KEY must encode exactly 32 bytes");
    }
  }

  /** Encrypts plaintext as {@code v1:Base64(nonce || ciphertext || tag)}. */
  public String encrypt(String plaintext) {
    Objects.requireNonNull(plaintext, "plaintext");
    byte[] nonce = new byte[NONCE_BYTES];
    random.nextBytes(nonce);
    try {
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(
          Cipher.ENCRYPT_MODE,
          new SecretKeySpec(key, "AES"),
          new GCMParameterSpec(TAG_BITS, nonce));
      byte[] ciphertextAndTag =
          cipher.doFinal(plaintext.getBytes(java.nio.charset.StandardCharsets.UTF_8));
      byte[] packed = new byte[nonce.length + ciphertextAndTag.length];
      System.arraycopy(nonce, 0, packed, 0, nonce.length);
      System.arraycopy(ciphertextAndTag, 0, packed, nonce.length, ciphertextAndTag.length);
      return PREFIX + Base64.getEncoder().encodeToString(packed);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("Unable to encrypt credential");
    }
  }

  /** Decrypts a supported version, failing closed for unknown versions or authentication errors. */
  public String decrypt(String encrypted) {
    if (encrypted == null || !encrypted.startsWith(PREFIX)) {
      throw new IllegalArgumentException("Unsupported encrypted credential version");
    }
    final byte[] packed;
    try {
      packed = Base64.getDecoder().decode(encrypted.substring(PREFIX.length()));
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("Invalid encrypted credential");
    }
    if (packed.length <= NONCE_BYTES + TAG_BITS / Byte.SIZE) {
      throw new IllegalArgumentException("Invalid encrypted credential");
    }
    byte[] nonce = Arrays.copyOfRange(packed, 0, NONCE_BYTES);
    byte[] ciphertextAndTag = Arrays.copyOfRange(packed, NONCE_BYTES, packed.length);
    try {
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(
          Cipher.DECRYPT_MODE,
          new SecretKeySpec(key, "AES"),
          new GCMParameterSpec(TAG_BITS, nonce));
      return new String(cipher.doFinal(ciphertextAndTag), java.nio.charset.StandardCharsets.UTF_8);
    } catch (GeneralSecurityException e) {
      throw new IllegalArgumentException("Unable to decrypt credential");
    }
  }
}
