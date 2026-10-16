import { translate } from "@/locales";

const CODE_PATTERN = /^[a-z0-9-]{2,32}$/;
/** Same as `LinkTemplateRules.URL_PATTERN`: https, no whitespace, contains `{PRODUCT_ID}`. */
const URL_PATTERN = /^https:\/\/\S*\{PRODUCT_ID\}\S*$/;
const PRODUCT_VARIABLE = "{PRODUCT_ID}";
const INVITE_VARIABLE = "{INVITE_CODE}";
const WEIDIAN_PREFIX = "https://weidian.com/item.html?itemID=";

const length = (value: string) => [...value].length;

/** Java `URLEncoder.encode` (UTF-8): space becomes `+`, and `!'()~` are percent-encoded. */
export function javaUrlEncode(value: string): string {
  return encodeURIComponent(value)
    .replace(/[!'()~]/g, char => `%${char.charCodeAt(0).toString(16).toUpperCase()}`)
    .replace(/%20/g, "+");
}

export interface TemplatePreview {
  url: string;
  /** True when `{INVITE_CODE}` is required and the sample invite code is empty. */
  fallback: boolean;
}

/**
 * Fills a template the same way `LinkUrls.targetUrl` does. An empty invite code on a template
 * that needs one falls back to the Weidian item page.
 */
export function previewTemplateUrl(
  templateUrl: string,
  productId: string,
  inviteCode: string
): TemplatePreview {
  if (templateUrl.includes(INVITE_VARIABLE) && inviteCode === "") {
    return { url: WEIDIAN_PREFIX + productId, fallback: true };
  }
  const withInvite = templateUrl.includes(INVITE_VARIABLE)
    ? templateUrl.replaceAll(INVITE_VARIABLE, javaUrlEncode(inviteCode))
    : templateUrl;
  return { url: withInvite.replaceAll(PRODUCT_VARIABLE, productId), fallback: false };
}

export function validateTemplateCode(value: string): string | null {
  const text = value.trim();
  if (!CODE_PATTERN.test(text)) return translate("validation.templateCode");
  return null;
}

export function validateTemplateName(value: string): string | null {
  const text = value.trim();
  if (!text) return translate("validation.nameRequired");
  if (length(text) > 64) return translate("validation.nameTooLong");
  return null;
}

export function validateTemplateUrl(value: string): string | null {
  const text = value.trim();
  if (length(text) > 512 || !URL_PATTERN.test(text)) return translate("validation.templateUrl");
  return null;
}

export function validateSortOrder(value: number | null | undefined): string | null {
  if (value == null || !Number.isInteger(value) || value < 0 || value > 9999) {
    return translate("validation.sortOrder");
  }
  return null;
}
