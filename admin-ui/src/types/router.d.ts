import "vue-router";
import type { PermissionCode } from "@/api/types";

declare module "vue-router" {
  interface RouteMeta {
    /** Locale key for the page title; also the menu label (for example "route.home"). */
    title?: string;
    /** Permission code required to open the route. A super administrator passes every code. */
    permission?: PermissionCode;
    /** Super-administrator only (user management, audit log). */
    superAdmin?: boolean;
    /** Platform-administrator only (company management). */
    platformAdmin?: boolean;
    /** Public routes can be opened without signing in (login page). */
    public?: boolean;
    /** Keep the route out of the side menu. */
    hideInMenu?: boolean;
    /** Name of an Element Plus icon component. */
    icon?: string;
    /** Parent menu group (children listed under a sub-menu). */
    menuGroup?: boolean;
    /** Hide from the menu when the signed-in staff member has OWN data scope (task 05). */
    hideForOwn?: boolean;
    /**
     * SFC `name` of the page component. KeepAlive include matches this, not the route name.
     * Required for every page that opens a tab.
     */
    componentName?: string;
    /** Logged-in page that must not open a tab (403). */
    noTab?: boolean;
  }
}
