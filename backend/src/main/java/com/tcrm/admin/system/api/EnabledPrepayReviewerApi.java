package com.tcrm.admin.system.api;

import java.util.List;

/** Enabled company users currently granted the prepay review permission. */
public interface EnabledPrepayReviewerApi {
  List<Long> listEnabledIds();
}
