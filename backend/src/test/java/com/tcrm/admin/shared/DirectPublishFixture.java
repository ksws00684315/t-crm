package com.tcrm.admin.shared;

import org.springframework.context.ApplicationEventPublisher;

/** Calls {@code publishEvent} directly so the architecture rule can be shown to reject it. */
public class DirectPublishFixture {
  void publish(ApplicationEventPublisher events) {
    events.publishEvent(new Object());
  }
}
