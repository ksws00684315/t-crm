package com.tcrm.admin.product.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.tcrm.admin.product.service.MappingImportService;
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

/** Kol item mapping Excel import (task 05 §4.3). Requires product:edit. */
@RestController
@RequestMapping("/admin-api/product/kol-item-imports")
@SaCheckPermission("product:edit")
public class KolItemImportController {
  static final String TEMPLATE_NAME_KEY = "import.mapping.template-name";
  private final MappingImportService imports;
  private final MessageSource messages;

  public KolItemImportController(MappingImportService imports, MessageSource messages) {
    this.imports = imports;
    this.messages = messages;
  }

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

  @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  @AuditLog(module = "PRODUCT", action = "Import mappings")
  public ResponseEntity<R<KolItemImportVO>> submit(@RequestParam("file") MultipartFile file) {
    byte[] content;
    try {
      content = file.getBytes();
    } catch (IOException e) {
      throw new BusinessException(ErrorCode.TASK_FILE_INVALID);
    }
    Long taskId =
        imports.submit(
            new ImportFileDTO(file.getOriginalFilename(), file.getContentType(), content));
    return ResponseEntity.status(HttpStatus.ACCEPTED).body(R.ok(new KolItemImportVO(taskId)));
  }
}
