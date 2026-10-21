import { readFileSync, readdirSync, statSync } from "node:fs";
import { join, relative, resolve } from "node:path";
import { describe, expect, it } from "vitest";

const repoRoot = resolve(__dirname, "../../../..");
const css = readFileSync(resolve(__dirname, "../tokens.css"), "utf8");
const portalCss = readFileSync(
  resolve(__dirname, "../../../../kol-portal/src/style/index.css"),
  "utf8"
);

/** Every .vue/.css source of one app, minus the file allowed to define raw font-size values. */
function sourceFiles(appDir: string, allowed: string[] = []): string[] {
  const out: string[] = [];
  const walk = (dir: string) => {
    for (const name of readdirSync(dir)) {
      const full = join(dir, name);
      if (statSync(full).isDirectory()) {
        walk(full);
      } else if (/\.(vue|css)$/.test(name) && !allowed.includes(relative(repoRoot, full))) {
        out.push(full);
      }
    }
  };
  walk(resolve(repoRoot, appDir));
  return out;
}

function tokenValue(source: string, name: string): string | undefined {
  return source.match(new RegExp(`--${name}:\\s*([^;]+);`))?.[1]?.trim();
}

describe("light-only tokens (dark switch removed, task 32)", () => {
  it("keeps the color scheme pinned to light", () => {
    expect(css).toContain("color-scheme: light");
    expect(css).not.toContain("color-scheme: dark");
  });

  it("drops every html.dark override with the removed dark mode", () => {
    expect(css).not.toContain("html.dark");
  });
});

describe("shared frontend visual tokens", () => {
  it("keeps type, spacing, font stack, and control sizing in sync with the creator portal", () => {
    const tokens = [
      "tcrm-space-1",
      "tcrm-space-2",
      "tcrm-space-3",
      "tcrm-space-4",
      "tcrm-space-5",
      "tcrm-space-6",
      "tcrm-control-height",
      "tcrm-control-height-mobile",
      "tcrm-font-sans",
      "tcrm-font-size-body",
      "tcrm-line-height-body",
      "tcrm-font-size-title",
      "tcrm-line-height-title",
      "tcrm-font-size-section",
      "tcrm-line-height-section",
      "tcrm-font-size-help",
      "tcrm-line-height-help"
    ];

    for (const token of tokens) {
      expect(tokenValue(portalCss, token), `${token} must match both apps`).toBe(
        tokenValue(css, token)
      );
    }
  });

  it("applies typography to app descendants and body-mounted component overlays", () => {
    const styles = readFileSync(resolve(__dirname, "../index.css"), "utf8");
    expect(styles).toContain("#app *,");
    expect(styles).toContain(".el-overlay *");
    expect(styles).toContain(".el-popper *");
    expect(styles).toContain("font-size: var(--tcrm-font-size-body) !important");
  });

  it("declares no numeric font-size outside tokens.css in either app", () => {
    // Only the admin tokens file may spell out px values; every other style — including the
    // portal's index.css, which mirrors the tokens — must reference the CSS variables.
    const offenders: string[] = [];
    const pattern = /font-size:\s*\d+(?:\.\d+)?px/g;
    const files = [
      ...sourceFiles("admin-ui/src", ["admin-ui/src/style/tokens.css"]),
      ...sourceFiles("kol-portal/src")
    ];
    for (const file of files) {
      const text = readFileSync(file, "utf8");
      const lines = text.split("\n");
      lines.forEach((line, index) => {
        pattern.lastIndex = 0;
        if (pattern.test(line)) {
          offenders.push(`${relative(repoRoot, file)}:${index + 1}: ${line.trim()}`);
        }
      });
    }
    expect(
      offenders,
      "numeric font-size declarations must use tokens:\n" + offenders.join("\n")
    ).toEqual([]);
  });
});
