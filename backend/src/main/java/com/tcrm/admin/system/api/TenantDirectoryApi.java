package com.tcrm.admin.system.api;

import java.util.Collection;
import java.util.List;

/** Company directory lookups for other modules; system module owns the sys_tenant table. */
public interface TenantDirectoryApi {
  /**
   * Returns the companies among {@code ids}; unknown IDs are skipped. An empty collection returns
   * an empty list without querying.
   */
  List<TenantRefDTO> listByIds(Collection<Long> ids);

  /**
   * @param enabled status is 1 (design §3.4: a disabled company cannot be logged into)
   */
  record TenantRefDTO(Long id, String name, boolean enabled) {}
}
