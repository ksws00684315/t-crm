import type { RouteMeta } from "vue-router";
import { translate } from "@/locales";
import { siteName } from "@/stores/site-config";

/**
 * Sets document.title from a route's `meta.title` (a locale key such as "route.login") followed by
 * the portal name. Called from the router's afterEach and again whenever the language changes.
 */
export function applyDocumentTitle(meta: RouteMeta): void {
  const pageTitle = meta.title ? translate(meta.title) : null;
  const appName = `${siteName.value}${translate("app.titleSuffix")}`;
  document.title = pageTitle ? `${pageTitle} | ${appName}` : appName;
}
