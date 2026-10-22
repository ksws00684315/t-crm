package com.tcrm.admin.task.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.tcrm.admin.shared.AppLocale;
import com.tcrm.admin.shared.BusinessException;
import com.tcrm.admin.shared.TenantScope;
import com.tcrm.admin.task.api.ImportFileDTO;
import com.tcrm.admin.task.api.ImportReason;
import com.tcrm.admin.task.api.ImportRowProcessor;
import com.tcrm.admin.task.api.ImportTaskApi;
import com.tcrm.admin.task.api.ImportTaskOutcome;
import com.tcrm.admin.task.api.TaskType;
import com.tcrm.admin.task.mapper.TaskFileMapper;
import com.tcrm.admin.task.mapper.TaskMapper;
import com.tcrm.admin.task.model.TaskDO;
import com.tcrm.admin.task.model.TaskFileDO;
import com.tcrm.admin.task.model.TaskFileKind;
import com.tcrm.admin.task.model.TaskStatus;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Runs Excel imports as batch tasks: synchronous file checks, then asynchronous row-by-row
 * processing with progress, an error file and a final status. Templates and error files are
 * rendered through the message bundles: templates in the request language, error files in the
 * language stored on the task row at submission (design §2.4; the worker thread has no request).
 */
@Service
public class ImportTaskService implements ImportTaskApi {
  /** Progress is written every this many rows, and once more at the end. */
  static final int PROGRESS_INTERVAL = 100;

  static final String ERROR_COLUMN = "import.error.column";
  static final String REASON_SEPARATOR = "import.error.reason-separator";
  static final String ROW_ERROR = "import.error.row-error";
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
  private final MessageSource messages;

  public ImportTaskService(
      ExcelFiles excel,
      TaskMapper tasks,
      TaskFileMapper files,
      TaskQueue queue,
      TransactionTemplate tx,
      MessageSource messages) {
    this.excel = excel;
    this.tasks = tasks;
    this.files = files;
    this.queue = queue;
    this.tx = tx;
    this.messages = messages;
  }

  @Override
  public Long submitImport(
      TaskType type,
      List<String> headerKeys,
      ImportFileDTO file,
      Supplier<ImportRowProcessor> processor,
      int maxRows) {
    return submitImport(type, headerKeys, List.of(), file, processor, maxRows);
  }

  @Override
  public Long submitImport(
      TaskType type,
      List<String> headerKeys,
      List<String> legacyHeaderKeys,
      ImportFileDTO file,
      Supplier<ImportRowProcessor> processor,
      int maxRows) {
    return submitImport(type, headerKeys, legacyHeaderKeys, file, processor, maxRows, null);
  }

  @Override
  public Long submitImport(
      TaskType type,
      List<String> headerKeys,
      List<String> legacyHeaderKeys,
      ImportFileDTO file,
      Supplier<ImportRowProcessor> processor,
      int maxRows,
      String params) {
    boolean allowCsv = type == TaskType.ORDER_IMPORT || type == TaskType.REFUND_IMPORT;
    excel.check(file, allowCsv);
    boolean asCsv =
        allowCsv
            && file.fileName() != null
            && file.fileName().toLowerCase(Locale.ROOT).endsWith(".csv");
    // The file must carry one full template language; the worker later re-reads the same variant.
    List<List<String>> variants =
        new ArrayList<>(variants(headerKeys, AppLocale.ZH_CN, AppLocale.EN_US));
    if (legacyHeaderKeys != null && !legacyHeaderKeys.isEmpty()) {
      variants.addAll(variants(legacyHeaderKeys, AppLocale.ZH_CN, AppLocale.EN_US));
    }
    ExcelFiles.SheetData sheet = excel.read(file.content(), List.copyOf(variants), maxRows, asCsv);
    int total = sheet.rows().size();
    TaskDO task = new TaskDO();
    task.setTaskType(type);
    task.setStatus(TaskStatus.WAITING);
    task.setTotal(total);
    task.setDone(0);
    task.setFailed(0);
    task.setDeleted(false);
    task.setLocale(AppLocale.current().tag());
    if (params != null) {
      task.setParams(params);
    }
    TaskFileDO input = new TaskFileDO();
    input.setKind(TaskFileKind.INPUT);
    input.setFileName(fileName(file.fileName()));
    input.setContentType(asCsv ? "text/csv" : XLSX_CONTENT_TYPE);
    input.setContent(file.content());
    tx.executeWithoutResult(
        status -> {
          tasks.insert(task);
          input.setTaskId(task.getId());
          files.insert(input);
        });
    Long id = task.getId();
    try {
      queue.submit(() -> run(id, sheet.matchedHeaders(), processor, maxRows));
    } catch (BusinessException e) {
      finish(id, null, TaskStatus.FAILED, MESSAGE_QUEUE_FULL);
      throw e;
    }
    LOG.info("Queued {} task {} with {} rows", type, id, total);
    return id;
  }

  @Override
  public Long submitRows(
      TaskType type,
      List<String> headerKeys,
      String fileName,
      List<List<String>> rows,
      Supplier<ImportRowProcessor> processor,
      int maxRows) {
    byte[] content = excel.write(render(headerKeys, List.of(), AppLocale.current()), rows);
    return submitImport(
        type,
        headerKeys,
        new ImportFileDTO(fileName, XLSX_CONTENT_TYPE, content),
        processor,
        maxRows);
  }

  @Override
  public byte[] template(List<String> headerKeys) {
    return template(headerKeys, List.of());
  }

