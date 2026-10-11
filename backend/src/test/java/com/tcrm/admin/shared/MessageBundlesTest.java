package com.tcrm.admin.shared;

import static org.assertj.core.api.Assertions.assertThat;

import com.tcrm.admin.kol.service.KolImportColumn;
import com.tcrm.admin.product.service.MappingImportColumn;
import com.tcrm.admin.product.service.ProductImportColumn;
import com.tcrm.admin.task.service.ImportTaskService;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.ResourceBundleMessageSource;

/**
 * The message bundles behind the request-language texts (design §2.4): the two languages carry
 * exactly the same keys, the base bundle equals the English one, and every key the import code
 * references exists in both languages.
 */
class MessageBundlesTest {
  /**
   * The row processors are package-private (by design), so they are picked up by name; the enums
   * and the task service are referenced directly.
   */
  private static List<Class<?>> importCode() {
    List<Class<?>> all =
        new ArrayList<>(
            List.of(
                KolImportColumn.class,
                ProductImportColumn.class,
                MappingImportColumn.class,
                ImportTaskService.class));
    for (String name :
        List.of(
            "com.tcrm.admin.kol.service.KolImportProcessor",
            "com.tcrm.admin.product.service.ProductImportProcessor",
            "com.tcrm.admin.product.service.MappingImportProcessor")) {
      try {
        all.add(Class.forName(name));
      } catch (ClassNotFoundException e) {
        throw new IllegalStateException(e);
      }
    }
    return all;
  }

  @Test
  void shouldHaveIdenticalKeys_inChineseAndEnglishBundles() throws IOException {
    assertThat(keys("messages_zh_CN.properties")).isEqualTo(keys("messages_en.properties"));
  }

  @Test
  void shouldHaveEnglishContent_inTheBaseBundle() throws IOException {
    Map<String, String> en = load("messages_en.properties");
    Map<String, String> base = load("messages.properties");
    assertThat(base).isEqualTo(en);
  }

  @Test
  void shouldHaveIdenticalKeys_inChineseAndEnglishValidationBundles() throws IOException {
    assertThat(keys("ValidationMessages_zh_CN.properties"))
        .isEqualTo(keys("ValidationMessages_en.properties"))
        .isEqualTo(keys("ValidationMessages.properties"));
    // The explicit _en bundle stops ResourceBundle from falling back to the system locale.
    assertThat(load("ValidationMessages_en.properties"))
        .isEqualTo(load("ValidationMessages.properties"));
  }

  @Test
  void shouldResolveEveryReferencedImportKey_inBothLanguages() throws Exception {
    Set<String> referenced = new HashSet<>();
    for (Class<?> type : importCode()) {
      for (Field field : type.getDeclaredFields()) {
        if (field.getType() != String.class || !Modifier.isStatic(field.getModifiers())) {
          continue;
        }
        field.setAccessible(true);
        String value = (String) field.get(null);
        if (value.startsWith("import.")) {
          referenced.add(value);
        }
      }
    }
    assertThat(referenced).isNotEmpty();
    ResourceBundleMessageSource messages = new ResourceBundleMessageSource();
    messages.setBasenames("messages");
    messages.setDefaultEncoding("UTF-8");
    messages.setFallbackToSystemLocale(false);
    for (Locale locale : List.of(Locale.SIMPLIFIED_CHINESE, Locale.ENGLISH)) {
      List<String> missing = new ArrayList<>();
      for (String key : referenced) {
        try {
          assertThat(messages.getMessage(key, null, locale)).isNotBlank();
        } catch (Exception e) {
          missing.add(key + "@" + locale);
        }
      }
      assertThat(missing).as("keys missing in %s", locale).isEmpty();
    }
  }

  @Test
  void shouldResolveTemplateFileNames_inBothLanguages() {
    ResourceBundleMessageSource messages = new ResourceBundleMessageSource();
    messages.setBasenames("messages");
    messages.setDefaultEncoding("UTF-8");
    messages.setFallbackToSystemLocale(false);
    List<String> nameKeys =
        List.of(
            "import.kol.template-name",
            "import.product.template-name",
            "import.mapping.template-name");
    for (Locale locale : List.of(Locale.SIMPLIFIED_CHINESE, Locale.ENGLISH)) {
      for (String key : nameKeys) {
        assertThat(messages.getMessage(key, null, locale)).endsWith(".xlsx");
      }
    }
  }

  private static List<String> keys(String resource) throws IOException {
    return load(resource).keySet().stream().sorted().toList();
  }

  private static Map<String, String> load(String resource) throws IOException {
    Properties properties = new Properties();
    try (InputStream in = MessageBundlesTest.class.getResourceAsStream("/" + resource)) {
      assertThat(in).as(resource).isNotNull();
      properties.load(in);
    }
    @SuppressWarnings("unchecked")
    Map<String, String> cast = (Map<String, String>) (Map<?, ?>) properties;
    return cast;
  }
}
