package com.tcrm.admin.kol.controller;

import cn.dev33.satoken.annotation.SaCheckRole;
import cn.dev33.satoken.annotation.SaMode;
import com.tcrm.admin.kol.service.KolImportService;
import com.tcrm.admin.shared.AppLocale;
import com.tcrm.admin.shared.AuditLog;
import com.tcrm.admin.shared.BusinessException;
import com.tcrm.admin.shared.ErrorCode;
import com.tcrm.admin.shared.R;
import com.tcrm.admin.task.api.ImportFileDTO;
import com.tcrm.admin.task.api.ImportTaskApi;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.context.MessageSource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** KOL Excel import (task 04 §4.5): template download and asynchronous upload. */
@RestController
@RequestMapping("/admin-api/kol/kol-imports")
@SaCheckRole(
    value = {"SUPER_ADMIN", "OPERATOR"},
    mode = SaMode.OR)
public class KolImportController {
  static final String TEMPLATE_NAME_KEY = "import.kol.template-name";
  private final KolImportService imports;
  private final MessageSource messages;

  public KolImportController(KolImportService imports, MessageSource messages) {
    this.imports = imports;
    this.messages = messages;
  }

  /**
   * Header-only workbook in the request language; the file name follows it. Not a JSON endpoint.
   */
  @GetMapping("/template")
  public ResponseEntity<byte[]> template() {
    return ResponseEntity.ok()
        .header(
            HttpHeaders.CONTENT_DISPOSITION,
            ContentDisposition.attachment()
                .filename(
                    messages.getMessage(TEMPLATE_NAME_KEY, null, AppLocale.current().locale()),
                    StandardCharsets.UTF_8)
                .build()
                .toString())
        .contentType(MediaType.parseMediaType(ImportTaskApi.XLSX_CONTENT_TYPE))
        .body(imports.template());
  }

  /** Validates the file, queues the import and returns 202 with the task ID to poll. */
  @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  @AuditLog(module = "KOL", action = "Import")
  public ResponseEntity<R<KolImportVO>> submit(@RequestParam("file") MultipartFile file) {
    byte[] content;
    try {
      content = file.getBytes();
    } catch (IOException e) {
      throw new BusinessException(ErrorCode.TASK_FILE_INVALID);
    }
    Long taskId =
        imports.submit(
            new ImportFileDTO(file.getOriginalFilename(), file.getContentType(), content));
    return ResponseEntity.status(HttpStatus.ACCEPTED).body(R.ok(new KolImportVO(taskId)));
  }
}
