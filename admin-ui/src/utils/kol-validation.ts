/*
 * KOL form checks mirroring the backend request constraints (CreateKolRequest / EditKolRequest,
 * KolRules): name 1-64, email same pattern as the backend and at most 128, ISO alpha-2 country,
 * social platform 1-32, handle 1-128, https url at most 512, follower count non-negative integer,
 * remark at most 500. The backend also checks that the country code is assigned.
 */

const EMAIL_PATTERN =
  /^[A-Za-z0-9._%+-]+@[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?(?:\.[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?)+$/;
const COUNTRY_PATTERN = /^[A-Za-z]{2}$/;
const HTTPS_URL_PATTERN = /^https:\/\/[^\s/?#]+\S*$/;
const COUNT_PATTERN = /^\d{1,15}$/;

/** Length in Unicode characters, as the backend counts it. */
const length = (value: string) => [...value].length;
const blank = (value: string | null | undefined) => !value || value.trim().length === 0;

export function validateKolName(value: string): string | null {
  if (blank(value)) return "请输入姓名";
  if (length(value.trim()) > 64) return "姓名不能超过 64 个字符";
  return null;
}

export function validateKolEmail(value: string): string | null {
  if (blank(value)) return "请输入邮箱";
  const text = value.trim();
  if (length(text) > 128) return "邮箱不能超过 128 个字符";
  if (!EMAIL_PATTERN.test(text)) return "邮箱格式不正确";
  return null;
}

export function validateCountry(value: string): string | null {
  if (blank(value)) return "请输入国家代码";
  if (!COUNTRY_PATTERN.test(value.trim())) return "请输入两位字母国家代码，如 US";
  return null;
}

export function validateRemark(value: string): string | null {
  return length(value ?? "") > 500 ? "备注不能超过 500 个字符" : null;
}

export interface SocialAccountInput {
  platform: string;
  handle: string;
  url: string;
  followers: string;
}

/** First problem of one social account row, or null. */
export function validateSocialAccount(account: SocialAccountInput): string | null {
  if (blank(account.platform)) return "请输入社媒平台";
  if (length(account.platform.trim()) > 32) return "社媒平台不能超过 32 个字符";
  if (blank(account.handle)) return "请输入社媒账号";
  if (length(account.handle.trim()) > 128) return "社媒账号不能超过 128 个字符";
  const url = account.url.trim();
  if (url && (url.length > 512 || !HTTPS_URL_PATTERN.test(url))) {
    return "主页链接须以 https:// 开头，且不超过 512 个字符";
  }
  const followers = account.followers.trim();
  if (followers && !COUNT_PATTERN.test(followers)) return "粉丝数须为非负整数";
  return null;
}
