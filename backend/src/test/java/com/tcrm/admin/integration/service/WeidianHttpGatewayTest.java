package com.tcrm.admin.integration.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.util.LinkedHashMap;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;
import tools.jackson.databind.json.JsonMapper;

class WeidianHttpGatewayTest {
  @Test
  void shouldPostUrlEncodedPublicAndParamJson() {
    RestTemplate http = new RestTemplate();
    MockRestServiceServer server = MockRestServiceServer.bindTo(http).build();
    server
        .expect(requestTo("https://api.example.test/api"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_FORM_URLENCODED))
        .andExpect(
            content()
                .string(
                    org.hamcrest.Matchers.allOf(
                        org.hamcrest.Matchers.containsString("public=%7B"),
                        org.hamcrest.Matchers.containsString("param=%7B"),
                        org.hamcrest.Matchers.containsString("%26"),
                        org.hamcrest.Matchers.not(
                            org.hamcrest.Matchers.containsString("name&value")))))
        .andRespond(
            withSuccess(
                "{\"status\":{\"status_code\":0},\"result\":{}}", MediaType.APPLICATION_JSON));
    WeidianHttpGateway gateway =
        new WeidianHttpGateway(
            http,
            JsonMapper.builder().build(),
            "https://api.example.test/api",
            "https://oauth.example.test");
    LinkedHashMap<String, String> publicValues = new LinkedHashMap<>();
    publicValues.put("method", "vdian.item.get");
    publicValues.put("access_token", "token-value");
    publicValues.put("format", "json");
    publicValues.put("version", "1.1");

    var response =
        gateway.apiPost(
            publicValues.get("method"),
            publicValues.get("version"),
            publicValues.get("access_token"),
            "{\"name&value\":\"中文\"}");

    assertThat(response.path("status").path("status_code").intValue()).isZero();
    server.verify();
  }
}
