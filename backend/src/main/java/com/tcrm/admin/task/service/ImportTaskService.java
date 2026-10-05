package com.tcrm.admin.task.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.tcrm.admin.shared.BusinessException;
import com.tcrm.admin.task.api.ImportFileDTO;
import com.tcrm.admin.task.api.ImportRowProcessor;
import com.tcrm.admin.task.api.ImportTaskApi;
import com.tcrm.admin.task.api.TaskType;
import com.tcrm.admin.task.mapper.TaskFileMapper;
import com.tcrm.admin.task.mapper.TaskMapper;
import com.tcrm.admin.task.model.TaskDO;
import com.tcrm.admin.task.model.TaskFileDO;
import com.tcrm.admin.task.model.TaskFileKind;
import com.tcrm.admin.task.model.TaskStatus;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Runs Excel imports as batch tasks: synchronous file checks, then asynchronous row-by-row
 * processing with progress, an error file and a final status.
 */
@Service
public class ImportTaskService implements ImportTaskApi {
  /** Progress is written every this many rows, and once more at the end. */
  static final int PROGRESS_INTERVAL = 100;

  static final String ERROR_COLUMN = "错误原因";
  static final String REASON_SEPARATOR = "；";
  static final String ROW_ERROR = "系统错误，该行未导入";
  static final String MESSAGE_ALL_FAILED = "All rows failed";
  static final String MESSAGE_UNREADABLE = "File cannot be parsed";
  static final String MESSAGE_QUEUE_FULL = "Rejected: task queue full";
  static final String MESSAGE_UNEXPECTED = "Unexpected error";
  private static final Logger LOG = LoggerFactory.getLogger(ImportTaskService.class);
  private static final int MAX_FILE_NAME = 255;

  private final ExcelFiles excel;
  private final TaskMapper tasks;
  private final TaskFileMapper files;
  private final TaskQueue queue;
  private final TransactionTemplate tx;

  public ImportTaskService(
      ExcelFiles excel,
      TaskMapper tasks,
      TaskFileMapper files,
      TaskQueue queue,
      TransactionTemplate tx) {
    this.excel = excel;
    this.tasks = tasks;
    this.files = files;
    this.queue = queue;
    this.tx = tx;
  }

  @Override
  public Long submitImport(
      TaskType type,
      List<String> headers,
      ImportFileDTO file,
      Supplier<ImportRowProcessor> processor) {
    excel.check(file);
    int total = excel.read(file.content(), headers).size();
    TaskDO task = new TaskDO();
    task.setTaskType(type);
    task.setStatus(TaskStatus.WAITING);
    task.setTotal(total);
    task.setDone(0);
    task.setFailed(0);
    task.setDeleted(false);
    TaskFileDO input = new TaskFileDO();
    input.setKind(TaskFileKind.INPUT);
    input.setFileName(fileName(file.fileName()));
    input.setContentType(XLSX_CONTENT_TYPE);
    input.setContent(file.content());
    tx.executeWithoutResult(
        status -> {
          tasks.insert(task);
          input.setTaskId(task.getId());
          files.insert(input);
        });
    Long id = task.getId();
    try {
      queue.submit(() -> run(id, headers, processor));
    } catch (BusinessException e) {
      finish(id, null, TaskStatus.FAILED, MESSAGE_QUEUE_FULL);
      throw e;
    }
    LOG.info("Queued {} task {} with {} rows", type, id, total);
    return id;
  }

  @Override
  public byte[] template(List<String> headers) {
    return excel.write(headers, List.of());
  }

