package com.tcrm.fixtures;

import com.tcrm.admin.security.api.Edition;
import com.tcrm.admin.security.api.EditionScope;
import org.springframework.web.bind.annotation.RestController;

/**
 * Positive control for {@link EditionInterceptorTest} and the architecture rule: the interceptor
 * must read a declared scope. Lives outside {@code com.tcrm.admin} so a running application does
 * not register it.
 */
@RestController
@EditionScope({Edition.KOL_LIBRARY})
public class EditionFixture {
  public void handle() {}
}
