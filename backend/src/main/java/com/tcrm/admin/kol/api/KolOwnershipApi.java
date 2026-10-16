package com.tcrm.admin.kol.api;

import java.time.Instant;
import java.util.List;

/** Payment-time BD ownership lookup in the caller's company. */
public interface KolOwnershipApi {
  /** BD covering [start,end); null denotes company ownership or a missing historical assignment. */
  Long bdAt(Long kolId, Instant at);

  /** New history records with create_time in [from,to), ordered by create time and id. */
  List<KolOwnershipDTO> createdBetween(Instant from, Instant to);
}
