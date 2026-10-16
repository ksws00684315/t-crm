/*
 * KOL form checks mirroring the backend request constraints (CreateKolRequest / EditKolRequest,
 * KolRules): name 1-64, email same pattern as the backend and at most 128, ISO alpha-2 country,
 * social platform 1-32, handle 1-128, https url at most 512, follower count non-negative integer,
 * remark at most 500. The backend also checks that the country code is assigned.
 */

import { translate } from "@/locales";

const EMAIL_PATTERN =
  /^[A-Za-z0-9._%+-]+@[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?(?:\.[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?)+$/;
const COUNTRY_PATTERN = /^[A-Za-z]{2}$/;
const HTTPS_URL_PATTERN = /^https:\/\/[^\s/?#]+\S*$/;
const COUNT_PATTERN = /^\d{1,15}$/;
const WHATSAPP_PATTERN = /^\+[0-9]{8,15}$/;
const INVITE_CODE_PATTERN = /^[A-Za-z0-9_-]{1,64}$/;
const SHEET_URL_PATTERN = /^https:\/\/docs\.google\.com\/spreadsheets\/.*$/;

/** Length in Unicode characters, as the backend counts it. */
const length = (value: string) => [...value].length;
const blank = (value: string | null | undefined) => !value || value.trim().length === 0;

export function validateKolName(value: string): string | null {
  if (blank(value)) return translate("validation.kolNameRequired");
  if (length(value.trim()) > 64) return translate("validation.kolNameTooLong");
  return null;
}

export function validateKolEmail(value: string): string | null {
  if (blank(value)) return translate("validation.kolEmailRequired");
  const text = value.trim();
  if (length(text) > 128) return translate("validation.kolEmailTooLong");
  if (!EMAIL_PATTERN.test(text)) return translate("validation.emailInvalid");
  return null;
}

/** Optional. A non-blank value must be two letters; the backend also checks the ISO list. */
export function validateCountry(value: string): string | null {
  if (blank(value)) return null;
  if (!COUNTRY_PATTERN.test(value.trim())) return translate("validation.countryPattern");
  return null;
}

export function validateWhatsapp(value: string): string | null {
  const text = value.trim();
  if (!text) return null;
  if (length(text) > 32 || !WHATSAPP_PATTERN.test(text)) return translate("validation.whatsapp");
  return null;
}

export function validateInviteCode(value: string): string | null {
  const text = value.trim();
  if (!text) return null;
  if (!INVITE_CODE_PATTERN.test(text)) return translate("validation.inviteCode");
  return null;
}

export function validateSheetUrl(value: string): string | null {
  const text = value.trim();
  if (!text) return null;
  if (length(text) > 512 || !SHEET_URL_PATTERN.test(text)) return translate("validation.sheetUrl");
  return null;
}

export function validateRemark(value: string): string | null {
  return length(value ?? "") > 500 ? translate("validation.remarkTooLong") : null;
}

export interface SocialAccountInput {
  platform: string;
  handle: string;
  url: string;
  followers: string;
  primary: boolean;
}

/** Selects one row, or clears it when that row is already the primary. */
export function withPrimary(
  rows: readonly SocialAccountInput[],
  index: number
): SocialAccountInput[] {
  const turnOn = rows[index] ? !rows[index].primary : false;
  return rows.map((row, i) => ({ ...row, primary: turnOn && i === index }));
}

/** First problem of one social account row, or null. */
export function validateSocialAccount(account: SocialAccountInput): string | null {
  if (blank(account.platform)) return translate("validation.socialPlatformRequired");
  if (length(account.platform.trim()) > 32) return translate("validation.socialPlatformTooLong");
  if (blank(account.handle)) return translate("validation.socialHandleRequired");
  if (length(account.handle.trim()) > 128) return translate("validation.socialHandleTooLong");
  const url = account.url.trim();
  if (url && (url.length > 512 || !HTTPS_URL_PATTERN.test(url))) {
    return translate("validation.socialUrl");
  }
  const followers = account.followers.trim();
  if (followers && !COUNT_PATTERN.test(followers)) return translate("validation.followers");
  return null;
}
