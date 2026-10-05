package com.tcrm.admin.kol.service;

/**
 * One social media account of a KOL.
 *
 * @param url https profile URL; null when not given
 * @param followers follower count; null when unknown
 */
public record SocialAccountDTO(String platform, String handle, String url, Long followers) {}
