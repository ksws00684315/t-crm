import { useSessionStore } from "@/stores/session";
import { createHttp } from "@/utils/http";
import type { ApiError } from "@/utils/http/error";
import { useLocaleStore } from "@/stores/locale";

let unauthorizedHandler: (error: ApiError) => void = () => {};

/** Registered once at startup (see setup.ts); keeps the http layer free of store/router imports. */
export function setUnauthorizedHandler(handler: (error: ApiError) => void): void {
  unauthorizedHandler = handler;
}

/** The only axios instance. Pages and stores go through the functions in `api/`. */
export const http = createHttp({
  getToken: () => {
    return useSessionStore().getToken();
  },
  getLanguage: () => useLocaleStore().locale,
  onUnauthorized: error => unauthorizedHandler(error)
});
