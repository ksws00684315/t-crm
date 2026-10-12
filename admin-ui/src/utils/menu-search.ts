import type { MenuItem } from "@/utils/permission";

export const RECENT_MENUS_KEY = "tcrm.admin.recent";
export const RECENT_MENU_LIMIT = 6;

export interface MenuEntry {
  path: string;
  title: string;
  group: string;
}

interface RecentFile {
  v: 1;
  users: Record<string, string[]>;
}

/** One searchable row per menu the user can open. Group titles stay on the row, not as results. */
export function flattenMenu(items: readonly MenuItem[]): MenuEntry[] {
  const entries: MenuEntry[] = [];
  for (const item of items) {
    if (item.children && item.children.length > 0) {
      for (const child of item.children) {
        entries.push({ path: child.path, title: child.title, group: item.title });
      }
      continue;
    }
    entries.push({ path: item.path, title: item.title, group: "" });
  }
  return entries;
}

/** Case-insensitive match on the current-language menu name or its group. */
export function matchMenus(entries: readonly MenuEntry[], query: string): MenuEntry[] {
  const needle = query.trim().toLowerCase();
  if (!needle) return [];
  return entries.filter(
    entry =>
      entry.title.toLowerCase().includes(needle) || entry.group.toLowerCase().includes(needle)
  );
}

export function loadRecentMenus(userId: string): string[] {
  if (!userId) return [];
  try {
    const parsed = JSON.parse(localStorage.getItem(RECENT_MENUS_KEY) ?? "") as RecentFile;
    if (parsed?.v !== 1 || typeof parsed.users !== "object" || parsed.users == null) return [];
    const paths = parsed.users[userId];
    if (!Array.isArray(paths)) return [];
    return paths.filter(path => typeof path === "string").slice(0, RECENT_MENU_LIMIT);
  } catch {
    return [];
  }
}

/** Puts the path first for this user and keeps other users' lists. */
export function rememberRecentMenu(userId: string, path: string): string[] {
  if (!userId || !path) return [];
  const next = [path, ...loadRecentMenus(userId).filter(item => item !== path)].slice(
    0,
    RECENT_MENU_LIMIT
  );
  try {
    let file: RecentFile = { v: 1, users: {} };
    const raw = localStorage.getItem(RECENT_MENUS_KEY);
    if (raw) {
      const parsed = JSON.parse(raw) as RecentFile;
      if (parsed?.v === 1 && parsed.users && typeof parsed.users === "object") file = parsed;
    }
    file.users[userId] = next;
    localStorage.setItem(RECENT_MENUS_KEY, JSON.stringify(file));
  } catch {
    // Storage can throw in private mode. The dialog still opens the menu.
  }
  return next;
}
