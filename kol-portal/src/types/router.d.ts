import "vue-router";

declare module "vue-router" {
  interface RouteMeta {
    /** Page title: a locale key (e.g. "route.login"), translated when document.title is set. */
    title?: string;
    /** Public routes can be opened without signing in (the login page). */
    public?: boolean;
  }
}
