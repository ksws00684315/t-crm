package com.tcrm.admin.kol.api;

import java.util.Collection;
import java.util.List;

/** KOL lookups for other modules; product must not depend on kol service/mapper/model. */
public interface KolApi {
  /**
   * Returns the KOL with this code (case-insensitive), or null when it does not exist (or is
   * deleted).
   *
   * @param kolCode KOL code as typed; blank returns null
   */
  KolRefDTO getByCode(String kolCode);

  /**
   * Returns the existing KOLs among {@code ids}, enabled or not, for display; unknown IDs are
   * skipped. An empty collection returns an empty list without querying.
   */
  List<KolRefDTO> listByIds(Collection<Long> ids);

  /**
   * Returns every undeleted KOL, enabled or not, ordered by ID (task 06 link index rebuild). The
   * design capacity is a few hundred KOLs.
   */
  List<KolRefDTO> listAll();
}
