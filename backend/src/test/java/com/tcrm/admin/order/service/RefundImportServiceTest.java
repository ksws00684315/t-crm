package com.tcrm.admin.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tcrm.admin.product.api.ShopAccessApi;
import com.tcrm.admin.shared.BusinessException;
import com.tcrm.admin.shared.ErrorCode;
import com.tcrm.admin.task.api.ImportFileDTO;
import com.tcrm.admin.task.api.ImportTaskApi;
import com.tcrm.admin.task.api.TaskType;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class RefundImportServiceTest {
  private final ImportTaskApi imports = mock(ImportTaskApi.class);
  private final ShopAccessApi shops = mock(ShopAccessApi.class);
  private final OrderWriter writer = mock(OrderWriter.class);
  private final OrderImportStateWriter importStates = mock(OrderImportStateWriter.class);
  private final OrderSyncFailureWriter syncFailures = mock(OrderSyncFailureWriter.class);
  private final RefundImportService service =
      new RefundImportService(imports, shops, writer, importStates, syncFailures);

  @Test
  void shouldSubmitRefundImportWithCutoffParams() {
    Instant cutoff = Instant.parse("2026-10-21T08:00:00Z");
    when(imports.submitImport(
            eq(TaskType.REFUND_IMPORT),
            anyList(),
            anyList(),
            any(),
            any(),
            anyInt(),
            eq(OrderImportService.params(cutoff))))
        .thenReturn(55L);
    Long id =
        service.submit(
            new ImportFileDTO("refunds.xlsx", ImportTaskApi.XLSX_CONTENT_TYPE, new byte[] {1}),
            cutoff);
    assertThat(id).isEqualTo(55L);
    verify(imports)
        .submitImport(
            eq(TaskType.REFUND_IMPORT),
            anyList(),
            anyList(),
            any(ImportFileDTO.class),
            any(),
            anyInt(),
            eq("{\"dataCutoffTime\":\"2026-10-21T08:00:00Z\"}"));
  }

  @Test
  void shouldDelegateTemplateBytes() {
    when(imports.template(anyList(), any())).thenReturn(new byte[] {8});
    assertThat(service.template()).containsExactly(8);
  }

  @Test
  void shouldRejectFutureCutoff() {
    Instant future = Instant.now().plusSeconds(3600);
    assertThatThrownBy(
            () ->
                service.submit(
                    new ImportFileDTO(
                        "refunds.xlsx", ImportTaskApi.XLSX_CONTENT_TYPE, new byte[] {1}),
                    future))
        .isInstanceOf(BusinessException.class)
        .extracting(e -> ((BusinessException) e).getErrorCode())
        .isEqualTo(ErrorCode.COMMON_VALIDATION_FAILED);
  }
}
