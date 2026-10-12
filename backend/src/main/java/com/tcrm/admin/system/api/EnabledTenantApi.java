package com.tcrm.admin.system.api;

import java.util.List;

/** Company registry access used by system-wide scheduled jobs. */
public interface EnabledTenantApi {
  List<Long> enabledTenantIds();
}
