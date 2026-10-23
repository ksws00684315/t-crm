package com.tcrm.admin.product.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tcrm.admin.KolMvcSupport;
import com.tcrm.admin.security.api.CurrentKolContext;
import com.tcrm.admin.security.api.KolAccountDTO;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;

class KolPortalItemControllerTest {
  private final CurrentKolContext current = org.mockito.Mockito.mock(CurrentKolContext.class);
  private final MockMvc mvc = KolMvcSupport.build(current, new KolPortalItemController());

  @Test
  void requiresCreatorSession() throws Exception {
    mvc.perform(get("/kol-api/items")).andExpect(status().isUnauthorized());
  }

  @Test
  void hidesRetiredPromotionListForSignedInCreator() throws Exception {
    KolAccountDTO account =
        new KolAccountDTO(
            21L,
            "KOL-ABC123",
            "Creator",
            "creator@example.test",
            "US",
            "hash",
            1,
            1,
            0,
            Instant.EPOCH,
            7L,
            "Company",
            true);
    String token = KolMvcSupport.actAs(current, account);

    mvc.perform(get("/kol-api/items").header(KolMvcSupport.TOKEN_HEADER, token))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.errorCode").value("COMMON_NOT_FOUND"));
  }
}
