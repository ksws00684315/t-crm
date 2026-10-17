package com.tcrm.admin.integration.service;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

/** Throw-away RSA keys and service account JSON for tests; never a real Google credential. */
public final class GoogleTestKeys {
  public static final String EMAIL = "sheet-reader@tcrm-test.iam.gserviceaccount.com";
  public static final String KEY_ID = "0123456789abcdef0123456789abcdef01234567";
  private static final KeyPair PAIR = generate(2048);

  private GoogleTestKeys() {}

  public static KeyPair pair() {
    return PAIR;
  }

  public static KeyPair generate(int bits) {
    try {
      var generator = KeyPairGenerator.getInstance("RSA");
      generator.initialize(bits);
      return generator.generateKeyPair();
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  public static String pem(KeyPair pair) {
    String body =
        Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(pair.getPrivate().getEncoded());
    return "-----BEGIN PRIVATE KEY-----\n" + body + "\n-----END PRIVATE KEY-----\n";
  }

  /** JSON in the shape of a downloaded service account key file. */
  public static String json(String tokenUri) {
    return """
        {"type":"service_account","project_id":"tcrm-test","private_key_id":"%s",
         "private_key":"%s","client_email":"%s","client_id":"1","token_uri":"%s"}
        """
        .formatted(KEY_ID, pem(PAIR).replace("\n", "\\n"), EMAIL, tokenUri);
  }
}
