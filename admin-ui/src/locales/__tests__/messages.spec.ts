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
  it("has exactly the same keys in zh-CN and en-US (typecheck guards this too)", () => {
    expect(flattenKeys(enUS)).toEqual(flattenKeys(zhCN));
  });

  it("drops the unreferenced L-028 keys from both languages", () => {
    const removed = ["finance.applicationType", "finance.type", "route.payoutAccounts"];
    for (const pack of [zhCN, enUS]) {
      const keys = flattenKeys(pack);
      expect(keys.filter(key => removed.includes(key))).toEqual([]);
    }
  });

  it("keeps the KOL word out of user-facing English copy; Creator is the term (task 24 §4.3)", () => {
    const offenders: string[] = [];
    const visit = (value: object, prefix = "") => {
      for (const [key, child] of Object.entries(value)) {
        const path = prefix ? `${prefix}.${key}` : key;
        if (child !== null && typeof child === "object") {
          visit(child as object, path);
        } else if (typeof child === "string" && /\bKOLs?\b/i.test(stripCodeSamples(child))) {
          offenders.push(`${path}: ${child}`);
        }
      }
    };
    // Word boundaries keep code samples out; since ADR-023 the code itself contains a hyphen
    // ("KOL-ASD001"), so the samples are removed first and everything else stays strict.
    visit(enUS);
    expect(offenders).toEqual([]);
  });

  it("has a message for every route meta.title key", () => {
    const keys = new Set(flattenKeys(zhCN));
    const titles = routes.flatMap(collectTitles);
    expect(titles.length).toBeGreaterThan(0);
    for (const title of titles) {
      expect(keys.has(title)).toBe(true);
    }
  });
});

/** Removes literal creator-code samples (ADR-023 §1) so only the KOL *word* is checked. */
function stripCodeSamples(text: string): string {
  return text.replace(/KOL-[A-Z]{3}[0-9]{3,8}/g, "");
}

function collectTitles(route: (typeof routes)[number]): string[] {
  const own = typeof route.meta?.title === "string" ? [route.meta.title] : [];
  const childTitles = (route.children ?? []).flatMap(child =>
    collectTitles(child as (typeof routes)[number])
  );
  return [...own, ...childTitles];
}
