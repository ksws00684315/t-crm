/**
 * Where the portal talks to the backend. The creator token header is defined only here so the
 * later task that adds the creator login can adjust it in one place.
 */
export const KOL_BASE_URL = "/kol-api";
/** Creator sessions use an independent token header. */
export const KOL_TOKEN_HEADER = "X-Kol-Token";
