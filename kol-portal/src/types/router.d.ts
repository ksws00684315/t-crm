import "vue-router";

declare module "vue-router" {
  interface RouteMeta {
    /** Page title. */
    title?: string;
    /** Public routes can be opened without signing in (the login page). */
    public?: boolean;
  }
}
