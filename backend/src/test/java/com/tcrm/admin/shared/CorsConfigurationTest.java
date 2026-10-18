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
  /** Case-insensitive containment: the allow-headers echo repeats the request's spelling. */
  private static org.hamcrest.Matcher<String> containsIgnoreCase(String expected) {
    return new org.hamcrest.TypeSafeMatcher<>() {
      @Override
      protected boolean matchesSafely(String item) {
        return item.toLowerCase(java.util.Locale.ROOT)
            .contains(expected.toLowerCase(java.util.Locale.ROOT));
      }

      @Override
      public void describeTo(org.hamcrest.Description description) {
        description.appendText("a string containing (ignoring case) ").appendValue(expected);
      }
    };
  }

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

    @org.springframework.web.bind.annotation.PostMapping(
        "/admin-api/kol/kols/{id}/promo-sheet-uploads")
    public String upload() {
      return "upload";
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

  /**
   * The promo-sheet upload (task 26 review) is served on its own sub-domain, so the browser
   * preflights it from the console's origin: the {@code /admin-api/**} mapping must cover the
   * upload path and allow exactly the headers the console sends.
   */
  @Test
  void shouldPreflightTheUploadPath_withTheConsoleHeaders() throws Exception {
    try (var context =
        new org.springframework.web.context.support.AnnotationConfigWebApplicationContext()) {
      context.setServletContext(new org.springframework.mock.web.MockServletContext());
      context.register(WebConfiguration.class);
      context.refresh();
      var mvc = MockMvcBuilders.webAppContextSetup(context).build();
      mvc.perform(
              options("/admin-api/kol/kols/5/promo-sheet-uploads")
                  .header("Origin", "https://example.com")
                  .header("Access-Control-Request-Method", "POST")
                  .header(
                      "Access-Control-Request-Headers",
                      "x-admin-token, x-company-id, content-type"))
          .andExpect(status().isOk())
          .andExpect(header().string("Access-Control-Allow-Origin", "https://example.com"))
          .andExpect(
              header()
                  .string(
                      "Access-Control-Allow-Methods", org.hamcrest.Matchers.containsString("POST")))
          // Spring echoes the header names as the browser declared them (lowercase here).
          .andExpect(
              header()
                  .string(
                      "Access-Control-Allow-Headers",
                      org.hamcrest.Matchers.allOf(
                          containsIgnoreCase("x-admin-token"),
                          containsIgnoreCase("x-company-id"),
                          containsIgnoreCase("content-type"))));
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
        }) {
      assertThatThrownBy(() -> new CorsConfiguration(value))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }
}
