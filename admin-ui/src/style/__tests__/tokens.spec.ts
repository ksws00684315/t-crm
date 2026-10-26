import { readFileSync, readdirSync, statSync } from "node:fs";
import { join, relative, resolve } from "node:path";
import { describe, expect, it } from "vitest";

const repoRoot = resolve(__dirname, "../../../..");
const css = readFileSync(resolve(__dirname, "../tokens.css"), "utf8");
const portalCss = readFileSync(resolve(repoRoot, "kol-portal/src/style/tokens.css"), "utf8");

/**
 * Spacing literals the portal may keep. Empty today: every margin, padding and gap uses a token.
 * Add an entry only with a reason, as `"kol-portal/src/...:line" -> "reason"`.
 */
const SPACING_ALLOWLIST = new Map<string, string>();

const RAW_COLOR = /#[0-9a-fA-F]{3,8}\b|rgba?\(/;
const RAW_SPACING =
  /(?:^|[^-\w])(?:margin|padding|gap|row-gap|column-gap)(?:-[a-z]+)*\s*:[^;]*\b\d+px\b/;

/** Every .vue/.css source of one app, minus the file allowed to define raw font-size values. */
function sourceFiles(appDir: string, allowed: string[] = []): string[] {
  const out: string[] = [];
  const walk = (dir: string) => {
    for (const name of readdirSync(dir)) {
      const full = join(dir, name);
      if (statSync(full).isDirectory()) {
        walk(full);
      } else if (
        /\.(vue|css)$/.test(name) &&
        !allowed.includes(relative(repoRoot, full).replaceAll("\\", "/"))
      ) {
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
  it("ships the same tokens.css in both apps, byte for byte", () => {
    expect(portalCss).toBe(css);
  });

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
      "tcrm-table-header-height",
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

  it("keeps raw colors out of portal components and styles", () => {
    const offenders: string[] = [];
    for (const file of sourceFiles("kol-portal/src", ["kol-portal/src/style/tokens.css"])) {
      readFileSync(file, "utf8")
        .split("\n")
        .forEach((line, index) => {
          if (RAW_COLOR.test(line)) {
            offenders.push(`${relative(repoRoot, file)}:${index + 1}: ${line.trim()}`);
          }
        });
    }
    expect(
      offenders,
      "portal colors must come from tokens.css variables:\n" + offenders.join("\n")
    ).toEqual([]);
  });

  it("uses spacing tokens instead of pixel margins, paddings and gaps in portal styles", () => {
    const offenders: string[] = [];
    for (const file of sourceFiles("kol-portal/src", ["kol-portal/src/style/tokens.css"])) {
      readFileSync(file, "utf8")
        .split("\n")
        .forEach((line, index) => {
          const location = `${relative(repoRoot, file)}:${index + 1}`;
          if (RAW_SPACING.test(line) && !SPACING_ALLOWLIST.has(location)) {
            offenders.push(`${location}: ${line.trim()}`);
          }
        });
    }
    expect(
      offenders,
      "portal spacing must use --tcrm-space-* tokens:\n" + offenders.join("\n")
    ).toEqual([]);
  });
});

describe("shared component styles (task 59 R-08)", () => {
  function styleBlock(path: string): string {
    const source = readFileSync(resolve(repoRoot, path), "utf8");
    const match = source.match(/<style scoped>([\s\S]*?)<\/style>/);
    expect(match, `${path} has a scoped style block`).not.toBeNull();
    return match![1].replace(/\r\n/g, "\n");
  }

  it("keeps the DataTable stylesheet identical across both apps", () => {
    expect(styleBlock("kol-portal/src/components/DataTable.vue")).toBe(
      styleBlock("admin-ui/src/components/DataTable.vue")
    );
  });

  it("keeps the CopyText stylesheet identical across both apps (R-05)", () => {
    expect(styleBlock("kol-portal/src/components/CopyText.vue")).toBe(
      styleBlock("admin-ui/src/components/CopyText.vue")
    );
  });
});