  @Override
  public byte[] template(List<String> headerKeys, Collection<Integer> textColumns) {
    return excel.write(render(headerKeys, List.of(), AppLocale.current()), List.of(), textColumns);
  }

  /**
   * Worker body; never throws, every outcome ends in a final task status. The queue thread has no
   * login, so the company is read from the task row in system scope and the rest runs in that
   * company. A missing task or a missing company is left untouched.
   */
  void run(Long id, List<String> headers, Supplier<ImportRowProcessor> factory, int maxRows) {
    TaskDO task;
    try {
      task = TenantScope.callSystem("read import task company", () -> tasks.selectById(id));
    } catch (RuntimeException e) {
      LOG.error("Batch task {} failed unexpectedly: {}", id, e.getClass().getName());
      return;
    }
    if (task == null || task.getStatus() != TaskStatus.WAITING || task.getTenantId() == null) {
      return;
    }
    TenantScope.runCompany(
        task.getTenantId(),
        () -> work(id, headers, AppLocale.ofTag(task.getLocale()), factory, maxRows));
  }

  private void work(
      Long id,
      List<String> headers,
      AppLocale locale,
      Supplier<ImportRowProcessor> factory,
      int maxRows) {
    try {
      TaskDO running = new TaskDO();
      running.setId(id);
      running.setStatus(TaskStatus.RUNNING);
      running.setStartedAt(Instant.now());
      tasks.updateById(running);
      ExcelFiles.SheetData sheet;
      try {
        TaskFileDO inputFile = inputFile(id);
        boolean asCsv = "text/csv".equals(inputFile.getContentType());
        sheet = excel.read(inputFile.getContent(), List.of(headers), maxRows, asCsv);
      } catch (BusinessException e) {
        finish(id, null, TaskStatus.FAILED, MESSAGE_UNREADABLE);
        return;
      }
      processRows(id, sheet.matchedHeaders(), sheet.rows(), locale, factory.get());
    } catch (RuntimeException e) {
      // Exception messages may echo row data (e-mail addresses); log the type only (spec 02 §4).
      LOG.error("Batch task {} failed unexpectedly: {}", id, e.getClass().getName());
      finish(id, null, TaskStatus.FAILED, MESSAGE_UNEXPECTED);
    }
  }

  private void processRows(
      Long id,
      List<String> headers,
      List<List<String>> rows,
      AppLocale locale,
      ImportRowProcessor processor) {
    List<List<String>> errors = new ArrayList<>();
    int done = 0;
    int failed = 0;
    for (List<String> row : rows) {
      List<ImportReason> reasons = processRow(id, processor, row);
      done++;
      if (!reasons.isEmpty()) {
        failed++;
        List<String> errorRow = new ArrayList<>(row);
        errorRow.add(join(reasons, locale));
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
      errorHeaders.add(message(ERROR_COLUMN, List.of(), locale));
      errorFile = new TaskFileDO();
      errorFile.setTaskId(id);
      errorFile.setKind(TaskFileKind.ERROR);
      errorFile.setFileName("task-" + id + "-errors.xlsx");
      errorFile.setContentType(XLSX_CONTENT_TYPE);
      errorFile.setContent(excel.write(errorHeaders, errors));
    }
    finish(id, errorFile, status, status == TaskStatus.FAILED ? MESSAGE_ALL_FAILED : null, result);
    notifyFinished(processor, rows.size(), done, failed, status);
    LOG.info("Batch task {} finished {}: {} rows, {} failed", id, status, done, failed);
  }

  private static void notifyFinished(
      ImportRowProcessor processor, int total, int done, int failed, TaskStatus status) {
    var outcome =
        new ImportTaskOutcome(total, done, failed, ImportTaskOutcome.Status.valueOf(status.name()));
    try {
      processor.onFinish(outcome);
    } catch (RuntimeException e) {
      // The task itself already finished; listener problems must not flip its status.
      LOG.error("Batch task finish listener failed: {}", e.getClass().getName());
    }
  }

  private List<ImportReason> processRow(Long id, ImportRowProcessor processor, List<String> row) {
    try {
      List<ImportReason> reasons = processor.process(row);
      return reasons == null ? List.of() : reasons;
    } catch (RuntimeException e) {
      LOG.error("Batch task {} row failed unexpectedly: {}", id, e.getClass().getName());
      return List.of(ImportReason.of(ROW_ERROR));
    }
  }

  private TaskFileDO inputFile(Long id) {
    TaskFileDO file =
        files.selectOne(
            new LambdaQueryWrapper<TaskFileDO>()
                .eq(TaskFileDO::getTaskId, id)
                .eq(TaskFileDO::getKind, TaskFileKind.INPUT));
    if (file == null) {
      throw new IllegalStateException("Input file missing for task " + id);
    }
    return file;
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

  private List<List<String>> variants(List<String> headerKeys, AppLocale first, AppLocale second) {
    return List.of(render(headerKeys, List.of(), first), render(headerKeys, List.of(), second));
  }

  private List<String> render(List<String> keys, List<Object> args, AppLocale locale) {
    return keys.stream().map(key -> message(key, args, locale)).toList();
  }

  private String join(List<ImportReason> reasons, AppLocale locale) {
    String separator = message(REASON_SEPARATOR, List.of(), locale);
    return reasons.stream()
        .map(reason -> message(reason.key(), reason.args(), locale))
        .collect(Collectors.joining(separator));
  }

  private String message(String key, List<Object> args, AppLocale locale) {
    return messages.getMessage(key, args.toArray(), locale.locale());
  }
}
