package com.tcrm.fixtures;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * An endpoint with no access annotation, so the architecture rule can be shown to reject it. This
 * class lives outside {@code com.tcrm.admin} so a running application does not register it.
 */
@RestController
public class UndeclaredEndpointFixture {
  @GetMapping("/admin-api/fixture")
  public void open() {}
}
