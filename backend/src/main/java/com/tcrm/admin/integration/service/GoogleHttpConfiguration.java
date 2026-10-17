package com.tcrm.admin.integration.service;

import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

@Configuration
public class GoogleHttpConfiguration {
  /** Large source sheets return several megabytes, hence the longer read timeout. */
  @Bean
  public RestTemplate googleRestTemplate() {
    var requestFactory = new SimpleClientHttpRequestFactory();
    requestFactory.setConnectTimeout(Duration.ofSeconds(5));
    requestFactory.setReadTimeout(Duration.ofSeconds(60));
    return new RestTemplate(requestFactory);
  }
}
