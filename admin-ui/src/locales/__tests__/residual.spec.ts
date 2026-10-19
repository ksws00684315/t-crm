import { readdirSync, readFileSync, statSync } from "node:fs";
import { join, relative } from "node:path";
import { describe, expect, it } from "vitest";

/**
 * Files allowed to contain Chinese. Each entry needs a reason. Empty means every scanned source
 * takes its UI copy from the message packs.
 */
const ALLOWLIST: Readonly<Record<string, string>> = {
  "views/system/oper-log/index.vue":
    "Lookup key only: the quick filter matches the Chinese word the backend writes into BD-transfer summaries (task 33 §3.3); the constant renders nothing itself."
};

const CHINESE = /[\u4e00-\u9fff]/;

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

describe("hard-coded UI copy", () => {
  it("keeps Chinese out of Vue and TypeScript sources outside locales and tests", () => {
    const root = join(process.cwd(), "src");
    const offenders: string[] = [];
    for (const file of sourceFiles(root)) {
      const rel = relative(root, file);
      if (rel in ALLOWLIST) continue;
      const lines = readFileSync(file, "utf8").split("\n");
      lines.forEach((line, index) => {
        if (CHINESE.test(line)) offenders.push(`${rel}:${index + 1}: ${line.trim()}`);
      });
    }
    expect(offenders).toEqual([]);
  });
});
