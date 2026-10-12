package com.tcrm.admin.product.controller;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tcrm.admin.AdminMvcSupport;
import com.tcrm.admin.product.service.MappingImportService;
import com.tcrm.admin.product.service.ProductImportService;
import com.tcrm.admin.security.api.CurrentUserContext;
import com.tcrm.admin.security.api.DataScope;
import com.tcrm.admin.security.api.Role;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.test.web.servlet.MockMvc;

/** Template downloads follow the request language for the file name (task 06d §4.3). */
class ImportTemplateDownloadTest {
  private final CurrentUserContext current = mock(CurrentUserContext.class);
  private final ProductImportService products = mock(ProductImportService.class);
  private final MappingImportService mappings = mock(MappingImportService.class);
  private final ResourceBundleMessageSource messages = new ResourceBundleMessageSource();
  private final MockMvc mvc;

  {
    messages.setBasenames("messages");
    messages.setDefaultEncoding("UTF-8");
    messages.setFallbackToSystemLocale(false);
    mvc =
        AdminMvcSupport.build(
            current,
            new ProductImportController(products, messages),
            new KolItemImportController(mappings, messages));
  }

  @ParameterizedTest
  @CsvSource(
      value = {
        "/admin-api/product/product-imports/template|en-US|product-import-template.xlsx",
        "/admin-api/product/kol-item-imports/template|en|kol-item-import-template.xlsx",
        "/admin-api/product/product-imports/template|zh-CN|原商品导入模板.xlsx",
        "/admin-api/product/kol-item-imports/template|zh|对应关系导入模板.xlsx"
      },
      delimiter = '|')
  void shouldNameTheTemplateInRequestLanguage(String path, String language, String name)
      throws Exception {
    when(products.template()).thenReturn(new byte[] {'P', 'K'});
    when(mappings.template()).thenReturn(new byte[] {'P', 'K'});
    String token = AdminMvcSupport.actAs(current, 1L, Role.SUPER_ADMIN, DataScope.ALL);
    String expected = name.matches("\\w+.xlsx") ? name : encode(name);
    mvc.perform(
            get(path)
                .header(AdminMvcSupport.TOKEN_HEADER, token)
                .header("Accept-Language", language))
        .andExpect(status().isOk())
        .andExpect(
            header().string("Content-Disposition", org.hamcrest.Matchers.containsString(expected)));
  }

  private static String encode(String name) {
    return URLEncoder.encode(name, StandardCharsets.UTF_8);
  }
}
