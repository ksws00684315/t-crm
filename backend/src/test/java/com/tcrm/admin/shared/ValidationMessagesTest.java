package com.tcrm.admin.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.Validator;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class ValidationMessagesTest {
  private final Locale originalDefault = Locale.getDefault();
  private final ApplicationContextRunner context =
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration.class))
          .withBean(
              "validationEnglish",
              org.springframework.boot.validation.autoconfigure.ValidationConfigurationCustomizer
                  .class,
              () -> new CommonConfiguration().englishValidationMessages());

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
    return MockMvcBuilders.standaloneSetup(new UserController(mock(UserService.class)))
        .setControllerAdvice(new GlobalExceptionHandler())
        .setValidator(validator)
        .setMessageConverters(new JacksonJsonHttpMessageConverter(JsonMapper.builder().build()))
        .build();
  }

  @Test
  void shouldKeepEnglishMessages_whenServerAndRequestLocalesAreChinese() {
    context.run(
        ctx -> {
          var validator = ctx.getBean(Validator.class);
          var errors = new org.springframework.validation.BeanPropertyBindingResult(this, "target");
          validator.validate(
              new com.tcrm.admin.system.controller.CreateUserRequest(
                  null, "short", null, "not-an-email", null, null),
              errors);
          var messages = errors.getFieldErrors().stream().map(e -> e.getDefaultMessage()).toList();
          assertThat(messages).isNotEmpty();
          assertThat(messages).allSatisfy(message -> assertThat(message).matches("[\\x20-\\x7E]+"));
          assertThat(messages).contains("must not be null", "must be a well-formed email address");
        });
  }

  @Test
  void shouldReturnEnglishSortedFieldErrors_whenRequestAsksForChinese() {
    context.run(
        ctx -> {
          var result =
              mvc(ctx.getBean(Validator.class))
                  .perform(
                      post("/admin-api/system/users")
                          .header("Accept-Language", "zh-CN")
                          .locale(Locale.SIMPLIFIED_CHINESE)
                          .contentType("application/json")
                          .content("{\"password\":\"short\",\"email\":\"bad\"}"))
                  .andExpect(status().isBadRequest())
                  .andReturn();
          JsonNode body =
              JsonMapper.builder().build().readTree(result.getResponse().getContentAsString());
          List<String> fields =
              body.get("data").valueStream().map(n -> n.get("field").asString()).toList();
          assertThat(fields).isSorted().hasSizeGreaterThan(3);
          body.get("data")
              .forEach(n -> assertThat(n.get("message").asString()).matches("[\\x20-\\x7E]+"));
        });
  }
}
