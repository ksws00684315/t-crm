package com.tcrm.admin.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.config.annotation.CorsRegistry;

class CorsConfigurationTest {
  static class Registry extends CorsRegistry {
    Map<String, org.springframework.web.cors.CorsConfiguration> configurations() {
      return getCorsConfigurations();
    }
  }

  @RestController
  static class Endpoint {
    @GetMapping("/admin-api/test")
    public String get() {
      return "test";
    }
  }

  @Test
  void shouldRejectCrossOriginByDefault_whenWhitelistIsEmpty() {
    var registry = new Registry();
    new CorsConfiguration("").addCorsMappings(registry);
    assertThat(registry.configurations()).isEmpty();
  }

  @Test
  void shouldAllowOnlyExplicitOrigin_whenPreflightIsReceived() throws Exception {
    try (var context =
        new org.springframework.web.context.support.AnnotationConfigWebApplicationContext()) {
      context.setServletContext(new org.springframework.mock.web.MockServletContext());
      context.register(WebConfiguration.class);
      context.refresh();
      var mvc = MockMvcBuilders.webAppContextSetup(context).build();
      mvc.perform(
              options("/admin-api/test")
                  .header("Origin", "https://example.com")
                  .header("Access-Control-Request-Method", "GET")
                  .header("Access-Control-Request-Headers", "X-Admin-Token"))
          .andExpect(status().isOk())
          .andExpect(header().string("Access-Control-Allow-Origin", "https://example.com"));
      mvc.perform(
              options("/admin-api/test")
                  .header("Origin", "https://untrusted.example.com")
                  .header("Access-Control-Request-Method", "GET"))
          .andExpect(status().isForbidden());
    }
  }

  @org.springframework.context.annotation.Configuration
  @org.springframework.web.servlet.config.annotation.EnableWebMvc
  static class WebConfiguration {
    @org.springframework.context.annotation.Bean
    Endpoint endpoint() {
      return new Endpoint();
    }

    @org.springframework.context.annotation.Bean
    CorsConfiguration cors() {
      return new CorsConfiguration(" https://example.com , https://portal.example.com ");
    }
  }

  @Test
  void shouldRejectWildcardOrNonOriginValues_whenConfiguringCors() {
    for (String value :
        new String[] {
          "*",
          "https://*.example.com",
          "https://example.com/path",
          "https://user:credential@example.com",
          "not-an-origin",
          "https://[bad"
        })
      assertThatThrownBy(() -> new CorsConfiguration(value))
          .isInstanceOf(IllegalArgumentException.class);
  }
}
