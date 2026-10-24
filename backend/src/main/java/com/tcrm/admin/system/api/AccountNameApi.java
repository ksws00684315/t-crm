package com.tcrm.admin.system.api;

import java.util.Collection;
import java.util.List;

/**
 * Account display names for other modules. Unlike {@link BdUserApi}, this is not limited to the
 * caller's current company membership: a nickname stays visible after the person is disabled or
 * deleted. {@code sys_user} is not company-scoped.
 */
public interface AccountNameApi {
  /**
   * Returns the accounts among {@code ids}, including disabled and deleted ones. Unknown ids are
   * skipped. An empty collection returns an empty list without querying.
   */
  List<AccountNameDTO> listByIds(Collection<Long> ids);
}
