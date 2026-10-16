import type { RouteLocationNormalized, RouteLocationRaw, Router } from "vue-router";
import NProgress from "nprogress";
import type { AccessProfile } from "@/utils/permission";
import { canAccessRoute } from "@/utils/permission";
import { applyDocumentTitle } from "@/router/title";

export const LOGIN_PATH = "/login";
export const FORBIDDEN_PATH = "/403";
export const HOME_PATH = "/";

export interface AuthState extends AccessProfile {
  isLoggedIn: boolean;
}

/**
 * Decides whether a navigation may proceed. Returns `true` to allow it, or the location to go to:
 * signed-out users are sent to the login page (remembering where they wanted to go), signed-in
 * users skip the login page, and users without the required permission or data scope land on 403.
 * A platform administrator who opens home is sent to company management.
 */
export function resolveAccess(
  to: Pick<RouteLocationNormalized, "path" | "fullPath" | "meta">,
  auth: AuthState
): true | RouteLocationRaw {
  if (to.meta.public) {
    return auth.isLoggedIn && to.path === LOGIN_PATH
      ? auth.isPlatformAdmin
        ? "/platform/tenants"
        : HOME_PATH
      : true;
  }
  if (!auth.isLoggedIn) {
    const redirect = to.fullPath === HOME_PATH ? undefined : to.fullPath;
    return { path: LOGIN_PATH, query: redirect ? { redirect } : undefined };
  }
  if (!canAccessRoute(to.meta, auth)) {
    if (auth.isPlatformAdmin && (to.path === "/" || to.path === "/home")) {
      return "/platform/tenants";
    }
    return FORBIDDEN_PATH;
  }
  return true;
}

/** Installs the guard. `getAuth` is read on every navigation so it always sees fresh state. */
export function installGuard(router: Router, getAuth: () => AuthState): void {
  router.beforeEach(to => {
    NProgress.start();
    const decision = resolveAccess(to, getAuth());
    if (decision !== true) NProgress.done();
    return decision;
  });
  router.afterEach(to => {
    NProgress.done();
    applyDocumentTitle(to.meta);
  });
}
