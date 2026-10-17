import type { RouteRecordRaw } from "vue-router";
import type { DataScope, PermissionCode, PostTemplate, Role } from "@/api/types";
import { i18n, translate } from "@/locales";

/** Account the menu, the guard and the button helpers all read. Role names stay inside this module. */
export interface AbilitySubject {
  role: Role;
  dataScope: DataScope | null;
  permissions?: readonly string[] | null;
  postTemplate?: PostTemplate | null;
}

/** What a signed-in session may do. Pages use these flags instead of comparing role names. */
export interface AccessProfile {
  isSuperAdmin: boolean;
  /** Staff whose data scope is OWN. A super administrator is never own-scoped. */
  isOwnScope: boolean;
  isPlatformAdmin: boolean;
  /** True for every code when the account is a super administrator. */
  can: (code: PermissionCode) => boolean;
}

export const NO_ACCESS: AccessProfile = {
  isSuperAdmin: false,
  isOwnScope: false,
  isPlatformAdmin: false,
  can: () => false
};

export function accessOf(user: AbilitySubject | null | undefined): AccessProfile {
  if (!user) return NO_ACCESS;
  const isSuperAdmin = user.role === "SUPER_ADMIN";
  const isPlatformAdmin = user.role === "PLATFORM_ADMIN";
  const isOwnScope = user.role === "STAFF" && user.dataScope === "OWN";
  const owned = new Set(user.permissions ?? []);
  return {
    isSuperAdmin,
    isOwnScope,
    isPlatformAdmin,
    can: code => isSuperAdmin || owned.has(code)
  };
}

/** Role name in the current language. Resolved at call time so a switch updates open screens. */
export function roleLabel(role: Role): string {
  return translate(`role.${role}`);
}

/** Post template name. Null renders the empty mark from the language pack. */
export function postLabel(post: PostTemplate | null | undefined): string {
  if (!post) return translate("post.none");
  return translate(`post.${post}`);
}

/**
 * Header label: a sub-account shows its post (the old "operator" / "BD" text);
 * everyone else shows the account type.
 */
export function accountLabel(user: AbilitySubject): string {
  if (user.role === "STAFF" && user.postTemplate) return postLabel(user.postTemplate);
  return roleLabel(user.role);
}

/** A company sub-account, the only kind whose permission codes are edited one by one. */
export function isStaff(user: { role: Role } | null | undefined): boolean {
  return user?.role === "STAFF";
}

/** Company super administrator. Not the signed-in session flag; use this for a row in a list. */
export function isSuperAdminAccount(user: { role: Role } | null | undefined): boolean {
  return user?.role === "SUPER_ADMIN";
}

/** Account types a company super administrator may assign. Platform administrators are not here. */
export const SELECTABLE_ROLES: readonly Exclude<Role, "PLATFORM_ADMIN">[] = [
  "SUPER_ADMIN",
  "STAFF"
];

export const POST_TEMPLATES: readonly PostTemplate[] = ["OPERATOR", "BD"];

/** Groups from `GET /system/permissions`, in the order the permission picker renders them. */
export const PERMISSION_GROUP_ORDER = [
  "KOL",
  "PRODUCT",
  "ORDER",
  "FUNDS",
  "DASHBOARD",
  "OTHER"
] as const;

export interface PermissionChoice {
  code: string;
  group: string;
}

export interface PermissionGroupView {
  group: string;
  items: PermissionChoice[];
}

/** Known groups first, then any group the API adds, skipping empty groups. */
export function groupPermissions(items: readonly PermissionChoice[]): PermissionGroupView[] {
  const buckets = new Map<string, PermissionChoice[]>();
  const extra: string[] = [];
  for (const group of PERMISSION_GROUP_ORDER) buckets.set(group, []);
  for (const item of items) {
    if (!buckets.has(item.group)) {
      buckets.set(item.group, []);
      extra.push(item.group);
    }
    buckets.get(item.group)?.push(item);
  }
  return [...PERMISSION_GROUP_ORDER, ...extra]
    .map(group => ({ group, items: buckets.get(group) ?? [] }))
    .filter(group => group.items.length > 0);
}

interface PermissionMessages {
  permission?: Record<string, { name?: string; desc?: string }>;
  permissionGroup?: Record<string, string>;
}

function permissionPack(): PermissionMessages {
  return i18n.global.getLocaleMessage(i18n.global.locale.value) as PermissionMessages;
}

/**
 * Name or description for a permission code. An unknown code keeps its raw code as the name and
 * an empty description, so a newer API does not break this build.
 */
export function permissionText(code: string, field: "name" | "desc"): string {
  const value = permissionPack().permission?.[code]?.[field];
  if (typeof value === "string" && value.length > 0) return value;
  return field === "name" ? code : "";
}

/** Group title. An unknown group id is shown as itself. */
export function permissionGroupLabel(group: string): string {
  const value = permissionPack().permissionGroup?.[group];
  return typeof value === "string" && value.length > 0 ? value : group;
}

/**
 * Route flags the guard and the menu share. `hideInMenu` lets the platform administrator open
 * account pages (password, 403) that are not company business pages.
 */
export interface RouteAccessMeta {
  permission?: PermissionCode;
  superAdmin?: boolean;
  platformAdmin?: boolean;
  hideForOwn?: boolean;
  hideInMenu?: boolean;
  /** Company business page that stays closed to the platform administrator. */
  companyAccount?: boolean;
}

/**
 * Whether a signed-in user may open a route. Company home has no permission code: every company
 * account may open it, and the platform administrator may not. OWN hides `hideForOwn` routes.
 */
