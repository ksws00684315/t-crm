import { beforeEach, describe, expect, it, vi } from "vitest";
import {
  applyDefaultHidden,
  defaultPrefs,
  loadTablePrefs,
  mergePrefs,
  moveColumn,
  readPageSize,
  saveTablePrefs,
  tablePrefsKey,
  withLockedVisible,
  type TablePrefs
} from "../table-prefs";

const keys = ["code", "name", "status", "actions"];
const locked = new Set(["code", "actions"]);

describe("table prefs", () => {
  beforeEach(() => localStorage.clear());

  it("hides, reorders, and resets to the default columns", () => {
    const hidden = withLockedVisible(
      defaultPrefs(keys, 20).columns.map(column =>
        column.key === "name" ? { ...column, visible: false } : column
      ),
      locked
    );
    expect(hidden.find(column => column.key === "name")?.visible).toBe(false);
    const moved = moveColumn(hidden, 2, -1, locked);
    expect(moved.map(column => column.key)).toEqual(["code", "status", "name", "actions"]);
    expect(moveColumn(moved, 0, 1, locked).map(column => column.key)).toEqual([
      "code",
      "status",
      "name",
      "actions"
    ]);
    expect(defaultPrefs(keys, 20).columns.every(column => column.visible)).toBe(true);
  });

  it("hides only the columns that opt in", () => {
    const applied = applyDefaultHidden(
      defaultPrefs(["jobCode", "kolId", "actions"], 20),
      new Set(["kolId"])
    );
    expect(applied.columns.find(column => column.key === "kolId")?.visible).toBe(false);
    expect(applied.columns.find(column => column.key === "jobCode")?.visible).toBe(true);
    expect(
      applyDefaultHidden(defaultPrefs(keys, 20), new Set()).columns.every(column => column.visible)
    ).toBe(true);
  });

  it("forces locked columns to stay visible", () => {
    const forced = withLockedVisible(
      [
        { key: "code", visible: false },
        { key: "actions", visible: false }
      ],
      locked
    );
    expect(forced.every(column => column.visible)).toBe(true);
  });

  it("stores each table under its own key", () => {
    saveTablePrefs("kol", defaultPrefs(["code"], 20));
    saveTablePrefs("users", { ...defaultPrefs(["username"], 50), pageSize: 50 });
    expect(localStorage.getItem(tablePrefsKey("kol"))).toContain("code");
    expect(readPageSize("users", 20)).toBe(50);
    expect(readPageSize("kol", 20)).toBe(20);
  });

  it("drops unknown columns and shows new ones by default", () => {
    const saved: TablePrefs = {
      v: 1,
      columns: [
        { key: "code", visible: true },
        { key: "gone", visible: true },
        { key: "name", visible: false }
      ],
      density: "small",
      pageSize: 50
    };
    const merged = mergePrefs(["code", "name", "email", "actions"], saved, 20);
    expect(merged.columns.map(column => column.key)).toEqual(["code", "name", "email", "actions"]);
    expect(merged.columns.find(column => column.key === "name")?.visible).toBe(false);
    expect(merged.columns.find(column => column.key === "email")?.visible).toBe(true);
    expect(merged.density).toBe("small");
    expect(merged.pageSize).toBe(50);
  });

  it("returns defaults for a bad version or corrupt JSON", () => {
    expect(mergePrefs(keys, { v: 2, columns: [] }, 20)).toEqual(defaultPrefs(keys, 20));
    localStorage.setItem(tablePrefsKey("kol"), "{");
    expect(loadTablePrefs("kol", keys, 20)).toEqual(defaultPrefs(keys, 20));
    expect(readPageSize("kol", 20)).toBe(20);
  });

  it("keeps density and page size across a reload", () => {
    const prefs: TablePrefs = { ...defaultPrefs(keys, 20), density: "large", pageSize: 100 };
    saveTablePrefs("kol", prefs);
    expect(loadTablePrefs("kol", keys, 20)).toEqual(prefs);
    expect(readPageSize("kol", 20)).toBe(100);
  });

  it("does not throw when storage is blocked", () => {
    vi.spyOn(Storage.prototype, "getItem").mockImplementation(() => {
      throw new Error("blocked");
    });
    vi.spyOn(Storage.prototype, "setItem").mockImplementation(() => {
      throw new Error("blocked");
    });
    expect(loadTablePrefs("kol", keys, 20)).toEqual(defaultPrefs(keys, 20));
    expect(() => saveTablePrefs("kol", defaultPrefs(keys, 20))).not.toThrow();
    expect(readPageSize("kol", 20)).toBe(20);
  });
});
