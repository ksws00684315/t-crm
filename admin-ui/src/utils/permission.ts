import type { RouteRecordRaw } from "vue-router";
import type { CompanyEdition, DataScope, PermissionCode, PostTemplate, Role } from "@/api/types";
import { i18n, translate } from "@/locales";

/** Account the menu, the guard and the button helpers all read. Role names stay inside this module. */
export interface AbilitySubject {
  /** Null when the stored value is one this build does not know yet (#106). */
  role: Role | null;
  /** Null for the platform administrator or an unknown stored value (#106). */
  dataScope: DataScope | null;
  permissions?: readonly string[] | null;
  postTemplate?: PostTemplate | null;
  /** 1 enabled, 0 disabled; only the claim check needs it, so callers may omit it. */
  status?: 0 | 1;
  /** Edition of the company the account currently works in (ADR-038). */
  edition?: CompanyEdition | null;
}

/** What a signed-in session may do. Pages use these flags instead of comparing role names. */
export interface AccessProfile {
  isSuperAdmin: boolean;
  /** Staff whose data scope is OWN. A super administrator is never own-scoped. */
  isOwnScope: boolean;
  isPlatformAdmin: boolean;
  /** Edition of the current company; the distribution product when unknown. */
  edition: CompanyEdition;
  /** True for every code of the current edition when the account is a super administrator. */
  can: (code: PermissionCode) => boolean;
}

export const NO_ACCESS: AccessProfile = {
  isSuperAdmin: false,
  isOwnScope: false,
  isPlatformAdmin: false,
  edition: "DISTRIBUTION",
  can: () => false
};

/** Edition a subject belongs to; a missing or unknown value reads as the distribution product. */
export function editionOf(
  user: { edition?: CompanyEdition | null } | null | undefined
): CompanyEdition {
  return user?.edition === "KOL_LIBRARY" ? "KOL_LIBRARY" : "DISTRIBUTION";
}

export function accessOf(user: AbilitySubject | null | undefined): AccessProfile {
  if (!user) return NO_ACCESS;
  const isSuperAdmin = user.role === "SUPER_ADMIN";
  const isPlatformAdmin = user.role === "PLATFORM_ADMIN";
  const isOwnScope = user.role === "STAFF" && user.dataScope === "OWN";
  const edition = editionOf(user);
  const owned = new Set(user.permissions ?? []);
  return {
    isSuperAdmin,
    isOwnScope,
    isPlatformAdmin,
    edition,
    can: code =>
      // The backend limits a super administrator to the codes of its edition; the screen does the
      // same, so a menu entry cannot appear that the company could not use (ADR-038 §2.4).
      isSuperAdmin ? EDITION_PERMISSION_CODES[edition].includes(code) : owned.has(code)
  };
}