export function canAccessRoute(meta: RouteAccessMeta | undefined, access: AccessProfile): boolean {
  if (meta?.platformAdmin) return access.isPlatformAdmin;
  if (access.isPlatformAdmin) {
    if (meta?.companyAccount) return false;
    return !meta?.permission && !meta?.superAdmin && meta?.hideInMenu === true;
  }
  if (meta?.superAdmin && !access.isSuperAdmin) return false;
  if (meta?.permission && !access.can(meta.permission)) return false;
  if (meta?.hideForOwn && access.isOwnScope) return false;
  return true;
}

export interface MenuItem {
  path: string;
  title: string;
  icon?: string;
  children?: MenuItem[];
}

/** Side menu entries the session may open, in route order. Supports one level of grouping. */
export function buildMenu(routes: readonly RouteRecordRaw[], access: AccessProfile): MenuItem[] {
  const items: MenuItem[] = [];
  const visit = (list: readonly RouteRecordRaw[], base: string) => {
    for (const route of list) {
      const path = route.path.startsWith("/")
        ? route.path
        : `${base.replace(/\/$/, "")}/${route.path}`;
      const meta = route.meta;
      if (meta?.menuGroup && meta.title) {
        const children: MenuItem[] = [];
        for (const child of route.children ?? []) {
          const childPath = child.path.startsWith("/")
            ? child.path
            : `${path.replace(/\/$/, "")}/${child.path}`;
          const childMeta = child.meta;
          if (
            childMeta?.title &&
            !childMeta.public &&
            !childMeta.hideInMenu &&
            canAccessRoute(childMeta, access)
          ) {
            children.push({
              path: childPath,
              title: translate(childMeta.title),
              icon: childMeta.icon as string | undefined
            });
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
      if (meta?.title && !meta.public && !meta.hideInMenu && canAccessRoute(meta, access)) {
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

/** What the KOL pages show. The backend enforces the same rules. */
export interface KolAbilities {
  /** Create, edit, enable/disable and import. */
  canWrite: boolean;
  /** Move a creator to another BD. Super administrator only. */
  canChangeBd: boolean;
  /** Pick a BD while creating or importing. Super administrator, or kol:edit outside OWN. */
  canAssignBd: boolean;
  /** Filter the list by BD. kol:view outside OWN. */
  canFilterBd: boolean;
  /** Show and rotate the caller's creator registration link. */
  canRegisterLink: boolean;
}

export function kolAbilities(user: AbilitySubject | null | undefined): KolAbilities {
  const access = accessOf(user);
  if (!user) {
    return {
      canWrite: false,
      canChangeBd: false,
      canAssignBd: false,
      canFilterBd: false,
      canRegisterLink: false
    };
  }
  const canEdit = access.can("kol:edit");
  return {
    canWrite: canEdit,
    canChangeBd: access.isSuperAdmin,
    canAssignBd: access.isSuperAdmin || (canEdit && !access.isOwnScope),
    canFilterBd: access.can("kol:view") && !access.isOwnScope,
    canRegisterLink: access.can("kol:register-link")
  };
}

/** Product / shop / mapping button visibility. The backend enforces the same rules. */
export interface ProductAbilities {
  /** Shop create, edit and status. Super administrator only. */
  canManageShop: boolean;
  /** Source-product edit and import. */
  canEditProduct: boolean;
  /** Source-product on-shelf and off-shelf, including the batch actions. */
  canChangeStatus: boolean;
  /** Mapping list. product:view outside OWN. */
  canViewMapping: boolean;
  /** Mapping import. */
  canImportMapping: boolean;
}

export function productAbilities(user: AbilitySubject | null | undefined): ProductAbilities {
  const access = accessOf(user);
  if (!user) {
    return {
      canManageShop: false,
      canEditProduct: false,
      canChangeStatus: false,
      canViewMapping: false,
      canImportMapping: false
    };
  }
  return {
    canManageShop: access.isSuperAdmin,
    canEditProduct: access.can("product:edit"),
    canChangeStatus: access.can("product:status"),
    canViewMapping: access.can("product:view") && !access.isOwnScope,
    canImportMapping: access.can("product:edit")
  };
}

/** Tracking-link visibility. The backend enforces the same rules, including the BD scope. */
export interface LinkAbilities {
  /** Link list of a creator. Requires product:view; OWN still only reaches their own creators. */
  canViewLinks: boolean;
  /** Full link-index rebuild. Super administrator only. */
  canRebuildIndex: boolean;
}

export function linkAbilities(user: AbilitySubject | null | undefined): LinkAbilities {
  const access = accessOf(user);
  if (!user) return { canViewLinks: false, canRebuildIndex: false };
  return {
    canViewLinks: access.can("product:view"),
    canRebuildIndex: access.isSuperAdmin
  };
}

/** Order page button visibility. The backend enforces the same rules. */
export interface OrderAbilities {
  /** Sync status, manual sync and the import entries. Requires order:manage. */
  canManage: boolean;
  /** Open a creator detail drawer from an order row. Requires kol:view. */
  canViewKol: boolean;
}

export function orderAbilities(user: AbilitySubject | null | undefined): OrderAbilities {
  const access = accessOf(user);
  if (!user) return { canManage: false, canViewKol: false };
  return {
    canManage: access.can("order:manage"),
    canViewKol: access.can("kol:view")
  };
}

/** List export button. */
export function canExport(user: AbilitySubject | null | undefined): boolean {
  return accessOf(user).can("data:export");
}
