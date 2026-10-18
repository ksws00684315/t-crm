package com.tcrm.admin.product.service;

import com.tcrm.admin.kol.api.KolApi;
import com.tcrm.admin.kol.api.KolRefDTO;
import com.tcrm.admin.product.api.PromoSheetClassification;
import com.tcrm.admin.product.api.PromoSheetConfirmResult;
import com.tcrm.admin.product.api.PromoSheetItemApi;
import com.tcrm.admin.product.api.PromoSheetRowDTO;
import com.tcrm.admin.security.api.AccountDTO;
import com.tcrm.admin.security.api.CurrentUserContext;
import com.tcrm.admin.security.api.Role;
import com.tcrm.admin.shared.AuditSanitizer;
import com.tcrm.admin.shared.BusinessException;
import com.tcrm.admin.shared.ErrorCode;
import com.tcrm.admin.system.api.ExternalAuditApi;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/**
 * Promo-sheet upload flow (task 26, ADR-024): upload to a temporary file, parse it there, keep only
 * the parsed rows in Redis for 30 minutes, then confirm with a re-validated write. The file is
 * deleted in every path and never stored; the KOL's status is never touched (ADR-023).
 */
@Service
public class PromoSheetService {
  /** Upload size limit of this endpoint only; every other upload keeps its own business cap. */
  public static final long MAX_BYTES = 300L * 1024 * 1024;

  static final Duration PREVIEW_TTL = Duration.ofMinutes(30);
  private static final String KEY_PREFIX = "kol:promo-sheet:";
  private static final Logger LOG = LoggerFactory.getLogger(PromoSheetService.class);
  private static final SecureRandom RANDOM = new SecureRandom();
  private static final char[] ALPHABET =
      "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz".toCharArray();

  private final CurrentUserContext current;
  private final KolApi kols;
  private final PromoSheetReader reader;
  private final PromoSheetItemApi mappings;
  private final StringRedisTemplate redis;
  private final ExternalAuditApi audit;
  private final AuditSanitizer sanitizer;

  public PromoSheetService(
      CurrentUserContext current,
      KolApi kols,
      PromoSheetReader reader,
      PromoSheetItemApi mappings,
      StringRedisTemplate redis,
      ExternalAuditApi audit,
      AuditSanitizer sanitizer) {
    this.current = current;
    this.kols = kols;
    this.reader = reader;
    this.mappings = mappings;
    this.redis = redis;
    this.audit = audit;
    this.sanitizer = sanitizer;
  }

  /** Parses the upload and stores the preview under a random id; nothing is written yet. */
  public PreviewOutcome preview(long kolId, MultipartFile file) {
    KolRefDTO kol = requireKol(kolId);
    String fileName = file.getOriginalFilename();
    if (fileName == null || !fileName.toLowerCase(Locale.ROOT).endsWith(".xlsx")) {
      throw new BusinessException(ErrorCode.KOL_PROMO_SHEET_INVALID);
    }
    if (file.getSize() > MAX_BYTES) {
      throw new BusinessException(ErrorCode.KOL_PROMO_SHEET_TOO_LARGE);
    }
    List<PromoSheetRowDTO> rows = parse(file);
    PromoSheetClassification classification = mappings.classify(kolId, rows);
    String uploadId = uploadId();
    AccountDTO user = current.user();
    PromoSheetUploadPayload payload =
        new PromoSheetUploadPayload(user.tenantId(), kolId, user.id(), fileName, rows);
    redis.opsForValue().set(KEY_PREFIX + uploadId, payload.serialize(), PREVIEW_TTL);
    return new PreviewOutcome(uploadId, fileName, classification, user.role() == Role.SUPER_ADMIN);
  }

  /** Re-validates and writes the additions of a stored preview; one confirmation per upload. */
  public ConfirmOutcome confirm(long kolId, String uploadId) {
    KolRefDTO kol = requireKol(kolId);
    String raw = redis.opsForValue().get(KEY_PREFIX + uploadId);
    if (raw == null) {
      throw new BusinessException(ErrorCode.KOL_PROMO_UPLOAD_EXPIRED);
    }
    PromoSheetUploadPayload payload = PromoSheetUploadPayload.deserialize(raw);
    AccountDTO user = current.user();
    if (!user.id().equals(payload.uploaderId())
        || !user.tenantId().equals(payload.tenantId())
        || kolId != payload.kolId()) {
      throw new BusinessException(ErrorCode.AUTH_FORBIDDEN);
    }
    PromoSheetConfirmResult result = mappings.confirm(kolId, payload.rows());
    redis.delete(KEY_PREFIX + uploadId);
    logConfirmation(kol, payload.fileName(), result);
    return new ConfirmOutcome(payload.fileName(), result, user.role() == Role.SUPER_ADMIN);
  }

  /** Streams the upload to a temporary file, parses it there and deletes it in every path. */
  private List<PromoSheetRowDTO> parse(MultipartFile file) {
    Path temp = null;
    try {
      temp = Files.createTempFile("tcrm-promo-sheet-", ".xlsx");
      file.transferTo(temp.toFile());
      return reader.read(temp);
    } catch (IOException | IllegalStateException e) {
      throw new BusinessException(ErrorCode.KOL_PROMO_SHEET_INVALID);
    } finally {
      if (temp != null) {
        try {
          Files.deleteIfExists(temp);
        } catch (IOException e) {
          LOG.warn("Promo-sheet temporary file was not deleted: {}", temp);
        }
      }
    }
  }

  /** The KOL must exist in the caller's company and, for OWN scope, belong to the caller. */
  private KolRefDTO requireKol(long kolId) {
    KolRefDTO kol = kols.listByIds(List.of(kolId)).stream().findFirst().orElse(null);
    if (kol == null) {
      throw new BusinessException(ErrorCode.COMMON_NOT_FOUND);
    }
    AccountDTO user = current.user();
    if (user.ownScope() && !user.id().equals(kol.bdUserId())) {
      throw new BusinessException(ErrorCode.AUTH_FORBIDDEN);
    }
    return kol;
  }

  /** One operation log row with the KOL, the file name and the outcome counts (task 26 §3.3). */
  private void logConfirmation(KolRefDTO kol, String fileName, PromoSheetConfirmResult result) {
    Map<String, Object> params = new LinkedHashMap<>();
    params.put("kolCode", kol.kolCode());
    params.put("kolName", kol.name());
    params.put("fileName", fileName);
    params.put("inserted", result.inserted());
    params.put("existing", result.existing());
    params.put("conflicts", result.conflicts());
    params.put("duplicates", result.duplicates());
    params.put("notInSheet", result.notInSheet());
    params.put("skipped", result.skipCounts());
    var user = current.user();
    audit.recordTenantWriteOperation(
        user.tenantId(),
        user.id(),
        "KOL",
        "Confirm promo sheet",
        "POST",
        "/admin-api/kol/kols/" + kol.id() + "/promo-sheet-uploads",
        sanitizer.sanitize(params));
  }

  private static String uploadId() {
    char[] id = new char[32];
    for (int i = 0; i < id.length; i++) {
      id[i] = ALPHABET[RANDOM.nextInt(ALPHABET.length)];
    }
    return new String(id);
  }

  /** Preview outcome; the owner of a conflicting item is for super admins only. */
  public record PreviewOutcome(
      String uploadId,
      String fileName,
      PromoSheetClassification classification,
      boolean conflictOwnerVisible) {}

  /** Confirmation outcome; the owner of a conflicting item is for super admins only. */
  public record ConfirmOutcome(
      String fileName, PromoSheetConfirmResult result, boolean conflictOwnerVisible) {}
}
