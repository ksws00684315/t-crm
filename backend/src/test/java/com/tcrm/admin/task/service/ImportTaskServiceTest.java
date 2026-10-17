package com.tcrm.admin.task.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.idev.excel.FastExcel;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.tcrm.admin.shared.BusinessException;
import com.tcrm.admin.shared.ErrorCode;
import com.tcrm.admin.task.api.ImportFileDTO;
import com.tcrm.admin.task.api.ImportReason;
import com.tcrm.admin.task.api.ImportRowProcessor;
import com.tcrm.admin.task.api.ImportTaskApi;
import com.tcrm.admin.task.api.TaskType;
import com.tcrm.admin.task.mapper.TaskFileMapper;
import com.tcrm.admin.task.mapper.TaskMapper;
import com.tcrm.admin.task.model.TaskDO;
import com.tcrm.admin.task.model.TaskFileDO;
import com.tcrm.admin.task.model.TaskFileKind;
import com.tcrm.admin.task.model.TaskStatus;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class ImportTaskServiceTest {
  /** Real bundle keys, so the service renders through the production message files. */
  private static final List<String> HEADER_KEYS =
      List.of("import.kol.header.code", "import.kol.header.name");

  private static final String REASON_CODE_DUPLICATE = "import.kol.error.code-duplicate";
  private static final String REASON_CODE_EXISTS = "import.kol.error.code-exists";
  private static final long TASK_ID = 42L;

  private static MessageSource bundles() {
    ResourceBundleMessageSource source = new ResourceBundleMessageSource();
    source.setBasenames("messages");
    source.setDefaultEncoding("UTF-8");
    source.setFallbackToSystemLocale(false);
    return source;
  }

  private static List<String> render(List<String> keys, Locale locale) {
    MessageSource messages = bundles();
    return keys.stream().map(key -> messages.getMessage(key, null, locale)).toList();
  }

  @BeforeAll
  static void tableInfo() {
    TableInfoHelper.initTableInfo(
        new MapperBuilderAssistant(new MybatisConfiguration(), ""), TaskDO.class);
    TableInfoHelper.initTableInfo(
        new MapperBuilderAssistant(new MybatisConfiguration(), ""), TaskFileDO.class);
  }

  private final ExcelFiles excel = new ExcelFiles();
  private final TaskMapper tasks = mock(TaskMapper.class);
  private final TaskFileMapper files = mock(TaskFileMapper.class);
  private final ExecutorService executor = mock(ExecutorService.class);
  private final List<Runnable> queued = new ArrayList<>();
  private final List<TaskDO> updates = new ArrayList<>();
  private final List<TaskFileDO> inserted = new ArrayList<>();
  private final MessageSource messages = bundles();
  private final List<String> headersZh = render(HEADER_KEYS, Locale.SIMPLIFIED_CHINESE);
  private final List<String> headersEn = render(HEADER_KEYS, Locale.ENGLISH);
  private ImportTaskService service;

  @BeforeEach
  void setup() {
    LocaleContextHolder.setLocale(Locale.SIMPLIFIED_CHINESE);
    doAnswer(
            invocation -> {
              queued.add(invocation.getArgument(0));
              return null;
            })
        .when(executor)
        .execute(any());
    doAnswer(
            invocation -> {
              invocation.<TaskDO>getArgument(0).setId(TASK_ID);
              return 1;
            })
        .when(tasks)
        .insert(any(TaskDO.class));
    doAnswer(
            invocation -> {
              updates.add(invocation.getArgument(0));
              return 1;
            })
        .when(tasks)
        .updateById(any(TaskDO.class));
    doAnswer(
            invocation -> {
              inserted.add(invocation.getArgument(0));
              return 1;
            })
        .when(files)
        .insert(any(TaskFileDO.class));
    service =
        new ImportTaskService(
            excel,
            tasks,
            files,
            new TaskQueue(executor),
            new TransactionTemplate(mock(PlatformTransactionManager.class)),
            messages);
  }

  @AfterEach
  void resetLocale() {
    LocaleContextHolder.resetLocaleContext();
  }

  private byte[] workbook(List<List<String>> rows) {
    return excel.write(headersZh, rows);
  }

  private ImportFileDTO upload(byte[] content) {
    return new ImportFileDTO("C:\\temp\\达人.xlsx", ImportTaskApi.XLSX_CONTENT_TYPE, content);
  }

  private void storedInput(byte[] content, String locale) {
    TaskDO waiting = new TaskDO();
    waiting.setId(TASK_ID);
    waiting.setStatus(TaskStatus.WAITING);
    waiting.setTenantId(1L);
    waiting.setLocale(locale);
    when(tasks.selectById(TASK_ID)).thenReturn(waiting);
    TaskFileDO input = new TaskFileDO();
    input.setKind(TaskFileKind.INPUT);
    input.setContent(content);
    when(files.selectOne(any())).thenReturn(input);
  }

  private TaskDO lastUpdate() {
    return updates.getLast();
  }

  private static List<Map<Integer, String>> readBack(byte[] content) {
    return FastExcel.read(new ByteArrayInputStream(content)).sheet(0).headRowNumber(0).doReadSync();
  }

  @Test
  void shouldStoreWaitingTaskAndQueueIt_whenFileIsValid() {
    byte[] content = workbook(List.of(List.of("KOL0001", "a"), List.of("", "b")));

    Long id =
        service.submitImport(
            TaskType.KOL_IMPORT,
            HEADER_KEYS,
            upload(content),
            () -> null,
            TaskType.KOL_IMPORT.maxRows());

    assertThat(id).isEqualTo(TASK_ID);
    ArgumentCaptor<TaskDO> task = ArgumentCaptor.forClass(TaskDO.class);
    verify(tasks).insert(task.capture());
    assertThat(task.getValue().getStatus()).isEqualTo(TaskStatus.WAITING);
    assertThat(task.getValue().getTaskType()).isEqualTo(TaskType.KOL_IMPORT);
    assertThat(task.getValue().getTotal()).isEqualTo(2);
    assertThat(task.getValue().getLocale()).isEqualTo("zh-CN");
    assertThat(inserted)
        .singleElement()
        .satisfies(
            file -> {
              assertThat(file.getKind()).isEqualTo(TaskFileKind.INPUT);
              assertThat(file.getTaskId()).isEqualTo(TASK_ID);
              assertThat(file.getFileName()).isEqualTo("达人.xlsx");
              assertThat(file.getContent()).isEqualTo(content);
            });
    assertThat(queued).hasSize(1);
  }

  @Test
  void shouldStoreGeneratedRowsAsTheInputWorkbook_whenRowsComeFromTheSystem() {
    Long id =
        service.submitRows(
            TaskType.KOL_IMPORT,
            HEADER_KEYS,
            "源表.xlsx",
            List.of(List.of("KOL0001", "a"), List.of("", "=b")),
            () -> null,
            TaskType.KOL_IMPORT.maxRows());

    assertThat(id).isEqualTo(TASK_ID);
    ArgumentCaptor<TaskDO> task = ArgumentCaptor.forClass(TaskDO.class);
    verify(tasks).insert(task.capture());
    assertThat(task.getValue().getTotal()).isEqualTo(2);
    assertThat(inserted)
        .singleElement()
        .satisfies(
            file -> {
              assertThat(file.getFileName()).isEqualTo("源表.xlsx");
              List<Map<Integer, String>> rows = readBack(file.getContent());
              assertThat(rows.get(0).values()).containsExactlyElementsOf(headersZh);
              assertThat(rows.get(1).values()).containsExactly("KOL0001", "a");
              // Cells are escaped like any workbook the system writes.
              assertThat(rows.get(2).get(1)).isEqualTo("'=b");
            });
    assertThat(queued).hasSize(1);
  }

  @Test
  void shouldStoreTaskLocale_whenRequestIsEnglish() {
    LocaleContextHolder.setLocale(Locale.ENGLISH);
    byte[] content = excel.write(headersEn, List.of(List.of("KOL0001", "a")));

    service.submitImport(
        TaskType.KOL_IMPORT,
        HEADER_KEYS,
        upload(content),
        () -> null,
        TaskType.KOL_IMPORT.maxRows());

    ArgumentCaptor<TaskDO> task = ArgumentCaptor.forClass(TaskDO.class);
    verify(tasks).insert(task.capture());
    assertThat(task.getValue().getLocale()).isEqualTo("en-US");
  }

  @Test
  void shouldProcessEnglishTemplateEndToEnd_whenFileCarriesEnglishHeaders() {
    LocaleContextHolder.setLocale(Locale.ENGLISH);
    byte[] content = excel.write(headersEn, List.of(List.of("KOL0001", "a")));
    service.submitImport(
        TaskType.KOL_IMPORT,
        HEADER_KEYS,
        upload(content),
        () -> cells -> List.of(),
        TaskType.KOL_IMPORT.maxRows());
    // The queued worker must re-read the file with the English variant it matched at submission.
    LocaleContextHolder.setLocale(Locale.SIMPLIFIED_CHINESE);
    storedInput(content, "en-US");

    queued.getFirst().run();

    assertThat(lastUpdate().getStatus()).isEqualTo(TaskStatus.SUCCESS);
  }

  @Test
  void shouldRejectBeforeStoring_whenFileIsInvalid() {
    byte[] content = excel.write(List.of("其他"), List.of(List.of("x")));

    assertThatThrownBy(
            () ->
                service.submitImport(
                    TaskType.KOL_IMPORT,
                    HEADER_KEYS,
                    upload(content),
                    () -> null,
                    TaskType.KOL_IMPORT.maxRows()))
        .isInstanceOf(BusinessException.class)
        .extracting(e -> ((BusinessException) e).getErrorCode())
        .isEqualTo(ErrorCode.TASK_FILE_HEADER_MISMATCH);
    verify(tasks, never()).insert(any(TaskDO.class));
    assertThat(queued).isEmpty();
  }

  @Test
  void shouldRejectMixedLanguageHeader_evenWhenBothListsAreKnown() {
    List<String> mixed = List.of(headersZh.get(0), headersEn.get(1));
    byte[] content = excel.write(mixed, List.of(List.of("KOL0001", "a")));

    assertThatThrownBy(
            () ->
                service.submitImport(
                    TaskType.KOL_IMPORT,
                    HEADER_KEYS,
                    upload(content),
                    () -> null,
                    TaskType.KOL_IMPORT.maxRows()))
        .isInstanceOf(BusinessException.class)
        .extracting(e -> ((BusinessException) e).getErrorCode())
        .isEqualTo(ErrorCode.TASK_FILE_HEADER_MISMATCH);
  }

  @Test
  void shouldFailTaskAndReturn429_whenQueueIsFull() {
    doAnswer(
            invocation -> {
              throw new RejectedExecutionException("full");
            })
        .when(executor)
        .execute(any());
    byte[] content = workbook(List.of(List.of("", "a")));

    assertThatThrownBy(
            () ->
                service.submitImport(
                    TaskType.KOL_IMPORT,
                    HEADER_KEYS,
                    upload(content),
                    () -> null,
                    TaskType.KOL_IMPORT.maxRows()))
        .isInstanceOf(BusinessException.class)
        .extracting(e -> ((BusinessException) e).getErrorCode())
        .isEqualTo(ErrorCode.TASK_QUEUE_FULL);
    assertThat(lastUpdate().getStatus()).isEqualTo(TaskStatus.FAILED);
    assertThat(lastUpdate().getMessage()).isEqualTo(ImportTaskService.MESSAGE_QUEUE_FULL);
  }

  @Test
  void shouldFinishSuccess_whenEveryRowIsImported() {
    storedInput(workbook(List.of(List.of("", "a"), List.of("", "b"))), "zh-CN");
    List<List<String>> seen = new ArrayList<>();

    service.run(
        TASK_ID,
        headersZh,
        () ->
            cells -> {
              seen.add(cells);
              return List.of();
            },
        TaskType.KOL_IMPORT.maxRows());

    assertThat(seen).containsExactly(List.of("", "a"), List.of("", "b"));
    assertThat(updates.getFirst().getStatus()).isEqualTo(TaskStatus.RUNNING);
    assertThat(updates.getFirst().getStartedAt()).isNotNull();
    assertThat(lastUpdate().getStatus()).isEqualTo(TaskStatus.SUCCESS);
    assertThat(lastUpdate().getDone()).isEqualTo(2);
    assertThat(lastUpdate().getFailed()).isZero();
    assertThat(lastUpdate().getMessage()).isNull();
    assertThat(lastUpdate().getFinishedAt()).isNotNull();
    assertThat(inserted).isEmpty();
  }

  @Test
  void shouldFinishPartialWithErrorFileInChinese_whenTaskWasSubmittedInChinese() {
    storedInput(workbook(List.of(List.of("", "ok"), List.of("=cmd", "bad"))), "zh-CN");
    ImportRowProcessor processor =
        cells ->
            cells.get(1).equals("bad")
                ? List.of(
                    ImportReason.of(REASON_CODE_DUPLICATE), ImportReason.of(REASON_CODE_EXISTS))
                : List.of();

    service.run(TASK_ID, headersZh, () -> processor, TaskType.KOL_IMPORT.maxRows());

    assertThat(lastUpdate().getStatus()).isEqualTo(TaskStatus.PARTIAL);
    assertThat(lastUpdate().getDone()).isEqualTo(2);
    assertThat(lastUpdate().getFailed()).isEqualTo(1);
    TaskFileDO errorFile = inserted.getFirst();
    assertThat(errorFile.getKind()).isEqualTo(TaskFileKind.ERROR);
    assertThat(errorFile.getTaskId()).isEqualTo(TASK_ID);
    assertThat(errorFile.getFileName()).isEqualTo("task-42-errors.xlsx");
    List<Map<Integer, String>> rows = readBack(errorFile.getContent());
    assertThat(rows).hasSize(2);
    assertThat(rows.get(0)).containsEntry(0, "达人编号").containsEntry(2, "错误原因");
    assertThat(rows.get(1))
        .containsEntry(0, "'=cmd")
        .containsEntry(1, "bad")
        .containsEntry(2, "达人编号在文件中重复；达人编号已存在");
  }

  @Test
  void shouldFinishWithErrorFileInEnglish_whenTaskWasSubmittedInEnglish() {
    // English template in the file, English task language, Chinese thread locale on the worker.
    byte[] content = excel.write(headersEn, List.of(List.of("", "ok"), List.of("=cmd", "bad")));
    storedInput(content, "en-US");
    LocaleContextHolder.setLocale(Locale.SIMPLIFIED_CHINESE);
    ImportRowProcessor processor =
        cells ->
            cells.get(1).equals("bad")
                ? List.of(
                    ImportReason.of(REASON_CODE_DUPLICATE), ImportReason.of(REASON_CODE_EXISTS))
                : List.of();

    service.run(TASK_ID, headersEn, () -> processor, TaskType.KOL_IMPORT.maxRows());

    assertThat(lastUpdate().getStatus()).isEqualTo(TaskStatus.PARTIAL);
    List<Map<Integer, String>> rows = readBack(inserted.getFirst().getContent());
    // The file's own headers are echoed; error texts follow the task language, not the thread.
    assertThat(rows.get(0)).containsEntry(0, "KOL Code").containsEntry(2, "Error");
    assertThat(rows.get(1))
        .containsEntry(2, "KOL code appears more than once in the file; KOL code already exists");
  }

  @Test
  void shouldFinishFailed_whenEveryRowFails() {
    storedInput(workbook(List.of(List.of("", "a"))), "zh-CN");

    service.run(
        TASK_ID,
        headersZh,
        () -> cells -> List.of(ImportReason.of(REASON_CODE_DUPLICATE)),
        TaskType.KOL_IMPORT.maxRows());

    assertThat(lastUpdate().getStatus()).isEqualTo(TaskStatus.FAILED);
    assertThat(lastUpdate().getMessage()).isEqualTo(ImportTaskService.MESSAGE_ALL_FAILED);
    assertThat(inserted).hasSize(1);
  }

  @Test
  void shouldCountRowAsFailed_whenProcessorThrows() {
    storedInput(workbook(List.of(List.of("", "a"), List.of("", "b"))), "zh-CN");
    ImportRowProcessor processor =
        cells -> {
          if (cells.get(1).equals("a")) {
            throw new IllegalStateException("boom");
          }
          return null;
        };

    service.run(TASK_ID, headersZh, () -> processor, TaskType.KOL_IMPORT.maxRows());

    assertThat(lastUpdate().getStatus()).isEqualTo(TaskStatus.PARTIAL);
    assertThat(readBack(inserted.getFirst().getContent()).get(1))
        .containsEntry(
            2, messages.getMessage(ImportTaskService.ROW_ERROR, null, Locale.SIMPLIFIED_CHINESE));
  }

  @Test
  void shouldWriteProgressEveryHundredRows_whenProcessing() {
    List<List<String>> rows = new ArrayList<>();
    for (int i = 0; i < 250; i++) {
      rows.add(List.of("", "n" + i));
    }
    storedInput(workbook(rows), "zh-CN");

    service.run(TASK_ID, headersZh, () -> cells -> List.of(), TaskType.KOL_IMPORT.maxRows());

    // RUNNING, progress at 100 and 200, final SUCCESS.
    verify(tasks, times(4)).updateById(any(TaskDO.class));
    assertThat(updates.get(1).getDone()).isEqualTo(100);
    assertThat(updates.get(2).getDone()).isEqualTo(200);
    assertThat(lastUpdate().getDone()).isEqualTo(250);
  }

  @Test
  void shouldFailTask_whenStoredFileCannotBeParsed() {
    storedInput(new byte[] {0x50, 0x4B, 0x03, 0x04, 9, 9}, "zh-CN");

    service.run(TASK_ID, headersZh, () -> cells -> List.of(), TaskType.KOL_IMPORT.maxRows());

    assertThat(lastUpdate().getStatus()).isEqualTo(TaskStatus.FAILED);
    assertThat(lastUpdate().getMessage()).isEqualTo(ImportTaskService.MESSAGE_UNREADABLE);
  }

  @Test
  void shouldFailTask_whenUnexpectedErrorOccurs() {
    storedInput(workbook(List.of(List.of("", "a"))), "zh-CN");

    service.run(
        TASK_ID,
        headersZh,
        () -> {
          throw new IllegalStateException("cannot create processor");
        },
        TaskType.KOL_IMPORT.maxRows());

    assertThat(lastUpdate().getStatus()).isEqualTo(TaskStatus.FAILED);
    assertThat(lastUpdate().getMessage()).isEqualTo(ImportTaskService.MESSAGE_UNEXPECTED);
  }

  @Test
  void shouldFailTask_whenInputFileIsMissing() {
    storedInput(workbook(List.of(List.of("", "a"))), "zh-CN");
    when(files.selectOne(any())).thenReturn(null);

    service.run(TASK_ID, headersZh, () -> cells -> List.of(), TaskType.KOL_IMPORT.maxRows());

    assertThat(lastUpdate().getMessage()).isEqualTo(ImportTaskService.MESSAGE_UNEXPECTED);
  }

  @Test
  void shouldSkip_whenTaskIsNoLongerWaiting() {
    TaskDO failed = new TaskDO();
    failed.setId(TASK_ID);
    failed.setStatus(TaskStatus.FAILED);
    when(tasks.selectById(TASK_ID)).thenReturn(failed);

    service.run(TASK_ID, headersZh, () -> cells -> List.of(), TaskType.KOL_IMPORT.maxRows());

    verify(tasks, never()).updateById(any(TaskDO.class));
  }

  @Test
  void shouldProduceChineseTemplate_whenRequestIsChinese() {
    List<Map<Integer, String>> rows = readBack(service.template(HEADER_KEYS));
    assertThat(rows).singleElement().satisfies(r -> assertThat(r).containsEntry(1, "姓名"));
  }

  @Test
  void shouldProduceEnglishTemplate_whenRequestIsEnglish() {
    LocaleContextHolder.setLocale(Locale.ENGLISH);
    List<Map<Integer, String>> rows = readBack(service.template(HEADER_KEYS));
    assertThat(rows).singleElement().satisfies(r -> assertThat(r).containsEntry(1, "Name"));
  }

  private List<String> headersZh_ZH() {
    return headersZh;
  }
}
