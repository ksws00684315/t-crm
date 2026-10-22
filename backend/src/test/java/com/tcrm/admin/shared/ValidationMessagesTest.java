package com.tcrm.admin.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tcrm.admin.kol.controller.CountryCode;
import com.tcrm.admin.security.api.Role;
import com.tcrm.admin.system.controller.CompanyRole;
import com.tcrm.admin.system.controller.UserController;
import com.tcrm.admin.system.service.UserService;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.Validator;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Field-level validation messages follow the request language (ADR-0.15): zh-CN for Chinese
 * requests, English for everything else — including a missing header while the server itself runs
 * with a Chinese default locale. The envelope {@code msg} stays English either way.
 */
class ValidationMessagesTest {
  /** A Chinese server default must never leak into English-only requests. */
  private final Locale originalDefault = Locale.getDefault();

  private final ApplicationContextRunner context =
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration.class))
          .withBean(
              "localeAware",
              org.springframework.boot.validation.autoconfigure.ValidationConfigurationCustomizer
                  .class,
              () -> new CommonConfiguration().localeAwareValidationMessages());

  @BeforeEach
  void useChineseServerLocale() {
    Locale.setDefault(Locale.SIMPLIFIED_CHINESE);
    LocaleContextHolder.setLocale(Locale.SIMPLIFIED_CHINESE);
  }

  @AfterEach
  void restoreLocale() {
    Locale.setDefault(originalDefault);
    LocaleContextHolder.resetLocaleContext();
  }

  private MockMvc mvc(Validator validator) {
    return MockMvcBuilders.standaloneSetup(
            new UserController(
                mock(UserService.class),
                mock(com.tcrm.admin.system.service.UserPermissionService.class),
                mock(com.tcrm.admin.system.service.DepartureService.class)))
        .setControllerAdvice(new GlobalExceptionHandler())
        .setValidator(validator)
        .setLocaleResolver(new SupportedLocaleResolver())
        .setMessageConverters(new JacksonJsonHttpMessageConverter(JsonMapper.builder().build()))
        .build();
  }

  private JsonNode body(String content) {
    return JsonMapper.builder().build().readTree(content);
  }

  private String fieldMessage(JsonNode body, String field) {
    return body.get("data")
        .valueStream()
        .filter(n -> field.equals(n.get("field").asString()))
        .map(n -> n.get("message").asString())
        .findFirst()
        .orElseThrow(() -> new AssertionError("no error for field " + field));
  }

  @Test
  void shouldInterpolateChineseFieldErrors_whenRequestAsksForChinese() {
    context.run(
        ctx -> {
          var result =
              mvc(ctx.getBean(Validator.class))
                  .perform(
                      post("/admin-api/system/users")
                          .header("Accept-Language", "zh-CN")
                          .contentType("application/json")
                          .content("{\"password\":\"short\",\"email\":\"bad\"}"))
                  .andExpect(status().isBadRequest())
                  .andReturn();
          JsonNode json = body(result.getResponse().getContentAsString());
          List<String> fields =
              json.get("data").valueStream().map(n -> n.get("field").asString()).toList();
          assertThat(fields).isSorted().hasSizeGreaterThan(3);
          json.get("data")
              .forEach(
                  n -> assertThat(n.get("message").asString()).matches(".*[\\u4e00-\\u9fff].*"));
          assertThat(fieldMessage(json, "username")).isEqualTo("不能为空");
          assertThat(fieldMessage(json, "password")).isEqualTo("个数必须在10和72之间");
          assertThat(fieldMessage(json, "email")).isEqualTo("不是一个合法的电子邮件地址");
          assertThat(fieldMessage(json, "role")).isEqualTo("不能为null");
          // The envelope message stays English; only the field messages are localized.
          assertThat(json.get("msg").asString()).matches("[\\x20-\\x7E]+");
        });
  }

  @Test
  void shouldInterpolateEnglishFieldErrors_whenRequestAsksForEnglishOrOmitsTheHeader() {
    context.run(
        ctx -> {
          for (String header : new String[] {"en-US", "en", "fr-FR", null}) {
            MockHttpServletRequestBuilder request =
                post("/admin-api/system/users")
                    .contentType("application/json")
                    .content("{\"password\":\"short\",\"email\":\"bad\"}");
            if (header != null) {
              request.header("Accept-Language", header);
            }
            RequestBuilder perform = request;
            var result =
                mvc(ctx.getBean(Validator.class))
                    .perform(perform)
                    .andExpect(status().isBadRequest())
                    .andReturn();
            JsonNode json = body(result.getResponse().getContentAsString());
            json.get("data")
                .forEach(n -> assertThat(n.get("message").asString()).matches("[\\x20-\\x7E]+"));
            assertThat(fieldMessage(json, "username")).isEqualTo("must not be blank");
            assertThat(fieldMessage(json, "email"))
                .isEqualTo("must be a well-formed email address");
            assertThat(fieldMessage(json, "role")).isEqualTo("must not be null");
          }
        });
  }

  @Test
  void shouldLocalizeCustomConstraintMessages_inBothLanguages() {
    record CountryProbe(@CountryCode String country) {}
    record RoleProbe(@CompanyRole Role role) {}
    context.run(
        ctx -> {
          var validator = ctx.getBean(Validator.class);
          for (Locale locale : List.of(Locale.SIMPLIFIED_CHINESE, Locale.ENGLISH)) {
            LocaleContextHolder.setLocale(locale);
            var country = new BeanPropertyBindingResult(new CountryProbe("ZZ"), "country");
            validator.validate(new CountryProbe("ZZ"), country);
            assertThat(country.getFieldErrors())
                .singleElement()
                .satisfies(
                    e ->
                        assertThat(e.getDefaultMessage())
                            .isEqualTo(
                                locale == Locale.SIMPLIFIED_CHINESE
                                    ? "必须是 ISO 3166-1 alpha-2 国家代码"
                                    : "must be an ISO 3166-1 alpha-2 country code"));
            var role = new BeanPropertyBindingResult(new RoleProbe(Role.PLATFORM_ADMIN), "role");
            validator.validate(new RoleProbe(Role.PLATFORM_ADMIN), role);
            assertThat(role.getFieldErrors())
                .singleElement()
                .satisfies(
                    e ->
                        assertThat(e.getDefaultMessage())
                            .isEqualTo(
                                locale == Locale.SIMPLIFIED_CHINESE
                                    ? "必须是公司内角色"
                                    : "must be a company role"));
          }
        });
  }
}
