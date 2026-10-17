package com.tcrm.admin.integration.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.tcrm.admin.integration.mapper.GoogleCredentialMapper;
import com.tcrm.admin.integration.model.GoogleCredentialDO;
import com.tcrm.admin.security.api.CurrentUserContext;
import com.tcrm.admin.shared.BusinessException;
import com.tcrm.admin.shared.ErrorCode;
import com.tcrm.admin.shared.SecretCipher;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.interfaces.RSAPrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Company Google service account (ADR-021 §2): written from the JSON key file, never read back.
 * Only the account email, key ID and update time are shown.
 */
@Service
public class GoogleCredentialService {
  private static final Pattern EMAIL =
      Pattern.compile("[A-Za-z0-9._%+-]{1,64}@[A-Za-z0-9.-]{1,180}\\.iam\\.gserviceaccount\\.com");
  private static final Pattern KEY_ID = Pattern.compile("[A-Za-z0-9]{1,64}");
  private static final String PEM_BEGIN = "-----BEGIN PRIVATE KEY-----";
  private static final String PEM_END = "-----END PRIVATE KEY-----";

  private final GoogleCredentialMapper credentials;
  private final SecretCipher cipher;
  private final CurrentUserContext current;
  private final ObjectMapper json;
  private final GoogleAccessTokens tokens;
  private final String tokenUri;

  public GoogleCredentialService(
      GoogleCredentialMapper credentials,
      SecretCipher cipher,
      CurrentUserContext current,
      ObjectMapper json,
      GoogleAccessTokens tokens,
      @Value("${crm.google.token-uri}") String tokenUri) {
    this.credentials = credentials;
    this.cipher = cipher;
    this.current = current;
    this.json = json;
    this.tokens = tokens;
    this.tokenUri = tokenUri;
  }

  /** What the administrator may see; nothing secret. */
  public record View(
      boolean configured, String clientEmail, String privateKeyId, Instant updateTime) {}

  public View view() {
    GoogleCredentialDO row = find();
    return row == null
        ? new View(false, null, null, null)
        : new View(true, row.getClientEmail(), row.getPrivateKeyId(), row.getUpdateTime());
  }

  /**
   * Replaces the company's service account with the given key file content. The key must be a
   * service account key for Google's standard token endpoint with an RSA PKCS#8 private key.
   */
  public View save(String credentialJson) {
    Parsed parsed = parse(credentialJson);
    var user = current.user();
    Instant now = Instant.now();
    GoogleCredentialDO row = find();
    if (row == null) {
      row = new GoogleCredentialDO();
      row.setTenantId(user.tenantId());
      row.setCreateBy(user.id());
      row.setCreateTime(now);
      row.setDeleted(false);
    }
    row.setClientEmail(parsed.clientEmail());
    row.setPrivateKeyId(parsed.privateKeyId());
    row.setPrivateKeyCipher(cipher.encrypt(parsed.pem()));
    row.setUpdateBy(user.id());
    row.setUpdateTime(now);
    if (row.getId() == null) {
      credentials.insert(row);
    } else {
      credentials.updateById(row);
    }
    tokens.evict(user.tenantId());
    return view();
  }

  /** Decrypted account of the current company; GOOGLE_CREDENTIAL_MISSING when none is saved. */
  GoogleServiceAccount account() {
    GoogleCredentialDO row = find();
    if (row == null) {
      throw new BusinessException(ErrorCode.GOOGLE_CREDENTIAL_MISSING);
    }
    return new GoogleServiceAccount(
        row.getClientEmail(),
        row.getPrivateKeyId(),
        privateKey(cipher.decrypt(row.getPrivateKeyCipher())));
  }

  private GoogleCredentialDO find() {
    return credentials.selectOne(new LambdaQueryWrapper<GoogleCredentialDO>().last("LIMIT 1"));
  }

  private Parsed parse(String credentialJson) {
    JsonNode root;
    try {
      root = json.readTree(credentialJson == null ? "" : credentialJson);
    } catch (JacksonException e) {
      throw new BusinessException(ErrorCode.GOOGLE_CREDENTIAL_INVALID);
    }
    String type = text(root, "type");
    String email = text(root, "client_email");
    String keyId = text(root, "private_key_id");
    String pem = text(root, "private_key");
    String uri = text(root, "token_uri");
    if (!"service_account".equals(type)
        || email == null
        || !EMAIL.matcher(email).matches()
        || keyId == null
        || !KEY_ID.matcher(keyId).matches()
        || pem == null
        || (uri != null && !uri.equals(tokenUri))) {
      throw new BusinessException(ErrorCode.GOOGLE_CREDENTIAL_INVALID);
    }
    privateKey(pem);
    return new Parsed(email, keyId, pem);
  }

  /** Parses an RSA PKCS#8 PEM key; anything else is GOOGLE_CREDENTIAL_INVALID. */
  static PrivateKey privateKey(String pem) {
    String body = pem.strip();
    if (!body.startsWith(PEM_BEGIN) || !body.endsWith(PEM_END)) {
      throw new BusinessException(ErrorCode.GOOGLE_CREDENTIAL_INVALID);
    }
    body = body.substring(PEM_BEGIN.length(), body.length() - PEM_END.length());
    try {
      byte[] der = Base64.getMimeDecoder().decode(body);
      PrivateKey key = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
      if (!(key instanceof RSAPrivateKey rsa) || rsa.getModulus().bitLength() < 2048) {
        throw new BusinessException(ErrorCode.GOOGLE_CREDENTIAL_INVALID);
      }
      return key;
    } catch (IllegalArgumentException | GeneralSecurityException e) {
      throw new BusinessException(ErrorCode.GOOGLE_CREDENTIAL_INVALID);
    }
  }

  private static String text(JsonNode root, String field) {
    JsonNode node = root.get(field);
    return node == null || !node.isString() ? null : node.asString().strip();
  }

  private record Parsed(String clientEmail, String privateKeyId, String pem) {
    @Override
    public String toString() {
      return "Parsed[clientEmail=%s, pem=redacted]".formatted(clientEmail);
    }
  }
}
