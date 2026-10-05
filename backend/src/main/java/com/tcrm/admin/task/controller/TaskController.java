package com.tcrm.admin.task.controller;

import cn.dev33.satoken.annotation.SaCheckRole;
import cn.dev33.satoken.annotation.SaMode;
import com.tcrm.admin.shared.R;
import com.tcrm.admin.task.service.TaskFileDTO;
import com.tcrm.admin.task.service.TaskService;
import jakarta.validation.constraints.Min;
import java.nio.charset.StandardCharsets;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Batch task progress and error files. Any administrator role may call; the service allows only the
 * submitter or a super administrator (403 otherwise, 404 when missing).
 */
@RestController
@RequestMapping("/admin-api/task/tasks")
@SaCheckRole(
    value = {"SUPER_ADMIN", "OPERATOR", "FINANCE"},
    mode = SaMode.OR)
public class TaskController {
  private final TaskService tasks;

  public TaskController(TaskService tasks) {
    this.tasks = tasks;
  }

  @GetMapping("/{id}")
  public R<TaskVO> get(@PathVariable @Min(1) Long id) {
    return R.ok(TaskVO.from(tasks.get(id)));
  }

  /** Error rows as .xlsx; not a JSON endpoint. */
  @GetMapping("/{id}/error-file")
  public ResponseEntity<byte[]> errorFile(@PathVariable @Min(1) Long id) {
    TaskFileDTO file = tasks.getErrorFile(id);
    return ResponseEntity.ok()
        .header(
            HttpHeaders.CONTENT_DISPOSITION,
            ContentDisposition.attachment()
                .filename(file.fileName(), StandardCharsets.UTF_8)
                .build()
                .toString())
        .contentType(MediaType.parseMediaType(file.contentType()))
        .body(file.content());
  }
}
