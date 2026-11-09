package com.tcrm.admin.security.api;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Company editions that may call this controller (ADR-038 §2, task 143 §2.3.1). Declared on the
 * controller class; every endpoint of a controller shares its editions.
 *
 * <p><b>Denied by default:</b> a controller without this annotation belongs to {@link
 * Edition#DISTRIBUTION} only, so a new feature cannot become reachable from another edition just by
 * being forgotten. The check runs in a servlet interceptor, before the role and permission
 * annotations, and only for endpoints of a company session: platform endpoints ({@code
 * /admin-api/platform/**}), external endpoints and public endpoints are not affected. {@code
 * com.tcrm.admin.ArchitectureTest} keeps the list of controllers open to {@link
 * Edition#KOL_LIBRARY} explicit, so opening one is always a deliberate change.
 *
 * <p>The annotation lives in this module's public package rather than in {@code shared}: {@code
 * shared} depends on no business module, and the enum and its check belong to authorisation.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface EditionScope {
  Edition[] value();
}