  /** Worker body; never throws, every outcome ends in a final task status. */
  void run(Long id, List<String> headers, Supplier<ImportRowProcessor> factory) {
    try {
      TaskDO task = tasks.selectById(id);
      if (task == null || task.getStatus() != TaskStatus.WAITING) {
        return;
      }
      TaskDO running = new TaskDO();
      running.setId(id);
      running.setStatus(TaskStatus.RUNNING);
      running.setStartedAt(Instant.now());
      tasks.updateById(running);
      List<List<String>> rows;
      try {
        rows = excel.read(input(id), headers);
      } catch (BusinessException e) {
        finish(id, null, TaskStatus.FAILED, MESSAGE_UNREADABLE);
        return;
      }
      processRows(id, headers, rows, factory.get());
    } catch (RuntimeException e) {
      // Exception messages may echo row data (e-mail addresses); log the type only (spec 02 §4).
      LOG.error("Batch task {} failed unexpectedly: {}", id, e.getClass().getName());
      finish(id, null, TaskStatus.FAILED, MESSAGE_UNEXPECTED);
    }
  }

  private void processRows(
      Long id, List<String> headers, List<List<String>> rows, ImportRowProcessor processor) {
    List<List<String>> errors = new ArrayList<>();
    int done = 0;
    int failed = 0;
    for (List<String> row : rows) {
      List<String> reasons = processRow(id, processor, row);
      done++;
      if (!reasons.isEmpty()) {
        failed++;
        List<String> errorRow = new ArrayList<>(row);
        errorRow.add(String.join(REASON_SEPARATOR, reasons));
        errors.add(errorRow);
      }
      if (done % PROGRESS_INTERVAL == 0 && done < rows.size()) {
        progress(id, done, failed);
      }
    }
    TaskStatus status = TaskStatus.finish(rows.size(), failed);
    TaskDO result = new TaskDO();
    result.setDone(done);
    result.setFailed(failed);
    TaskFileDO errorFile = null;
    if (!errors.isEmpty()) {
      List<String> errorHeaders = new ArrayList<>(headers);
      errorHeaders.add(ERROR_COLUMN);
      errorFile = new TaskFileDO();
      errorFile.setTaskId(id);
      errorFile.setKind(TaskFileKind.ERROR);
      errorFile.setFileName("task-" + id + "-errors.xlsx");
      errorFile.setContentType(XLSX_CONTENT_TYPE);
      errorFile.setContent(excel.write(errorHeaders, errors));
    }
    finish(id, errorFile, status, status == TaskStatus.FAILED ? MESSAGE_ALL_FAILED : null, result);
    LOG.info("Batch task {} finished {}: {} rows, {} failed", id, status, done, failed);
  }

  private List<String> processRow(Long id, ImportRowProcessor processor, List<String> row) {
    try {
      List<String> reasons = processor.process(row);
      return reasons == null ? List.of() : reasons;
    } catch (RuntimeException e) {
      LOG.error("Batch task {} row failed unexpectedly: {}", id, e.getClass().getName());
      return List.of(ROW_ERROR);
    }
  }

  private byte[] input(Long id) {
    TaskFileDO file =
        files.selectOne(
            new LambdaQueryWrapper<TaskFileDO>()
                .eq(TaskFileDO::getTaskId, id)
                .eq(TaskFileDO::getKind, TaskFileKind.INPUT));
    if (file == null) {
      throw new IllegalStateException("Input file missing for task " + id);
    }
    return file.getContent();
  }

  private void progress(Long id, int done, int failed) {
    TaskDO update = new TaskDO();
    update.setId(id);
    update.setDone(done);
    update.setFailed(failed);
    tasks.updateById(update);
  }

  private void finish(Long id, TaskFileDO errorFile, TaskStatus status, String message) {
    finish(id, errorFile, status, message, new TaskDO());
  }

  private void finish(
      Long id, TaskFileDO errorFile, TaskStatus status, String message, TaskDO update) {
    update.setId(id);
    update.setStatus(status);
    update.setMessage(message);
    update.setFinishedAt(Instant.now());
    tx.executeWithoutResult(
        s -> {
          if (errorFile != null) {
            files.insert(errorFile);
          }
          tasks.updateById(update);
        });
  }

  private static String fileName(String original) {
    String name = original == null ? "" : original;
    name = name.substring(Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\')) + 1).strip();
    if (name.isEmpty()) {
      name = "import.xlsx";
    }
    return name.length() > MAX_FILE_NAME ? name.substring(name.length() - MAX_FILE_NAME) : name;
  }
}
