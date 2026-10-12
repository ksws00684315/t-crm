import "vue-router";
import type { Role } from "@/api/types";

declare module "vue-router" {
  interface RouteMeta {
    /** Locale key for the page title; also the menu label (for example "route.home"). */
    title?: string;
    /** Roles allowed to open the route. Missing or empty means any signed-in administrator. */
    roles?: Role[];
    /** Public routes can be opened without signing in (login page). */
    public?: boolean;
    /** Keep the route out of the side menu. */
    hideInMenu?: boolean;
    /** Name of an Element Plus icon component. */
    icon?: string;
    /** Parent menu group (children listed under a sub-menu). */
    menuGroup?: boolean;
    /** Hide from the menu when the signed-in operator has OWN data scope (task 05). */
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
