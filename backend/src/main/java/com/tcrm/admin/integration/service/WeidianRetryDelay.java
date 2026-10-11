package com.tcrm.admin.integration.service;

import java.time.Duration;
import org.springframework.stereotype.Component;

@Component
public class WeidianRetryDelay {
  public void pause(Duration duration) {
    try {
      Thread.sleep(duration);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new WeidianTransportException(false);
    }
  }
}
