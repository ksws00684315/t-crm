package com.tcrm.admin.security.api;

/**
 * Product edition of a company (ADR-038). A company's edition decides which backend modules and
 * permission codes it may use; it is a property of the company, not of an account, so switching
 * companies switches editions. New editions require code changes.
 */
public enum Edition {
  /** Full distribution product: every existing module. Every company before ADR-038 has it. */
  DISTRIBUTION,
  /** TikTok creator library: the creator library and account management only. */
  KOL_LIBRARY
}
