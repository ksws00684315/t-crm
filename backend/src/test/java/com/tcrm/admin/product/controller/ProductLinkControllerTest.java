package com.tcrm.admin.product.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tcrm.admin.AdminMvcSupport;
import com.tcrm.admin.product.service.ProductLinkRateLimiter;
import com.tcrm.admin.product.service.ProductLinkService;
import com.tcrm.admin.product.service.ProductLinkService.ConvertResult;
import com.tcrm.admin.product.service.ProductLinkService.JobItemView;
import com.tcrm.admin.product.service.ProductLinkService.JobView;
import com.tcrm.admin.product.service.ProductLinkService.LinkTemplateView;
import com.tcrm.admin.product.service.ProductLinkService.ResolveItemView;
import com.tcrm.admin.product.service.ProductLinkService.ResolveResult;
import com.tcrm.admin.shared.ClientIpResolver;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Contract checks aligned with Apps Script 3.0.5: field names, lowercase statuses, string IDs, and
 * resolve items matching request order/count.
 */
class ProductLinkControllerTest {
  private static final String BASE = "/admin-api/external/product-links";
  private final ProductLinkService links = mock(ProductLinkService.class);
  private final ProductLinkRateLimiter rates = mock(ProductLinkRateLimiter.class);
  private final ClientIpResolver ips = mock(ClientIpResolver.class);
  private MockMvc mvc;

  @BeforeEach
  void setup() {
    when(ips.resolve(any(HttpServletRequest.class))).thenReturn("127.0.0.1");
    mvc = AdminMvcSupport.buildPublic(new ProductLinkController(links, rates, ips));
  }

  @Test
  void shouldReturnTemplates_withTcrmRelayFirst() throws Exception {
    when(links.templates("WEIDIAN"))
        .thenReturn(
            List.of(
                new LinkTemplateView(
                    "tcrm-relay",
                    "TCRM_RELAY",
                    "T-CRM 中转",
                    "https://t.example/i/{PRODUCT_ID}",
                    "WEIDIAN",
                    0),
                new LinkTemplateView(
                    "1",
                    "kakobuy",
                    "Kakobuy",
                    "https://kakobuy.example/{PRODUCT_ID}",
                    "WEIDIAN",
                    1)));
    mvc.perform(get(BASE + "/templates").param("channelCode", "WEIDIAN"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(0))
        .andExpect(jsonPath("$.data[0].templateCode").value("TCRM_RELAY"))
        .andExpect(jsonPath("$.data[0].templateUrl").value("https://t.example/i/{PRODUCT_ID}"))
        .andExpect(jsonPath("$.data[1].templateCode").value("kakobuy"));
    verify(rates).checkIp("127.0.0.1");
  }

  @Test
  void shouldConvert_likeScript_withCallerPlatformAndJobCode() throws Exception {
    when(links.convert(any())).thenReturn(new ConvertResult("AbCdEfGhIjKlMnOpQrStUv"));
    mvc.perform(
            post(BASE + "/convert")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"token":"TKN-ABCDEFGHIJKLMNOPQRSTUV","callerPlatform":"google_sheet_plugin",
                     "items":[{"sourceChannelCode":"WEIDIAN","sourceProductId":"123","ruleId":"r1"}]}
                    """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.jobCode").value("AbCdEfGhIjKlMnOpQrStUv"));
  }

  @Test
  void shouldResolve_matchingRequestOrderAndStatuses() throws Exception {
    when(links.resolve(eq("job1"), any()))
        .thenReturn(
            new ResolveResult(
                "job1",
                "ready",
                "1720000000000",
                List.of(
                    new ResolveItemView(
                        "WEIDIAN", "111", "success", "WEIDIAN", "999", "10001", null),
                    new ResolveItemView(
                        "WEIDIAN", "222", "already_applied", null, null, null, null),
                    new ResolveItemView("WEIDIAN", "333", "failed", null, null, null, "额度不足"),
                    new ResolveItemView("WEIDIAN", "444", "not_found", null, null, null, null),
                    new ResolveItemView(
                        "WEIDIAN", "555", "conflict", null, null, null, "多个店铺都有该商品"))));
    mvc.perform(
            post(BASE + "/jobs/job1/items/resolve")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"token":"TKN-ABCDEFGHIJKLMNOPQRSTUV","items":[
                      {"sourceChannelCode":"WEIDIAN","sourceProductId":"111"},
                      {"sourceChannelCode":"WEIDIAN","sourceProductId":"222"},
                      {"sourceChannelCode":"WEIDIAN","sourceProductId":"333"},
                      {"sourceChannelCode":"WEIDIAN","sourceProductId":"444"},
                      {"sourceChannelCode":"WEIDIAN","sourceProductId":"555"}
                    ]}
                    """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.jobCode").value("job1"))
        .andExpect(jsonPath("$.data.status").value("ready"))
        .andExpect(jsonPath("$.data.resultVersion").value("1720000000000"))
        .andExpect(jsonPath("$.data.items.length()").value(5))
        .andExpect(jsonPath("$.data.items[0].status").value("success"))
        .andExpect(jsonPath("$.data.items[0].targetExternalProductId").value("999"))
        .andExpect(jsonPath("$.data.items[0].targetStoreId").value("10001"))
        .andExpect(jsonPath("$.data.items[0].targetChannelCode").value("WEIDIAN"))
        .andExpect(jsonPath("$.data.items[1].status").value("already_applied"))
        .andExpect(jsonPath("$.data.items[2].status").value("failed"))
        .andExpect(jsonPath("$.data.items[3].status").value("not_found"))
        .andExpect(jsonPath("$.data.items[4].status").value("conflict"));
  }

  @Test
  void shouldReturnJobAndItems_withLowercaseStatuses() throws Exception {
    when(links.getJob("job1")).thenReturn(new JobView("job1", "completed", 2, 1, 1));
    when(links.listItems("job1"))
        .thenReturn(
            List.of(
                new JobItemView("WEIDIAN", "1", "success", "WEIDIAN", "9", "100", null),
                new JobItemView("WEIDIAN", "2", "failed", null, null, null, "不支持的来源渠道")));
    mvc.perform(get(BASE + "/jobs/job1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.status").value("completed"))
        .andExpect(jsonPath("$.data.totalCount").value(2))
        .andExpect(jsonPath("$.data.successCount").value(1))
        .andExpect(jsonPath("$.data.failCount").value(1));
    mvc.perform(get(BASE + "/jobs/job1/items"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data[0].targetExternalProductId").value("9"))
        .andExpect(jsonPath("$.data[1].errorMessage").value("不支持的来源渠道"));
  }
}
