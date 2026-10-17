package com.tcrm.admin.kol.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tcrm.admin.kol.model.CopyTokenDisplayStatus;
import com.tcrm.admin.kol.service.CopyTokenRowDTO;
import com.tcrm.admin.kol.service.CopyTokenService;
import com.tcrm.admin.kol.service.CreatedCopyTokenDTO;
import com.tcrm.admin.shared.PageResult;
import com.tcrm.admin.shared.R;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class CopyTokenControllerTest {
  private final CopyTokenService tokens = mock(CopyTokenService.class);
  private final CopyTokenController controller = new CopyTokenController(tokens);

  @Test
  void shouldDelegateListCreateRevealRevoke() {
    CopyTokenRowDTO row =
        new CopyTokenRowDTO(
            1L,
            "TKN-AAAA****ZZZZ",
            10L,
            "KOL1",
            "Alice",
            "WEIDIAN",
            null,
            null,
            0,
            1000,
            1440,
            Instant.parse("2026-10-18T12:00:00Z"),
            CopyTokenDisplayStatus.ACTIVE,
            null,
            Instant.parse("2026-10-17T12:00:00Z"));
    when(tokens.page(any())).thenReturn(new PageResult<>(List.of(row), 1, 1, 20));
    when(tokens.create(any())).thenReturn(new CreatedCopyTokenDTO("TKN-" + "A".repeat(26), row));
    when(tokens.reveal(1L)).thenReturn("TKN-" + "A".repeat(26));

    R<PageResult<CopyTokenVO>> list =
        controller.list(new CopyTokenQuery(null, null, null, null, 1, 20));
    assertThat(list.data().items()).hasSize(1);
    assertThat(list.data().items().get(0).tokenHint()).isEqualTo("TKN-AAAA****ZZZZ");

    R<CreatedCopyTokenVO> created =
        controller.create(new CreateCopyTokenRequest(10L, null, null, null));
    assertThat(created.data().token()).startsWith("TKN-");

    R<RevealCopyTokenVO> revealed = controller.reveal(1L);
    assertThat(revealed.data().token()).startsWith("TKN-");

    assertThat(controller.revoke(1L).data()).isNull();
    verify(tokens).revoke(eq(1L));
  }
}
