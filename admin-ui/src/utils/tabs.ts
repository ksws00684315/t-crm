/** Open tabs survive a browser refresh in this sessionStorage key. */
export const TABS_STORAGE_KEY = "tcrm.admin.tabs";

export const TAB_LIMIT = 12;

export interface TabRecord {
  path: string;
  titleKey: string;
  affix: boolean;
  componentName: string;
}

export interface TabSnapshot {
  v: 1;
  active: string;
  tabs: TabRecord[];
}

export function homeAffix(): TabRecord {
  return { path: "/home", titleKey: "route.home", affix: true, componentName: "HomePage" };
}

export function tenantAffix(): TabRecord {
  return {
    path: "/platform/tenants",
    titleKey: "route.tenants",
    affix: true,
    componentName: "PlatformTenantPage"
  };
}

/** Platform administrators pin company management; every other role pins home. */
export function affixFor(platformAdmin: boolean): TabRecord {
  return platformAdmin ? tenantAffix() : homeAffix();
}

function snapshot(active: string, tabs: TabRecord[]): TabSnapshot {
  return { v: 1, active, tabs };
}

function evict(tabs: TabRecord[], active: string): TabRecord[] {
  const next = [...tabs];
  while (next.length > TAB_LIMIT) {
    const index = next.findIndex(tab => !tab.affix && tab.path !== active);
    if (index < 0) break;
    next.splice(index, 1);
  }
  return next;
}

/** One tab per path. Query strings are not part of the identity. The opened tab becomes active. */
export function openTab(current: TabSnapshot, tab: TabRecord): TabSnapshot {
  const existing = current.tabs.find(item => item.path === tab.path);
  const tabs = existing
    ? current.tabs.map(item => (item.path === tab.path ? { ...item, ...tab } : item))
    : evict([...current.tabs, tab], tab.path);
  return snapshot(tab.path, tabs);
}

/** Closing the active tab activates the right neighbor, otherwise the left. Affix tabs stay. */
export function closeTab(current: TabSnapshot, path: string): TabSnapshot {
  const index = current.tabs.findIndex(tab => tab.path === path);
  if (index < 0 || current.tabs[index].affix) return current;
  const tabs = current.tabs.filter(tab => tab.path !== path);
  const active =
    current.active === path
      ? (current.tabs[index + 1]?.path ?? current.tabs[index - 1]?.path ?? current.active)
      : current.active;
  return snapshot(active, tabs);
}

function subjectIndex(current: TabSnapshot, path: string): number {
  return current.tabs.findIndex(tab => tab.path === path);
}

function retain(
  current: TabSnapshot,
  subject: string,
  keep: (tab: TabRecord, index: number) => boolean
): TabSnapshot {
  const tabs = current.tabs.filter((tab, index) => tab.affix || keep(tab, index));
  const active = tabs.some(tab => tab.path === current.active)
    ? current.active
    : (tabs.find(tab => tab.path === subject)?.path ?? tabs[0]?.path ?? current.active);
  return snapshot(active, tabs);
}

export function closeOthers(current: TabSnapshot, path: string): TabSnapshot {
  return retain(current, path, tab => tab.path === path);
}

export function closeLeft(current: TabSnapshot, path: string): TabSnapshot {
  const index = subjectIndex(current, path);
  if (index < 0) return current;
  return retain(current, path, (_tab, itemIndex) => itemIndex >= index);
}

export function closeRight(current: TabSnapshot, path: string): TabSnapshot {
  const index = subjectIndex(current, path);
  if (index < 0) return current;
  return retain(current, path, (_tab, itemIndex) => itemIndex <= index);
}

export function closeAll(current: TabSnapshot): TabSnapshot {
  const tabs = current.tabs.filter(tab => tab.affix);
  return snapshot(tabs[0]?.path ?? current.active, tabs);
}

function isRecord(value: unknown): value is TabRecord {
  if (!value || typeof value !== "object") return false;
  const tab = value as Partial<TabRecord>;
  return (
    typeof tab.path === "string" &&
    typeof tab.titleKey === "string" &&
    typeof tab.componentName === "string" &&
    typeof tab.affix === "boolean"
  );
}

/**
 * Restores a saved tab list. Drops tabs the user can no longer open, pins the role's affix at the
 * left, and falls back to that affix when the payload is missing or unreadable.
 */
export function restoreTabs(
  raw: string | null,
  affix: TabRecord,
  canAccess: (path: string) => boolean
): TabSnapshot {
  const fallback = snapshot(affix.path, [affix]);
  if (!raw) return fallback;
  let parsed: unknown;
  try {
    parsed = JSON.parse(raw);
  } catch {
    return fallback;
  }
  if (!parsed || typeof parsed !== "object") return fallback;
  const body = parsed as Partial<TabSnapshot>;
  if (body.v !== 1 || !Array.isArray(body.tabs)) return fallback;
  const extras: TabRecord[] = [];
  for (const item of body.tabs) {
    if (!isRecord(item) || item.path === affix.path || item.affix) continue;
    if (!canAccess(item.path)) continue;
    extras.push({ ...item, affix: false });
  }
  const tabs = evict(
    [affix, ...extras],
    typeof body.active === "string" ? body.active : affix.path
  );
  const active = tabs.some(tab => tab.path === body.active) ? String(body.active) : affix.path;
  return snapshot(active, tabs);
}

export function loadStoredTabs(
  affix: TabRecord,
  canAccess: (path: string) => boolean
): TabSnapshot {
  try {
    return restoreTabs(sessionStorage.getItem(TABS_STORAGE_KEY), affix, canAccess);
  } catch {
    return restoreTabs(null, affix, canAccess);
  }
}

export function saveStoredTabs(value: TabSnapshot): void {
  try {
    sessionStorage.setItem(TABS_STORAGE_KEY, JSON.stringify(value));
  } catch {
    // a full or blocked sessionStorage still leaves the in-memory tabs usable
  }
}

export function clearStoredTabs(): void {
  try {
    sessionStorage.removeItem(TABS_STORAGE_KEY);
  } catch {
    // nothing further to clean
  }
}
