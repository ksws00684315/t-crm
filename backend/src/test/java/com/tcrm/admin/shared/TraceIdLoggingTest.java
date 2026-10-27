package com.tcrm.admin.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The traceId shown in an error response must be greppable in the logs, on a line that also carries
 * the root-cause stack trace (#106 problem 2). Uses a minimal context (no datasource): only the
 * trace filter, the advice and one controller that always throws.
 */
@SpringBootTest(
    classes = {
      TraceIdLoggingTest.MvcConfig.class,
      TraceIdLoggingTest.BoomController.class,
      RequestTraceFilter.class,
      GlobalExceptionHandler.class
    },
    webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class TraceIdLoggingTest {
  @Autowired MockMvc mvc;
  private final ObjectMapper json = new ObjectMapper();

  @Configuration(proxyBeanMethods = false)
  @EnableWebMvc
  static class MvcConfig {}

  @RestController
  static class BoomController {
    @GetMapping("/boom")
    public String boom() {
      throw new IllegalStateException("root cause boom");
    }
  }

  @Test
  void shouldLogTheResponseTraceId_withTheRootCauseStack(CapturedOutput output) throws Exception {
    MvcResult result = mvc.perform(get("/boom")).andReturn();
    JsonNode body = json.readTree(result.getResponse().getContentAsString());
    String traceId = body.path("traceId").asText();
    assertThat(traceId).isNotBlank();
    // The exact traceId is greppable on the ERROR line ...
    assertThat(output).contains(traceId);
    assertThat(output).contains("Unhandled request failure");
    // ... and that line carries the full stack down to the root cause.
    assertThat(output).contains("IllegalStateException: root cause boom");
    assertThat(output).contains("at com.tcrm.admin.shared.TraceIdLoggingTest$BoomController.boom");
  }
}
