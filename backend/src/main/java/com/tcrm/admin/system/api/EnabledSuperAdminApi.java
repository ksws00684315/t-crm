package com.tcrm.admin.system.api;

import java.util.List;

/** Enabled super administrators of the company in the current scope, ordered by id. */
public interface EnabledSuperAdminApi {
  List<Long> listEnabledIds();
}
