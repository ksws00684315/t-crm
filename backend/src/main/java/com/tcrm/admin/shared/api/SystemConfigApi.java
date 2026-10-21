package com.tcrm.admin.shared.api;

/** Read-only access to platform-level system configuration for other backend modules. */
public interface SystemConfigApi {
  /** Returns the currently effective value of a registered configuration key. */
  String getValue(String key);

  /** Returns the currently effective integer value of a registered configuration key. */
  int getInt(String key);
}
