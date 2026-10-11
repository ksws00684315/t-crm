import { enUS, translate } from "@/locales";

/**
 * Error-code text for users, taken from the `errors.*` keys of the message packs in the current
 * language; the backend `msg` is never shown (spec 02 section 7). Creator-specific codes are
 * added together with the creator API in later tasks.
 */

// UPPER_SNAKE_CASE keys of the pack are backend error codes; lowercase ones (generic, network,
// field) are the fallback texts and must not match a code.
const knownErrorCodes: ReadonlySet<string> = new Set(
  Object.keys(enUS.errors).filter(key => /^[A-Z][A-Z0-9_]*$/.test(key))
);

export function isKnownErrorCode(errorCode: string | null | undefined): boolean {
  return !!errorCode && knownErrorCodes.has(errorCode);
}

/** Text for an error code in the current language; unknown or missing codes get the generic text. */
export function messageForErrorCode(errorCode: string | null | undefined): string {
  return isKnownErrorCode(errorCode)
    ? translate(`errors.${errorCode}`)
    : translate("errors.generic");
}

export function genericErrorMessage(): string {
  return translate("errors.generic");
}

export function networkErrorMessage(): string {
  return translate("errors.network");
}

export function fieldErrorMessage(): string {
  return translate("errors.field");
}
