import type { RouteLocationNormalized, RouteLocationRaw, Router } from "vue-router";
import NProgress from "nprogress";
import type { AccessProfile } from "@/utils/permission";
import { canAccessRoute } from "@/utils/permission";
import { applyDocumentTitle } from "@/router/title";

export const LOGIN_PATH = "/login";
export const FORBIDDEN_PATH = "/403";
export const HOME_PATH = "/";
/** The creator list, home page of a creator-library company (ADR-038 §3.3). */
export const LIBRARY_HOME_PATH = "/kol-library/kols";

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
    // The forbidden page is the end of the road: sending it to itself is an endless redirect.
    return to.path === FORBIDDEN_PATH ? true : FORBIDDEN_PATH;
  }
  // The home of a creator-library company is its creator list (ADR-038 §3.3, requirement 1.2.5):
  // "/home" moves there; every other company keeps the workbench. "/" is left to its own redirect.
  // The target is a full path: a location without a path resolves back to "/home" and loops.
  if (!auth.isPlatformAdmin && to.path === "/home" && auth.edition === "KOL_LIBRARY") {
    return LIBRARY_HOME_PATH;
  }
  return true;
}

/** Installs the guard. `getAuth` is read on every navigation so it always sees fresh state. */
/**
 * Decides whether a page may open its own tab. A page that was still loading when the company
 * switched (or any page whose route no longer matches what the router would do) must not add a tab
 * to the new company's tab list; the navigation itself already checks the same thing.
 */
export type TabOpener = (path: string, auth: AuthState) => boolean;

let tabOpener: TabOpener = () => true;

export function installGuard(router: Router, getAuth: () => AuthState): void {
  tabOpener = (path, auth) => {
    const resolved = router.resolve(path);
    return resolved.path === path && canAccessRoute(resolved.meta, auth);
  };
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

/** True when a page at that route may still take a tab in this session. */
export function mayOpenTab(path: string, auth: AuthState): boolean {
  return tabOpener(path, auth);
}
