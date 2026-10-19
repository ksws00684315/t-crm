package com.tcrm.admin.task.controller;

import cn.dev33.satoken.annotation.SaCheckRole;
import cn.dev33.satoken.annotation.SaMode;
import com.tcrm.admin.shared.PageResult;
import com.tcrm.admin.shared.R;
import com.tcrm.admin.task.api.TaskType;
import com.tcrm.admin.task.model.TaskStatus;
import com.tcrm.admin.task.service.TaskFileDTO;
import com.tcrm.admin.task.service.TaskListDTO;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Batch task progress and error files. Any company account may call; the service allows only the
 * submitter or a super administrator (403 otherwise, 404 when missing).
 */
@RestController
@RequestMapping("/admin-api/task/tasks")
@SaCheckRole(
    value = {"SUPER_ADMIN", "STAFF"},
    mode = SaMode.OR)
public class TaskController {
  private final TaskService tasks;

  public TaskController(TaskService tasks) {
    this.tasks = tasks;
  }

  @GetMapping
  public R<PageResult<TaskListVO>> page(
      @RequestParam(required = false) TaskType type,
      @RequestParam(required = false) TaskStatus status,
      @RequestParam(defaultValue = "1") @Min(1) int page,
      @RequestParam(defaultValue = "20") @Min(1) @jakarta.validation.constraints.Max(100)
          int size) {
    return R.ok(tasks.page(type, status, page, size).map(TaskListVO::from));
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

  public record TaskListVO(
      Long id,
      TaskType type,
      String fileName,
      TaskStatus status,
      int totalRows,
      int successRows,
      int failedRows,
      java.time.Instant createTime,
      java.time.Instant finishTime,
      String operatorName,
      boolean hasErrorFile) {
    static TaskListVO from(TaskListDTO task) {
      return new TaskListVO(
          task.id(),
          task.type(),
          task.fileName(),
          task.status(),
          task.totalRows(),
          task.successRows(),
          task.failedRows(),
          task.createTime(),
          task.finishTime(),
          task.operatorName(),
          task.hasErrorFile());
    }
  }
}
