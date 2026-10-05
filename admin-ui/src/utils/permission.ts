import type { RouteRecordRaw } from "vue-router";
import type { Role } from "@/api/types";

export const ROLE_LABELS: Readonly<Record<Role, string>> = {
  SUPER_ADMIN: "超级管理员",
  OPERATOR: "运营",
  FINANCE: "财务"
};

export const ROLES: readonly Role[] = ["SUPER_ADMIN", "OPERATOR", "FINANCE"];

/**
 * True when `required` is absent/empty (any signed-in administrator) or the user holds at least one
 * of the required roles.
 */
export function hasAnyRole(required: readonly Role[] | undefined, owned: readonly Role[]): boolean {
  if (!required || required.length === 0) return true;
  return required.some(role => owned.includes(role));
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
              !(childMeta.hideForOwn && dataScope === "OWN") &&
              hasAnyRole(childMeta.roles, owned)
            ) {
              children.push({
                path: childPath,
                title: childMeta.title,
                icon: childMeta.icon as string | undefined
              });
            }
          }
        }
        if (children.length > 0) {
          items.push({
            path,
            title: meta.title,
            icon: meta.icon as string | undefined,
            children
          });
        }
        continue;
      }
      if (meta?.title && !meta.public && !meta.hideInMenu && hasAnyRole(meta.roles, owned)) {
        items.push({ path, title: meta.title, icon: meta.icon as string | undefined });
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
