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
}

/** Side menu entries the given roles may open, in route order. */
export function buildMenu(routes: readonly RouteRecordRaw[], owned: readonly Role[]): MenuItem[] {
  const items: MenuItem[] = [];
  const visit = (list: readonly RouteRecordRaw[], base: string) => {
    for (const route of list) {
      const path = route.path.startsWith("/")
        ? route.path
        : `${base.replace(/\/$/, "")}/${route.path}`;
      const meta = route.meta;
      if (meta?.title && !meta.public && !meta.hideInMenu && hasAnyRole(meta.roles, owned)) {
        items.push({ path, title: meta.title, icon: meta.icon });
      }
      if (route.children) visit(route.children, path);
    }
  };
  visit(routes, "/");
  return items;
}
