import { useLocaleStore } from "@/stores/locale";
import { loadSession } from "@/utils/session";
import { createHttp } from "@/utils/http";
import type { ApiError } from "@/utils/http/error";

let unauthorizedHandler: (error: ApiError) => void = () => {};
let companyIdSource: () => string | null = () => null;
let companySwitchedHandler: (error: ApiError) => void = () => {};

/** Registered once at startup (see setup.ts); keeps the http layer free of store/router imports. */
export function setUnauthorizedHandler(handler: (error: ApiError) => void): void {
  unauthorizedHandler = handler;
}

/** Registered once at startup; supplies the company shown in this tab (not the stored one). */
export function setCompanyIdSource(source: () => string | null): void {
  companyIdSource = source;
}

/** Registered once at startup; runs on 409 AUTH_COMPANY_SWITCHED. */
export function setCompanySwitchedHandler(handler: (error: ApiError) => void): void {
  companySwitchedHandler = handler;
}

/** The only axios instance. Pages and stores go through the functions in `api/`. */
export const http = createHttp({
  getToken: () => loadSession()?.token ?? null,
  getLanguage: () => useLocaleStore().locale,
  onUnauthorized: error => unauthorizedHandler(error),
  getCompanyId: () => companyIdSource(),
  onCompanySwitched: error => companySwitchedHandler(error)
});
