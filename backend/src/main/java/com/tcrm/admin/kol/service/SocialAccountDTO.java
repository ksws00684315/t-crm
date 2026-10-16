package com.tcrm.admin.kol.service;

/**
 * One social media account of a KOL.
 *
 * @param url https profile URL; null when not given
 * @param followers follower count; null when unknown
 * @param primary marks the main platform (主阵地); at most one per KOL, false when unset
 */
public record SocialAccountDTO(
    String platform, String handle, String url, Long followers, boolean primary) {
  /** Account without the primary flag (import rows have no such column). */
  public SocialAccountDTO(String platform, String handle, String url, Long followers) {
    this(platform, handle, url, followers, false);
  }
}
