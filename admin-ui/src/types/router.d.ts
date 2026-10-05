import "vue-router";
import type { Role } from "@/api/types";

declare module "vue-router" {
  interface RouteMeta {
    /** Page title; also the menu label. */
    title?: string;
    /** Roles allowed to open the route. Missing or empty means any signed-in administrator. */
    roles?: Role[];
    /** Public routes can be opened without signing in (login page). */
    public?: boolean;
    /** Keep the route out of the side menu. */
    hideInMenu?: boolean;
    /** Name of an Element Plus icon component. */
    icon?: string;
  }
}
