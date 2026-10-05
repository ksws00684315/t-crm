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
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class ImportTaskServiceTest {
  @BeforeAll
  static void tableInfo() {
    TableInfoHelper.initTableInfo(
        new MapperBuilderAssistant(new MybatisConfiguration(), ""), TaskDO.class);
    TableInfoHelper.initTableInfo(
        new MapperBuilderAssistant(new MybatisConfiguration(), ""), TaskFileDO.class);
  }

  private static final List<String> HEADERS = List.of("编号", "姓名");
  private static final long TASK_ID = 42L;

  private final ExcelFiles excel = new ExcelFiles();
  private final TaskMapper tasks = mock(TaskMapper.class);
  private final TaskFileMapper files = mock(TaskFileMapper.class);
  private final ExecutorService executor = mock(ExecutorService.class);
  private final List<Runnable> queued = new ArrayList<>();
  private final List<TaskDO> updates = new ArrayList<>();
  private final List<TaskFileDO> inserted = new ArrayList<>();
  private ImportTaskService service;

  @BeforeEach
  void setup() {
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
            new TransactionTemplate(mock(PlatformTransactionManager.class)));
  }

  private byte[] workbook(List<List<String>> rows) {
    return excel.write(HEADERS, rows);
  }

  private ImportFileDTO upload(byte[] content) {
    return new ImportFileDTO("C:\\temp\\达人.xlsx", ImportTaskApi.XLSX_CONTENT_TYPE, content);
  }

  private void storedInput(byte[] content) {
    TaskDO waiting = new TaskDO();
    waiting.setId(TASK_ID);
    waiting.setStatus(TaskStatus.WAITING);
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
            HEADERS,
            upload(content),
            () -> null,
            TaskType.KOL_IMPORT.maxRows());

    assertThat(id).isEqualTo(TASK_ID);
    ArgumentCaptor<TaskDO> task = ArgumentCaptor.forClass(TaskDO.class);
    verify(tasks).insert(task.capture());
    assertThat(task.getValue().getStatus()).isEqualTo(TaskStatus.WAITING);
    assertThat(task.getValue().getTaskType()).isEqualTo(TaskType.KOL_IMPORT);
    assertThat(task.getValue().getTotal()).isEqualTo(2);
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
  void shouldRejectBeforeStoring_whenFileIsInvalid() {
    byte[] content = excel.write(List.of("其他"), List.of(List.of("x")));

    assertThatThrownBy(
            () ->
                service.submitImport(
                    TaskType.KOL_IMPORT,
                    HEADERS,
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
                    HEADERS,
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
    storedInput(workbook(List.of(List.of("", "a"), List.of("", "b"))));
    List<List<String>> seen = new ArrayList<>();

    service.run(
        TASK_ID,
        HEADERS,
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
  void shouldFinishPartialWithErrorFile_whenSomeRowsFail() {
    storedInput(workbook(List.of(List.of("", "ok"), List.of("=cmd", "bad"))));
    ImportRowProcessor processor =
        cells -> cells.get(1).equals("bad") ? List.of("原因一", "原因二") : List.of();

    service.run(TASK_ID, HEADERS, () -> processor, TaskType.KOL_IMPORT.maxRows());

    assertThat(lastUpdate().getStatus()).isEqualTo(TaskStatus.PARTIAL);
    assertThat(lastUpdate().getDone()).isEqualTo(2);
    assertThat(lastUpdate().getFailed()).isEqualTo(1);
    TaskFileDO errorFile = inserted.getFirst();
    assertThat(errorFile.getKind()).isEqualTo(TaskFileKind.ERROR);
    assertThat(errorFile.getTaskId()).isEqualTo(TASK_ID);
    assertThat(errorFile.getFileName()).isEqualTo("task-42-errors.xlsx");
    List<Map<Integer, String>> rows = readBack(errorFile.getContent());
    assertThat(rows).hasSize(2);
    assertThat(rows.get(0)).containsEntry(0, "编号").containsEntry(2, "错误原因");
    assertThat(rows.get(1))
        .containsEntry(0, "'=cmd")
        .containsEntry(1, "bad")
        .containsEntry(2, "原因一；原因二");
  }

  @Test
  void shouldFinishFailed_whenEveryRowFails() {
    storedInput(workbook(List.of(List.of("", "a"))));

    service.run(TASK_ID, HEADERS, () -> cells -> List.of("错误"), TaskType.KOL_IMPORT.maxRows());

    assertThat(lastUpdate().getStatus()).isEqualTo(TaskStatus.FAILED);
    assertThat(lastUpdate().getMessage()).isEqualTo(ImportTaskService.MESSAGE_ALL_FAILED);
    assertThat(inserted).hasSize(1);
  }

  @Test
  void shouldCountRowAsFailed_whenProcessorThrows() {
    storedInput(workbook(List.of(List.of("", "a"), List.of("", "b"))));
    ImportRowProcessor processor =
        cells -> {
          if (cells.get(1).equals("a")) {
            throw new IllegalStateException("boom");
          }
          return null;
        };

    service.run(TASK_ID, HEADERS, () -> processor, TaskType.KOL_IMPORT.maxRows());

    assertThat(lastUpdate().getStatus()).isEqualTo(TaskStatus.PARTIAL);
    assertThat(readBack(inserted.getFirst().getContent()).get(1))
        .containsEntry(2, ImportTaskService.ROW_ERROR);
  }

  @Test
  void shouldWriteProgressEveryHundredRows_whenProcessing() {
    List<List<String>> rows = new ArrayList<>();
    for (int i = 0; i < 250; i++) {
      rows.add(List.of("", "n" + i));
    }
    storedInput(workbook(rows));

    service.run(TASK_ID, HEADERS, () -> cells -> List.of(), TaskType.KOL_IMPORT.maxRows());

    // RUNNING, progress at 100 and 200, final SUCCESS.
    verify(tasks, times(4)).updateById(any(TaskDO.class));
    assertThat(updates.get(1).getDone()).isEqualTo(100);
    assertThat(updates.get(2).getDone()).isEqualTo(200);
    assertThat(lastUpdate().getDone()).isEqualTo(250);
  }

  @Test
  void shouldFailTask_whenStoredFileCannotBeParsed() {
    storedInput(new byte[] {0x50, 0x4B, 0x03, 0x04, 9, 9});

    service.run(TASK_ID, HEADERS, () -> cells -> List.of(), TaskType.KOL_IMPORT.maxRows());

    assertThat(lastUpdate().getStatus()).isEqualTo(TaskStatus.FAILED);
    assertThat(lastUpdate().getMessage()).isEqualTo(ImportTaskService.MESSAGE_UNREADABLE);
  }

  @Test
  void shouldFailTask_whenUnexpectedErrorOccurs() {
    storedInput(workbook(List.of(List.of("", "a"))));

    service.run(
        TASK_ID,
        HEADERS,
        () -> {
          throw new IllegalStateException("cannot create processor");
        },
        TaskType.KOL_IMPORT.maxRows());

    assertThat(lastUpdate().getStatus()).isEqualTo(TaskStatus.FAILED);
    assertThat(lastUpdate().getMessage()).isEqualTo(ImportTaskService.MESSAGE_UNEXPECTED);
  }

  @Test
  void shouldFailTask_whenInputFileIsMissing() {
    storedInput(workbook(List.of(List.of("", "a"))));
    when(files.selectOne(any())).thenReturn(null);

    service.run(TASK_ID, HEADERS, () -> cells -> List.of(), TaskType.KOL_IMPORT.maxRows());

    assertThat(lastUpdate().getMessage()).isEqualTo(ImportTaskService.MESSAGE_UNEXPECTED);
  }

  @Test
  void shouldSkip_whenTaskIsNoLongerWaiting() {
    TaskDO failed = new TaskDO();
    failed.setId(TASK_ID);
    failed.setStatus(TaskStatus.FAILED);
    when(tasks.selectById(TASK_ID)).thenReturn(failed);

    service.run(TASK_ID, HEADERS, () -> cells -> List.of(), TaskType.KOL_IMPORT.maxRows());

    verify(tasks, never()).updateById(any(TaskDO.class));
  }

  @Test
  void shouldProduceHeaderOnlyWorkbook_whenTemplateRequested() {
    List<Map<Integer, String>> rows = readBack(service.template(HEADERS));
    assertThat(rows).singleElement().satisfies(r -> assertThat(r).containsEntry(1, "姓名"));
  }
}
