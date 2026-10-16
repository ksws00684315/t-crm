package com.tcrm.admin.kol.api;

/**
 * KOL reference for other modules: identity, display name, whether the KOL is enabled, and the
 * current BD ({@code null} when unassigned) so callers can apply the BD data scope. The link target
 * inputs (task 11 §4.4) carry the resolved template so the link module never reads the template
 * table itself.
 *
 * @param inviteCode invite code on the agent platform; null when not set
 * @param linkTemplateUrl URL template with {@code {PRODUCT_ID}} (and maybe {@code {INVITE_CODE}});
 *     null when the KOL has no template
 * @param linkTemplateEnabled whether the KOL's template is enabled (status 1); irrelevant without a
 *     template
 */
public record KolRefDTO(
    Long id,
    String kolCode,
    String name,
    boolean enabled,
    Long bdUserId,
    String inviteCode,
    String linkTemplateUrl,
    boolean linkTemplateEnabled) {
  /** KOL without link target inputs (callers that only need identity, scope or status). */
  public KolRefDTO(Long id, String kolCode, String name, boolean enabled, Long bdUserId) {
    this(id, kolCode, name, enabled, bdUserId, null, null, false);
  }
}
