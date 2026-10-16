package com.tcrm.admin.kol.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.tcrm.admin.kol.mapper.KolMapper;
import com.tcrm.admin.kol.model.KolDO;
import com.tcrm.admin.security.api.AccountDTO;
import com.tcrm.admin.security.api.CurrentUserContext;
import com.tcrm.admin.security.api.DataScope;
import com.tcrm.admin.security.api.Role;
import com.tcrm.admin.system.api.BdUserApi;
import com.tcrm.admin.task.api.ImportFileDTO;
import com.tcrm.admin.task.api.ImportRowProcessor;
import com.tcrm.admin.task.api.ImportTaskApi;
import com.tcrm.admin.task.api.TaskType;
import java.util.List;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class KolImportServiceTest {
  private final ImportTaskApi imports = mock(ImportTaskApi.class);
  private final CurrentUserContext current = mock(CurrentUserContext.class);
  private final KolMapper kols = mock(KolMapper.class);
  private final KolWriter writer = mock(KolWriter.class);
  private final BdUserApi bdUsers = mock(BdUserApi.class);
  private final KolImportService service =
      new KolImportService(imports, current, kols, writer, bdUsers);
  private final ImportFileDTO file = new ImportFileDTO("k.xlsx", "x", new byte[] {1});

  private void actAs(long id, Role role, DataScope scope) {
    when(current.user())
        .thenReturn(
            new AccountDTO(
                id, "u", null, "U", null, role, scope, 1, null, null, 0, null, null, true));
  }

  @Test
  void shouldBuildTemplateFromColumnDefinition() {
    when(imports.template(KolImportColumn.headerKeys())).thenReturn(new byte[] {7});
    assertThat(service.template()).containsExactly(7);
  }

  @Test
  @SuppressWarnings("unchecked")
  void shouldCaptureBdSubmitter_whenBdImports() {
    actAs(5L, Role.STAFF, DataScope.OWN);
    when(imports.submitImport(any(), any(), any(), any(), anyInt())).thenReturn(42L);
    assertThat(service.submit(file)).isEqualTo(42L);
    ArgumentCaptor<Supplier<ImportRowProcessor>> processor =
        ArgumentCaptor.forClass(Supplier.class);
    verify(imports)
        .submitImport(
            eq(TaskType.KOL_IMPORT),
            eq(KolImportColumn.headerKeys()),
            eq(file),
            processor.capture(),
            eq(TaskType.KOL_IMPORT.maxRows()));
    List<String> row = new java.util.ArrayList<>(List.of("", "A", "a@x.io", "US", "ghost"));
    while (row.size() < KolImportColumn.values().length) {
      row.add("");
    }
    assertThat(processor.getValue().get().process(row)).isEmpty();
    var kol = ArgumentCaptor.forClass(KolDO.class);
    verify(writer).insert(kol.capture(), eq(null), eq(5L));
    assertThat(kol.getValue().getBdUserId()).isEqualTo(5L);
    verifyNoInteractions(bdUsers);
  }

  @Test
  void shouldSubmit_whenStaffWithAllScopeImports() {
    actAs(1L, Role.STAFF, DataScope.ALL);
    when(imports.submitImport(any(), any(), any(), any(), anyInt())).thenReturn(7L);
    assertThat(service.submit(file)).isEqualTo(7L);
  }
}
