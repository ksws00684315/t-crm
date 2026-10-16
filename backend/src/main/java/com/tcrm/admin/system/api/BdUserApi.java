package com.tcrm.admin.system.api;

import java.util.Collection;
import java.util.List;

/**
 * BD lookups for other modules. A usable BD is an enabled staff account with OWN scope and {@code
 * kol:edit}.
 */
public interface BdUserApi {
  /**
   * Returns the administrator with this ID, or null when it does not exist (or is deleted).
   *
   * @param id positive administrator ID
   */
  BdUserDTO getById(Long id);

  /**
   * Returns the administrator with this login name (case-insensitive), or null when none exists.
   *
   * @param username login name as typed; blank returns null
   */
  BdUserDTO getByUsername(String username);

  /**
   * Returns the existing administrators among {@code ids}, usable or not, for display; unknown IDs
   * are skipped. An empty collection returns an empty list without querying.
   */
  List<BdUserDTO> listByIds(Collection<Long> ids);

  /** Returns every usable BD ordered by username. */
  List<BdUserDTO> listUsable();
}
