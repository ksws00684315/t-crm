import type { RouteMeta } from "vue-router";
import { translate } from "@/locales";

/**
 * Sets document.title from a route's `meta.title` (a locale key such as "route.login") followed by
 * the console name. Called from the router's afterEach and again whenever the language changes.
 */
export function applyDocumentTitle(meta: RouteMeta): void {
  const pageTitle = meta.title ? translate(meta.title) : null;
  const appName = translate("app.title");
  document.title = pageTitle ? `${pageTitle} | ${appName}` : appName;
}
