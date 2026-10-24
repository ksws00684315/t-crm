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
   * Same as {@link #listByIds}, but keeps logically deleted KOLs so a historical prepay can still
   * show the creator's code and name. An empty collection returns an empty list without querying.
   */
  List<KolRefDTO> listByIdsIncludingDeleted(Collection<Long> ids);

  /**
   * Returns every undeleted KOL, enabled or not, ordered by ID (task 06 link index rebuild). The
   * design capacity is a few hundred KOLs.
   */
  List<KolRefDTO> listAll();

  /**
   * Pages the IDs of undeleted KOLs that use the link template, in ascending id order (task 11
   * §4.4). Used to register link index refreshes for every KOL of a template whose URL or status
   * changed; like the rebuild, it must run in system scope to cover every company.
   *
   * @param templateId crm_link_template.id
   * @param afterKolId exclusive lower bound; null starts at the first id
   * @param limit maximum number of ids (positive)
   */
  List<Long> pageKolIdsWithTemplate(Long templateId, Long afterKolId, int limit);

  /**
   * Every undeleted KOL currently assigned to the BD in the caller's company, enabled or not (task
   * 15a §4.4). Used by modules that apply the OWN data scope to a BD's KOLs; a BD has at most a few
   * hundred.
   *
   * @param bdUserId sys_user.id of the BD
   */
  List<KolRefDTO> listByBdUserId(Long bdUserId);

  /**
   * Locks the KOL row for the rest of the current transaction ({@code FOR UPDATE}), so sibling
   * writes that run under the same lock serialize (task 15a review: counting a KOL's payout
   * accounts then inserting must be atomic). Returns false when the KOL does not exist.
   *
   * @param kolId crm_kol.id
   */
  boolean lockKol(long kolId);

  /**
   * Ids of undeleted KOLs whose name contains {@code keyword} (case-insensitive), or whose code or
   * email equals it (case-insensitive). Blank returns an empty list without querying.
   *
   * @param keyword text typed in the creator-item filter
   */
  List<Long> findIdsByKeyword(String keyword);

  /**
   * Current promotion rate text of these KOLs (task 39). Unknown ids are skipped. An empty
   * collection returns an empty list without querying. A KOL with no rate has a null {@code
   * rateText}.
   */
  List<KolRateDTO> listRatesByIds(Collection<Long> ids);
}
