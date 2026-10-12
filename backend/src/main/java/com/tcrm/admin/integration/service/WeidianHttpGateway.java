package com.tcrm.admin.integration.service;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Low-level Weidian OAuth and API HTTP protocol implementation. */
@Component
public class WeidianHttpGateway {
  private final RestTemplate http;
  private final ObjectMapper json;
  private final String apiBaseUrl;
  private final String oauthBaseUrl;

  public WeidianHttpGateway(
      RestTemplate weidianRestTemplate,
      ObjectMapper json,
      @Value("${crm.weidian.api-base-url}") String apiBaseUrl,
      @Value("${crm.weidian.oauth-base-url}") String oauthBaseUrl) {
    this.http = weidianRestTemplate;
    this.json = json;
    this.apiBaseUrl = apiBaseUrl;
    this.oauthBaseUrl = oauthBaseUrl;
  }

  public JsonNode oauthGet(String path, Map<String, String> query) {
    URI uri = oauthUri(path, query);
    String body;
    try {
      body = http.getForObject(uri, String.class);
    } catch (HttpServerErrorException e) {
      throw new WeidianTransportException(true);
    } catch (ResourceAccessException e) {
      throw new WeidianTransportException(true);
    } catch (RestClientException e) {
      throw new WeidianTransportException(false);
    }
    return parse(body);
  }

  public JsonNode apiPost(String method, String version, String accessToken, String paramJson) {
    LinkedHashMap<String, String> publicValues = new LinkedHashMap<>();
    publicValues.put("method", method);
    publicValues.put("access_token", accessToken);
    publicValues.put("format", "json");
    publicValues.put("version", version);
    MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
    form.add("public", stringify(publicValues));
    form.add("param", paramJson);
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
    String body;
    try {
      body = http.postForObject(apiBaseUrl, new HttpEntity<>(form, headers), String.class);
    } catch (HttpServerErrorException e) {
      throw new WeidianTransportException(true);
    } catch (ResourceAccessException e) {
      throw new WeidianTransportException(true);
    } catch (RestClientException e) {
      throw new WeidianTransportException(false);
    }
    return parse(body);
  }

  private URI oauthUri(String path, Map<String, String> query) {
    UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(oauthBaseUrl + path);
    query.forEach(builder::queryParam);
    return builder.build().encode().toUri();
  }

  private JsonNode parse(String body) {
    try {
      return json.readTree(body == null ? "{}" : body);
    } catch (JacksonException e) {
      throw new WeidianTransportException(false);
    }
  }

  private String stringify(Object value) {
    try {
      return json.writeValueAsString(value);
    } catch (JacksonException e) {
      throw new IllegalStateException("Unable to encode Weidian request");
    }
  }
}
