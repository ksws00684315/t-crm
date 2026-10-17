package com.tcrm.admin.shared;

import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tcrm.admin.AdminMvcSupport;
import com.tcrm.admin.security.api.CurrentUserContext;
import com.tcrm.admin.security.api.DataScope;
import com.tcrm.admin.security.api.Role;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Request-format errors must map to 4xx envelopes (task 23 §4.2), not the catch-all 500. */
class GlobalExceptionHandlerRequestFormatTest {
  private final CurrentUserContext current = mock(CurrentUserContext.class);
  private final MockMvc mvc = AdminMvcSupport.build(current, new ProbeController());

  @Test
  void shouldReturnUnsupportedMediaType_whenJsonEndpointReceivesTextPlain() throws Exception {
    String token = AdminMvcSupport.actAs(current, 1L, Role.SUPER_ADMIN, DataScope.ALL);
    mvc.perform(
            post("/admin-api/probe/echo")
                .header(AdminMvcSupport.TOKEN_HEADER, token)
                .contentType(MediaType.TEXT_PLAIN)
                .content("not-json"))
        .andExpect(status().isUnsupportedMediaType())
        .andExpect(jsonPath("$.errorCode").value("COMMON_UNSUPPORTED_MEDIA_TYPE"));
  }

  @Test
  void shouldReturnValidationFailed_whenJsonBodyIsMalformed() throws Exception {
    String token = AdminMvcSupport.actAs(current, 1L, Role.SUPER_ADMIN, DataScope.ALL);
    mvc.perform(
            post("/admin-api/probe/echo")
                .header(AdminMvcSupport.TOKEN_HEADER, token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errorCode").value("COMMON_VALIDATION_FAILED"));
  }

  @Test
  void shouldReturnValidationFailed_whenRequiredParameterMissing() throws Exception {
    String token = AdminMvcSupport.actAs(current, 1L, Role.SUPER_ADMIN, DataScope.ALL);
    mvc.perform(get("/admin-api/probe/sum").header(AdminMvcSupport.TOKEN_HEADER, token))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errorCode").value("COMMON_VALIDATION_FAILED"));
  }

  @Test
  void shouldReturnValidationFailed_whenParameterTypeMismatches() throws Exception {
    String token = AdminMvcSupport.actAs(current, 1L, Role.SUPER_ADMIN, DataScope.ALL);
    mvc.perform(
            get("/admin-api/probe/sum")
                .param("n", "abc")
                .header(AdminMvcSupport.TOKEN_HEADER, token))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errorCode").value("COMMON_VALIDATION_FAILED"));
  }

  @RestController
  @RequestMapping("/admin-api/probe")
  static class ProbeController {
    @PostMapping(value = "/echo", consumes = MediaType.APPLICATION_JSON_VALUE)
    public R<String> echo(@RequestBody Echo body) {
      return R.ok(body.text());
    }

    @GetMapping("/sum")
    public R<Integer> sum(@RequestParam @Min(1) int n) {
      return R.ok(n);
    }

    record Echo(@NotBlank String text) {}
  }
}
