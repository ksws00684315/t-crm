import { describe, expect, it } from "vitest";
import { routes } from "@/router/routes";
import enUS from "../en-US";
import zhCN from "../zh-CN";

/** Flat "a.b.c" key list of a message pack, in sorted order so compare is order-insensitive. */
function flattenKeys(value: object, prefix = ""): string[] {
  return Object.entries(value)
    .flatMap(([key, child]) => {
      const path = prefix ? `${prefix}.${key}` : key;
      return child !== null && typeof child === "object" ? flattenKeys(child, path) : [path];
    })
    .sort();
}

describe("message packs", () => {
  it("has exactly the same keys in en-US and zh-CN (typecheck guards this too)", () => {
    expect(flattenKeys(zhCN)).toEqual(flattenKeys(enUS));
  });

  it("has a message for every route meta.title key", () => {
    const keys = new Set(flattenKeys(enUS));
    const titles = routes.flatMap(collectTitles);
    expect(titles.length).toBeGreaterThan(0);
    for (const title of titles) {
      expect(keys.has(title)).toBe(true);
    }
  });
});

function collectTitles(route: (typeof routes)[number]): string[] {
  const own = typeof route.meta?.title === "string" ? [route.meta.title] : [];
  const childTitles = (route.children ?? []).flatMap(child =>
    collectTitles(child as (typeof routes)[number])
  );
  return [...own, ...childTitles];
}
