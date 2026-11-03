import { readdirSync, readFileSync, statSync } from "node:fs";
import { join, relative } from "node:path";
import { describe, expect, it } from "vitest";
import enUS from "../en-US";
import zhCN from "../zh-CN";

/**
 * Static t("…") / $t("…") keys must exist in both message packs (L-104).
 * Dynamic keys (template literals, concatenation) go in ALLOWLIST with a reason.
 */
const ALLOWLIST: Readonly<Record<string, string>> = {
  // Example shape kept empty on purpose: every dynamic call site must document why it is skipped.
};

/** Matches t("a.b") / $t('a.b') / t("a.b", …) — not template literals. */
const STATIC_KEY = /\b(?:\$t|t)\(\s*(['"])([a-zA-Z][a-zA-Z0-9_.]*)\1/g;

function flattenKeys(value: object, prefix = ""): Set<string> {
  const keys = new Set<string>();
  for (const [key, child] of Object.entries(value)) {
    const path = prefix ? `${prefix}.${key}` : key;
    if (child !== null && typeof child === "object") {
      for (const nested of flattenKeys(child as object, path)) keys.add(nested);
    } else {
      keys.add(path);
    }
  }
  return keys;
}

function sourceFiles(dir: string): string[] {
  const found: string[] = [];
  for (const name of readdirSync(dir)) {
    const path = join(dir, name);
    if (statSync(path).isDirectory()) {
      if (name === "locales" || name === "__tests__" || name === "node_modules") continue;
      found.push(...sourceFiles(path));
      continue;
    }
    if (name.endsWith(".vue") || name.endsWith(".ts")) found.push(path);
  }
  return found;
}

function collectStaticKeys(root: string): Map<string, string[]> {
  const refs = new Map<string, string[]>();
  for (const file of sourceFiles(root)) {
    const rel = relative(root, file).replaceAll("\\", "/");
    if (rel in ALLOWLIST) continue;
    const text = readFileSync(file, "utf8");
    STATIC_KEY.lastIndex = 0;
    let match: RegExpExecArray | null;
    while ((match = STATIC_KEY.exec(text))) {
      const key = match[2];
      const hits = refs.get(key) ?? [];
      hits.push(rel);
      refs.set(key, hits);
    }
  }
  return refs;
}

describe("locale key references (L-104)", () => {
  const zhKeys = flattenKeys(zhCN);
  const enKeys = flattenKeys(enUS);

  it("every static t()/ $t() key exists in zh-CN and en-US", () => {
    const root = join(process.cwd(), "src");
    const refs = collectStaticKeys(root);
    expect(refs.size).toBeGreaterThan(0);

    const missing: string[] = [];
    for (const [key, files] of [...refs.entries()].sort(([a], [b]) => a.localeCompare(b))) {
      if (!zhKeys.has(key) || !enKeys.has(key)) {
        missing.push(`${key} (from ${files[0]}${files.length > 1 ? ` +${files.length - 1}` : ""})`);
      }
    }
    expect(missing).toEqual([]);
  });

  it("ALLOWLIST entries each carry a reason string", () => {
    for (const [path, reason] of Object.entries(ALLOWLIST)) {
      expect(reason.trim().length, path).toBeGreaterThan(0);
    }
  });
});
