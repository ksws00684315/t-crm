import type { RouteRecordRaw } from "vue-router";
import type { Role } from "@/api/types";
import { translate } from "@/locales";

/** Role name in the current language. Resolved at call time so a switch updates open screens. */
export function roleLabel(role: Role): string {
  return translate(`role.${role}`);
}

/** Roles available in company user management; platform administrators are platform-level. */
export const SELECTABLE_ROLES: readonly Exclude<Role, "PLATFORM_ADMIN">[] = [
  "SUPER_ADMIN",
  "OPERATOR",
  "FINANCE"
];

/**
 * True when `required` is absent/empty (any signed-in administrator) or the user holds at least one
 * of the required roles.
 */
export function hasAnyRole(required: readonly Role[] | undefined, owned: readonly Role[]): boolean {
  if (!required || required.length === 0) return true;
  return required.some(role => owned.includes(role));
}

/**
 * Whether a signed-in user may open a route: role match plus data-scope (hideForOwn),
 * matching buildMenu so OPERATOR+OWN cannot deep-link to hidden pages.
 */
export function canAccessRoute(
  meta: { roles?: readonly Role[]; hideForOwn?: boolean } | undefined,
  owned: readonly Role[],
  dataScope: "ALL" | "OWN" | null | undefined
): boolean {
  if (owned.includes("PLATFORM_ADMIN")) {
    const platformRoute = meta?.roles?.includes("PLATFORM_ADMIN") ?? false;
    const accountOrError = !meta?.roles?.length;
    if (!platformRoute && !accountOrError) return false;
  }
  if (!hasAnyRole(meta?.roles, owned)) return false;
  if (meta?.hideForOwn && dataScope === "OWN") return false;
  return true;
}

export interface MenuItem {
  path: string;
  title: string;
  icon?: string;
  children?: MenuItem[];
}

/** Side menu entries the given roles may open, in route order. Supports one level of grouping. */
export function buildMenu(
  routes: readonly RouteRecordRaw[],
  owned: readonly Role[],
  dataScope: "ALL" | "OWN" | null | undefined = "ALL"
): MenuItem[] {
  const items: MenuItem[] = [];
  const visit = (list: readonly RouteRecordRaw[], base: string) => {
    for (const route of list) {
      const path = route.path.startsWith("/")
        ? route.path
        : `${base.replace(/\/$/, "")}/${route.path}`;
      const meta = route.meta;
      if (meta?.menuGroup && meta.title && hasAnyRole(meta.roles, owned)) {
        const children: MenuItem[] = [];
        if (route.children) {
          for (const child of route.children) {
            const childPath = child.path.startsWith("/")
              ? child.path
              : `${path.replace(/\/$/, "")}/${child.path}`;
            const childMeta = child.meta;
            if (
              childMeta?.title &&
              !childMeta.public &&
              !childMeta.hideInMenu &&
              canAccessRoute(childMeta, owned, dataScope)
            ) {
              children.push({
                path: childPath,
                title: translate(childMeta.title),
                icon: childMeta.icon as string | undefined
              });
            }
          }
        }
        if (children.length > 0) {
          items.push({
            path,
            title: translate(meta.title),
            icon: meta.icon as string | undefined,
            children
          });
        }
        continue;
      }
      if (
        meta?.title &&
        !meta.public &&
        !meta.hideInMenu &&
        canAccessRoute(meta, owned, dataScope)
      ) {
        items.push({
          path,
          title: translate(meta.title),
          icon: meta.icon as string | undefined
        });
      }
      if (route.children && !meta?.menuGroup) visit(route.children, path);
    }
  };
  visit(routes, "/");
  return items;
}

/** What the KOL pages show to an administrator (task 04 §4.3). The backend enforces the same. */
export interface KolAbilities {
  /** Create, edit, enable/disable and import: everyone except FINANCE. */
  canWrite: boolean;
  /** Change BD, pick a BD, filter by BD: SUPER_ADMIN and OPERATOR + ALL only. */
  canManageBd: boolean;
}

export function kolAbilities(
  user: { role: Role; dataScope: "ALL" | "OWN" } | null | undefined
): KolAbilities {
  if (!user) return { canWrite: false, canManageBd: false };
  const bd = user.role === "OPERATOR" && user.dataScope === "OWN";
  return {
    canWrite: user.role !== "FINANCE",
    canManageBd: user.role === "SUPER_ADMIN" || (user.role === "OPERATOR" && !bd)
  };
}

/** Product / shop / mapping button visibility (task 05 §4.4). Backend enforces the same. */
export interface ProductAbilities {
  /** Shop create/edit/status: SUPER_ADMIN only. */
  canManageShop: boolean;
  /** Product edit/status/import: SUPER_ADMIN and OPERATOR + ALL. */
  canWriteProduct: boolean;
  /** Mapping list: SUPER_ADMIN, OPERATOR + ALL, FINANCE. */
  canViewMapping: boolean;
  /** Mapping import: SUPER_ADMIN and OPERATOR + ALL. */
  canImportMapping: boolean;
}

export function productAbilities(
  user: { role: Role; dataScope: "ALL" | "OWN" } | null | undefined
): ProductAbilities {
  if (!user) {
    return {
      canManageShop: false,
      canWriteProduct: false,
      canViewMapping: false,
      canImportMapping: false
    };
  }
  const own = user.role === "OPERATOR" && user.dataScope === "OWN";
  const allOp = user.role === "OPERATOR" && !own;
  return {
    canManageShop: user.role === "SUPER_ADMIN",
    canWriteProduct: user.role === "SUPER_ADMIN" || allOp,
    canViewMapping: user.role === "SUPER_ADMIN" || allOp || user.role === "FINANCE",
    canImportMapping: user.role === "SUPER_ADMIN" || allOp
  };
}

/** Tracking link visibility (task 06 §4.7). Backend enforces the same, including the BD scope. */
export interface LinkAbilities {
  /** Link list of a KOL: every role; a BD only reaches their own KOLs. */
  canViewLinks: boolean;
  /** Full link index rebuild: SUPER_ADMIN only. */
  canRebuildIndex: boolean;
}

export function linkAbilities(
  user: { role: Role; dataScope: "ALL" | "OWN" } | null | undefined
): LinkAbilities {
  if (!user) return { canViewLinks: false, canRebuildIndex: false };
  return { canViewLinks: true, canRebuildIndex: user.role === "SUPER_ADMIN" };
}
