package com.tcrm.admin.product.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.tcrm.admin.product.api.PromoSheetClassification;
import com.tcrm.admin.product.api.PromoSheetClassification.ConflictItem;
import com.tcrm.admin.product.api.PromoSheetConfirmResult;
import com.tcrm.admin.product.service.PromoSheetService;
import com.tcrm.admin.shared.AuditLog;
import com.tcrm.admin.shared.R;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Promo-sheet upload (task 26, ADR-024): one KOL, one .xlsx of at most 300 MB, preview before the
 * write. Permission {@code kol:edit}; an OWN-scope account may only upload for its own KOLs (the
 * service enforces it).
 */
@RestController
@RequestMapping("/admin-api/kol/kols/{id}/promo-sheet-uploads")
@SaCheckPermission("kol:edit")
public class PromoSheetUploadController {
  private final PromoSheetService sheets;

  public PromoSheetUploadController(PromoSheetService sheets) {
    this.sheets = sheets;
  }

  /** Parses the workbook and returns the preview; the file itself is not kept. */
  @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  @AuditLog(module = "KOL", action = "Upload promo sheet")
  public R<PromoSheetPreviewVO> preview(
      @PathVariable @Min(1) Long id, @RequestParam("file") MultipartFile file) {
    PromoSheetService.PreviewOutcome outcome = sheets.preview(id, file);
    return R.ok(PromoSheetPreviewVO.of(outcome));
  }

  /** Writes the additions after re-validation; each upload confirms once (expired is 410). */
  @PostMapping("/{uploadId}/confirm")
  public R<PromoSheetConfirmVO> confirm(
      @PathVariable @Min(1) Long id, @PathVariable @NotBlank String uploadId) {
    PromoSheetService.ConfirmOutcome outcome = sheets.confirm(id, uploadId);
    return R.ok(PromoSheetConfirmVO.of(outcome));
  }

  /** Preview response (task 26 §3.3). */
  public record PromoSheetPreviewVO(
      String uploadId,
      String fileName,
      int linkRows,
      int duplicates,
      int toAdd,
      int existing,
      int conflicts,
      int notInSheet,
      Map<String, Integer> skipCounts,
      List<SkipSampleVO> skipSamples,
      List<NewItemVO> newItems,
      List<ConflictVO> conflictsDetail) {

    static PromoSheetPreviewVO of(PromoSheetService.PreviewOutcome outcome) {
      PromoSheetClassification c = outcome.classification();
      Map<String, Integer> skipCounts = new LinkedHashMap<>();
      c.skipCounts().forEach((reason, count) -> skipCounts.put(reason.name(), count));
      return new PromoSheetPreviewVO(
          outcome.uploadId(),
          outcome.fileName(),
          c.linkRows(),
          c.duplicates(),
          c.toAdd(),
          c.existing(),
          c.conflicts(),
          c.notInSheet(),
          skipCounts,
          c.skipSamples().stream()
              .map(sample -> new SkipSampleVO(sample.sheet(), sample.row(), sample.reason().name()))
              .toList(),
          c.newItems().stream()
              .map(
                  item ->
                      new NewItemVO(
                          item.shopName(),
                          item.shopWeidianId(),
                          item.weidianItemId(),
                          item.itemName(),
                          item.productCode()))
              .toList(),
          c.conflictItems().stream()
              .map(item -> ConflictVO.of(item, outcome.conflictOwnerVisible()))
              .toList());
    }
  }

  /** Confirmation response (task 26 §3.3). */
  public record PromoSheetConfirmVO(
      String fileName,
      int inserted,
      int existing,
      int conflicts,
      int duplicates,
      int notInSheet,
      Map<String, Integer> skipCounts,
      List<ConflictVO> conflictsDetail) {

    static PromoSheetConfirmVO of(PromoSheetService.ConfirmOutcome outcome) {
      PromoSheetConfirmResult r = outcome.result();
      Map<String, Integer> skipCounts = new LinkedHashMap<>();
      r.skipCounts().forEach((reason, count) -> skipCounts.put(reason.name(), count));
      return new PromoSheetConfirmVO(
          outcome.fileName(),
          r.inserted(),
          r.existing(),
          r.conflicts(),
          r.duplicates(),
          r.notInSheet(),
          skipCounts,
          r.conflictItems().stream()
              .map(item -> ConflictVO.of(item, outcome.conflictOwnerVisible()))
              .toList());
    }
  }

  /** One skipped row with its location (sheet, row) and reason code. */
  public record SkipSampleVO(String sheet, int row, String reason) {}

  /** A mapping that confirmation would create. */
  public record NewItemVO(
      String shopName,
      String shopWeidianId,
      String weidianItemId,
      String itemName,
      String productCode) {}

  /** A conflicting item; owner fields are null unless the viewer is a super administrator. */
  public record ConflictVO(
      String shopWeidianId,
      String weidianItemId,
      String itemName,
      String ownerKolCode,
      String ownerKolName) {

    static ConflictVO of(ConflictItem item, boolean ownerVisible) {
      return new ConflictVO(
          item.shopWeidianId(),
          item.weidianItemId(),
          item.itemName(),
          ownerVisible ? item.ownerKolCode() : null,
          ownerVisible ? item.ownerKolName() : null);
    }
  }
}
