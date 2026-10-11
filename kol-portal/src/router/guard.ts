import type { RouteLocationNormalized, RouteLocationRaw, Router } from "vue-router";
import { applyDocumentTitle } from "./title";

export const LOGIN_PATH = "/login";
export const HOME_PATH = "/";

export interface AuthState {
  isLoggedIn: boolean;
}

/**
 * Decides whether a navigation may proceed. Returns `true` to allow it, or the location to go to:
 * signed-out users are sent to /login (remembering where they wanted to go) and signed-in users
 * skip the login page. Sign-in state is local only until the creator login API exists.
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
  return true;
}

/** Installs the guard. `getAuth` is read on every navigation so it always sees fresh state. */
export function installGuard(router: Router, getAuth: () => AuthState): void {
  router.beforeEach(to => resolveAccess(to, getAuth()));
  router.afterEach(to => applyDocumentTitle(to.meta));
}
