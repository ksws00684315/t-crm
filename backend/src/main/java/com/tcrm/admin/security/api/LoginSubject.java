package com.tcrm.admin.security.api;

/** Username supplied by a public login attempt for audit attribution. */
public interface LoginSubject {
  /** Returns the validated login name for audit attribution, including rejected login attempts. */
  String username();
}
