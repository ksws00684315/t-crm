package com.tcrm.admin.security.api;

/**
 * The current company's edition, supplied by the system module (ADR-038). Authorisation reads it,
 * so it lives behind an interface like {@link AccountStore} instead of a company table join in
 * every request.
 */
public interface CompanyStore {
  /**
   * Edition of {@code tenantId}; {@link Edition#DISTRIBUTION} when the company row is missing or
   * stores a value this build does not know, because the full product is the edition that never
   * withholds anything an existing token could reach.
   */
  Edition editionOf(long tenantId);
}
