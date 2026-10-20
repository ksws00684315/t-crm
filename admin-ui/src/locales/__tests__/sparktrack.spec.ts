import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { describe, expect, it } from "vitest";
import { i18n } from "../index";
import zhCN from "../zh-CN";
import enUS from "../en-US";

const messages = {
  "zh-CN": zhCN,
  "en-US": enUS
} as unknown as Record<string, Record<string, unknown>>;
void i18n;

/**
 * The rename (task 32 §2.4): both frontends show SparkTrack everywhere the user can read;
 * T-CRM must be gone from the language packs and the document titles.
 */
const ALLOWED_INTERNAL = ["tcrm", "TCRM_"];

function flatten(values: unknown, prefix = ""): string[] {
  if (typeof values === "string") return [values];
  if (values && typeof values === "object") {
    return Object.entries(values).flatMap(([key, value]) => flatten(value, `${prefix}${key}.`));
  }
  return [];
}

describe("SparkTrack rename (task 32 §2.4)", () => {
  it("uses SparkTrack as the app name in every language", () => {
    expect((messages["zh-CN"]?.app as { title: string } | undefined)?.title).toBe(
      "SparkTrack 管理端"
    );
    expect((messages["en-US"]?.app as { title: string } | undefined)?.title).toBe(
      "SparkTrack Admin"
    );
  });

  it("keeps T-CRM out of every user-facing message", () => {
    for (const [locale, pack] of Object.entries(messages)) {
      for (const sentence of flatten(pack)) {
        expect(sentence.includes("T-CRM"), `${locale}: ${sentence}`).toBe(false);
      }
    }
  });

  it("keeps T-CRM out of the document title", () => {
    const html = readFileSync(resolve(__dirname, "../../../index.html"), "utf8");
    expect(html).not.toContain("T-CRM");
    expect(html).toContain("SparkTrack");
  });

  it("internal identifiers keep their tcrm names", () => {
    // Storage keys and CSS tokens are internal; the rename must not touch them.
    const sidebar = readFileSync(resolve(__dirname, "../../utils/sidebar.ts"), "utf8");
    expect(ALLOWED_INTERNAL.some(token => sidebar.includes(token))).toBe(true);
  });
});