/** Role name in the current language. Resolved at call time so a switch updates open screens. */
export function roleLabel(role: Role | null | undefined): string {
  // Null (unknown stored value, #106) renders the dash like the account list does.
  return role ? translate(`role.${role}`) : translate("post.none");
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
export function isStaff(user: { role: Role | null } | null | undefined): boolean {
  return user?.role === "STAFF";
}

/** Company super administrator. Not the signed-in session flag; use this for a row in a list. */
export function isSuperAdminAccount(user: { role: Role | null } | null | undefined): boolean {
  return user?.role === "SUPER_ADMIN";
}

/** Account types a company super administrator may assign. Platform administrators are not here. */
export const SELECTABLE_ROLES: readonly Exclude<Role, "PLATFORM_ADMIN">[] = [
  "SUPER_ADMIN",
  "STAFF"
];

export const POST_TEMPLATES: readonly PostTemplate[] = ["OPERATOR", "BD", "FINANCE"];

/**
 * Codes each edition may use (ADR-038 §2.4), mirroring the backend registry. The distribution entry
 * lists every code of the full product; the creator library only its two codes.
 */
export const EDITION_PERMISSION_CODES: Readonly<Record<CompanyEdition, readonly PermissionCode[]>> =
  {
    DISTRIBUTION: [
      "kol:view",
      "kol:edit",
      "kol:register-link",
      "kol:copy-token",
      "product:view",
      "product:edit",
      "product:status",
      "order:view",
      "order:manage",
      "commission:view",
      "commission:freeze",
      "payout-account:review",
      "withdrawal:review",
      "withdrawal:pay",
      "prepay:apply",
      "prepay:review",
      "prepay:finance-review",
      "prepay:pay",
      "dashboard:shop",
      "dashboard:team",
      "dashboard:kol",
      "data:export"
    ],
    KOL_LIBRARY: ["kol-library:view", "kol-library:edit"]
  };

/** Groups from `GET /system/permissions`, in the order the permission picker renders them. */
export const PERMISSION_GROUP_ORDER = [
  "KOL",
  "PRODUCT",
  "ORDER",
  "FUNDS",
  "DASHBOARD",
  "OTHER",
  "KOL_LIBRARY"
] as const;

/** Group order of one edition's picker: a creator library shows its own group first. */
export function permissionGroupOrder(edition: CompanyEdition): readonly string[] {
  return edition === "KOL_LIBRARY"
    ? ["KOL_LIBRARY", ...PERMISSION_GROUP_ORDER.filter(group => group !== "KOL_LIBRARY")]
    : PERMISSION_GROUP_ORDER;
}

export interface PermissionChoice {
  code: string;
  group: string;
}

/**
 * Edit-style codes and the view code each needs (task 24 §4.4). Checking an edit code checks its
 * view code; clearing a view code clears the edit codes needing it. The relation is one level
 * deep and lives only in the pickers — the backend accepts any set it is sent.
 */
export const EDIT_PERMISSION_DEPENDENCIES: Readonly<Record<string, string>> = {
  "kol:edit": "kol:view",
  "product:edit": "product:view",
  "product:status": "product:view",
  "order:manage": "order:view",
  "payout-account:review": "commission:view",
  "kol-library:edit": "kol-library:view"
};

/** One checkbox flip on a permission set, applying the dependency rules above. */
export function togglePermission(
  current: readonly string[],
  code: string,
  checked: boolean
): string[] {
  if (checked) {
    const next = new Set(current);
    next.add(code);
    const required = EDIT_PERMISSION_DEPENDENCIES[code];
    if (required) next.add(required);
    return [...next];
  }
  const dropped = new Set([code]);
  for (const [edit, view] of Object.entries(EDIT_PERMISSION_DEPENDENCIES)) {
    if (view === code) dropped.add(edit);
  }
  return current.filter(item => !dropped.has(item));
}

export interface PermissionGroupView {
  group: string;
  items: PermissionChoice[];
}

/** Known groups first, then any group the API adds, skipping empty groups. */
export function groupPermissions(
  items: readonly PermissionChoice[],
  order: readonly string[] = PERMISSION_GROUP_ORDER
): PermissionGroupView[] {
  const buckets = new Map<string, PermissionChoice[]>();
  const extra: string[] = [];
  for (const group of order) buckets.set(group, []);
  for (const item of items) {
    if (!buckets.has(item.group)) {
      buckets.set(item.group, []);
      extra.push(item.group);
    }
    buckets.get(item.group)?.push(item);
  }
  return [...order, ...extra]
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
  /** Permission code required to open the route. A super administrator passes every code. */
  permission?: PermissionCode | readonly PermissionCode[];
  /**
   * Editions the route belongs to (ADR-038). Only the library pages declare `KOL_LIBRARY`; a route
   * without the flag belongs to the distribution product, so a new page cannot appear in the
   * library company by being forgotten.
   */
  editions?: readonly CompanyEdition[];
  superAdmin?: boolean;
  platformAdmin?: boolean;
  hideForOwn?: boolean;
  hideInMenu?: boolean;
  /** Company business page that stays closed to the platform administrator. */
  companyAccount?: boolean;
  /**
   * Tab title built at run time from the record (the creator detail tab shows the nickname). A
   * route with `dynamicTitle` reports it on the route meta before it is opened in a tab.
   */
  dynamicTitle?: boolean;
}

/** The editions a route belongs to; undeclared means the distribution product only. */
export function routeEditions(meta: RouteAccessMeta | undefined): readonly CompanyEdition[] {
  const declared = meta?.editions;
  return declared && declared.length > 0 ? declared : ["DISTRIBUTION"];
}

/** True when the route belongs to the edition of the company. */
export function editionAllowsRoute(
  meta: RouteAccessMeta | undefined,
  edition: CompanyEdition
): boolean {
  const allowed: readonly CompanyEdition[] = routeEditions(meta);
  return allowed.some(item => item === edition);
}

/** The permission codes a route asks for; empty when the route has none. */
function requiredCodes(meta: RouteAccessMeta | undefined): readonly PermissionCode[] {
  const permission = meta?.permission;
  // typeof, not Array.isArray: the latter cannot narrow a readonly array out of the union.
  return typeof permission === "string" ? [permission] : (permission ?? []);
}

/**
 * Whether a signed-in user may open a route: the route must belong to the company's edition and the
 * account must hold its permission code or role. An array `permission` means any one code suffices
 * (task 15b §4.2.1). Company home has no permission code: every company account may open it, and
 * the platform administrator may not. OWN hides `hideForOwn` routes.
 */
export function canAccessRoute(meta: RouteAccessMeta | undefined, access: AccessProfile): boolean {
  if (meta?.platformAdmin && meta?.superAdmin) {
    // Open to either administrator type, but a company account still needs the edition: the audit
    // log, for instance, is a distribution page the platform administrator also reads.
    if (access.isPlatformAdmin) return true;
    return editionAllowsRoute(meta, access.edition) && access.isSuperAdmin;
  }
  if (meta?.platformAdmin) return access.isPlatformAdmin;
  if (access.isPlatformAdmin) {
    if (meta?.companyAccount) return false;
    return !meta?.permission && !meta?.superAdmin && meta?.hideInMenu === true;
  }
  if (!editionAllowsRoute(meta, access.edition)) return false;
  if (meta?.superAdmin && !access.isSuperAdmin) return false;
  const codes = requiredCodes(meta);
  if (codes.length > 0 && !codes.some(code => access.can(code))) return false;
  if (meta?.hideForOwn && access.isOwnScope) return false;
  return true;
}

export interface MenuItem {
  path: string;
  title: string;
  icon?: string;
  children?: MenuItem[];
  /**
   * Pending-work counter shown next to the label (task 15b: pending payout-account reviews).
   * Only set by the layout; zero and undefined both render without a badge.
   */
  badge?: number;
}

/**
 * Side menu entries the session may open, in route order. Supports one level of grouping; a group
 * may sit anywhere in the tree (the creator library is a group of the layout like every other).
 */
export function buildMenu(routes: readonly RouteRecordRaw[], access: AccessProfile): MenuItem[] {
  const joined = (base: string, path: string): string =>
    path.startsWith("/") ? path : `${base.replace(/\/$/, "")}/${path}`;
  const group = (route: RouteRecordRaw, path: string): MenuItem | null => {
    const meta = route.meta;
    const children: MenuItem[] = [];
    for (const child of route.children ?? []) {
      const childPath = joined(path, child.path);
      const childMeta = child.meta;
      if (childMeta?.menuGroup) {
        const nested = group(child, childPath);
        if (nested) children.push(nested);
        continue;
      }
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
    if (children.length === 0 || !meta?.title) return null;
    return {
      path,
      title: translate(meta.title),
      icon: meta.icon as string | undefined,
      children
    };
  };
  const items: MenuItem[] = [];
  const visit = (list: readonly RouteRecordRaw[], base: string) => {
    for (const route of list) {
      const path = joined(base, route.path);
      const meta = route.meta;
      if (meta?.menuGroup) {
        const entry = group(route, path);
        if (entry) items.push(entry);
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
  /** Edit profiles, review, commission, cleanup and freeze. */
  canWrite: boolean;
  /** Pick a BD while creating or importing. Super administrator, or kol:edit outside OWN. */
  canAssignBd: boolean;
  /** Filter the list by BD. kol:view outside OWN. */
  canFilterBd: boolean;
  /** Show the caller's creator registration link. */
  canRegisterLink: boolean;
  /** Open the "invite creator" dialog: the BD conditions above, or the super administrator. */
  canInviteKol: boolean;
  /** Generate, reveal and revoke copy tokens. */
  canCopyToken: boolean;
}

export function kolAbilities(user: AbilitySubject | null | undefined): KolAbilities {
  const access = accessOf(user);
  if (!user) {
    return {
      canWrite: false,
      canAssignBd: false,
      canFilterBd: false,
      canRegisterLink: false,
      canInviteKol: false,
      canCopyToken: false
    };
  }
  const canEdit = access.can("kol:edit");
  const canRegisterLink =
    user.status === 1 &&
    user.role === "STAFF" &&
    access.isOwnScope &&
    canEdit &&
    access.can("kol:register-link");
  return {
    canWrite: canEdit,
    canAssignBd: access.isSuperAdmin || (canEdit && !access.isOwnScope),
    canFilterBd: access.can("kol:view") && !access.isOwnScope,
    canRegisterLink,
    // Task 33 §3.1 lets the super administrator keep an invitation code of their own.
    canInviteKol: access.isSuperAdmin || canRegisterLink,
    canCopyToken: access.can("kol:copy-token")
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

/** Creator-library button visibility (ADR-038, task 143 §3.2.5). The backend enforces the same. */
export interface LibraryAbilities {
  /** Add and edit creators, add and edit content, update the follow-up. */
  canEdit: boolean;
  /** Delete a creator or a content record: super administrators only (requirement P1-15). */
  canDelete: boolean;
}

export function libraryAbilities(user: AbilitySubject | null | undefined): LibraryAbilities {
  const access = accessOf(user);
  const inLibrary = access.edition === "KOL_LIBRARY";
  return {
    canEdit: inLibrary && access.can("kol-library:edit"),
    canDelete: inLibrary && access.isSuperAdmin
  };
}
