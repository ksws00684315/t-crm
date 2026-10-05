import type { RouteLocationNormalized, RouteLocationRaw, Router } from "vue-router";
import NProgress from "nprogress";
import type { Role } from "@/api/types";
import { hasAnyRole } from "@/utils/permission";

export const LOGIN_PATH = "/login";
export const FORBIDDEN_PATH = "/403";
export const HOME_PATH = "/";

export interface AuthState {
  isLoggedIn: boolean;
  roles: readonly Role[];
}

/**
 * Decides whether a navigation may proceed. Returns `true` to allow it, or the location to go to:
 * signed-out users are sent to the login page (remembering where they wanted to go), signed-in
 * users skip the login page, and users without a required role land on the 403 page.
 */
export function resolveAccess(
  to: Pick<RouteLocationNormalized, "path" | "fullPath" | "meta">,
  auth: AuthState
): true | RouteLocationRaw {
  if (to.meta.public) {
    return auth.isLoggedIn && to.path === LOGIN_PATH ? HOME_PATH : true;
  }
  if (!auth.isLoggedIn) {
    const redirect = to.fullPath === HOME_PATH ? undefined : to.fullPath;
    return { path: LOGIN_PATH, query: redirect ? { redirect } : undefined };
  }
  if (!hasAnyRole(to.meta.roles, auth.roles)) return FORBIDDEN_PATH;
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
    document.title = to.meta.title ? `${to.meta.title} | T-CRM 管理端` : "T-CRM 管理端";
  });
}
