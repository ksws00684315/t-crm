export const PAGE_SIZES = [10, 20, 50, 100] as const;
export type PageSize = (typeof PAGE_SIZES)[number];
export type TableDensity = "default" | "small" | "large";

export interface PrefColumn {
  key: string;
  visible: boolean;
}

export interface TablePrefs {
  v: 1;
  columns: PrefColumn[];
  density: TableDensity;
  pageSize: number;
}

const DENSITIES: readonly TableDensity[] = ["default", "small", "large"];

export function tablePrefsKey(tableId: string): string {
  return `tcrm.admin.table.${tableId}`;
}

export function defaultPrefs(keys: readonly string[], pageSize: number): TablePrefs {
  return {
    v: 1,
    columns: keys.map(key => ({ key, visible: true })),
    density: "default",
    pageSize
  };
}

/** Hides columns that opt in. Callers apply this only when the user has no saved prefs, and on reset. */
export function applyDefaultHidden(prefs: TablePrefs, hidden: ReadonlySet<string>): TablePrefs {
  if (hidden.size === 0) return prefs;
  return {
    ...prefs,
    columns: prefs.columns.map(column =>
      hidden.has(column.key) ? { ...column, visible: false } : column
    )
  };
}

/** True when localStorage holds a payload this table can merge. A bad payload counts as unsaved. */
export function hasSavedTablePrefs(tableId: string): boolean {
  try {
    const raw = localStorage.getItem(tablePrefsKey(tableId));
    if (!raw) return false;
    const parsed = JSON.parse(raw) as Partial<TablePrefs>;
    return parsed?.v === 1 && Array.isArray(parsed.columns);
  } catch {
    return false;
  }
}

function isPageSize(value: unknown): value is PageSize {
  return typeof value === "number" && (PAGE_SIZES as readonly number[]).includes(value);
}

function isDensity(value: unknown): value is TableDensity {
  return typeof value === "string" && (DENSITIES as readonly string[]).includes(value);
}

/**
 * Merges a saved preference with the current columns. Unknown columns are dropped. Columns the
 * saved payload has never seen are inserted, visible, at their default position. A bad payload
 * returns the defaults.
 */
export function mergePrefs(
  keys: readonly string[],
  saved: unknown,
  fallbackPageSize: number
): TablePrefs {
  const defaults = defaultPrefs(keys, fallbackPageSize);
  if (!saved || typeof saved !== "object") return defaults;
  const body = saved as Partial<TablePrefs>;
  if (body.v !== 1 || !Array.isArray(body.columns)) return defaults;
  const known = new Set(keys);
  const kept: PrefColumn[] = [];
  const seen = new Set<string>();
  for (const item of body.columns) {
    if (!item || typeof item !== "object") continue;
    const column = item as Partial<PrefColumn>;
    if (typeof column.key !== "string" || !known.has(column.key) || seen.has(column.key)) continue;
    seen.add(column.key);
    kept.push({ key: column.key, visible: column.visible !== false });
  }
  for (let index = 0; index < keys.length; index += 1) {
    const key = keys[index];
    if (seen.has(key)) continue;
    const previous = keys
      .slice(0, index)
      .reverse()
      .find(candidate => kept.some(column => column.key === candidate));
    const at = previous ? kept.findIndex(column => column.key === previous) + 1 : 0;
    kept.splice(at, 0, { key, visible: true });
  }
  return {
    v: 1,
    columns: kept,
    density: isDensity(body.density) ? body.density : "default",
    pageSize: isPageSize(body.pageSize) ? body.pageSize : fallbackPageSize
  };
}

/** Locked columns (the first column and the actions column) cannot be hidden. */
export function withLockedVisible(
  columns: readonly PrefColumn[],
  locked: ReadonlySet<string>
): PrefColumn[] {
  return columns.map(column => (locked.has(column.key) ? { ...column, visible: true } : column));
}

export function moveColumn(
  columns: readonly PrefColumn[],
  index: number,
  delta: -1 | 1,
  locked: ReadonlySet<string>
): PrefColumn[] {
  const target = index + delta;
  if (target < 0 || target >= columns.length) return [...columns];
  if (locked.has(columns[index].key) || locked.has(columns[target].key)) return [...columns];
  const next = [...columns];
  const [item] = next.splice(index, 1);
  next.splice(target, 0, item);
  return next;
}

export function loadTablePrefs(
  tableId: string,
  keys: readonly string[],
  fallbackPageSize: number
): TablePrefs {
  try {
    const raw = localStorage.getItem(tablePrefsKey(tableId));
    if (!raw) return defaultPrefs(keys, fallbackPageSize);
    return mergePrefs(keys, JSON.parse(raw) as unknown, fallbackPageSize);
  } catch {
    return defaultPrefs(keys, fallbackPageSize);
  }
}

export function saveTablePrefs(tableId: string, prefs: TablePrefs): void {
  try {
    localStorage.setItem(tablePrefsKey(tableId), JSON.stringify(prefs));
  } catch {
    // the table still uses the in-memory choice
  }
}

export function readPageSize(tableId: string, fallback: number): number {
  try {
    const raw = localStorage.getItem(tablePrefsKey(tableId));
    if (!raw) return fallback;
    const parsed = JSON.parse(raw) as Partial<TablePrefs>;
    if (parsed?.v !== 1 || !isPageSize(parsed.pageSize)) return fallback;
    return parsed.pageSize;
  } catch {
    return fallback;
  }
}
