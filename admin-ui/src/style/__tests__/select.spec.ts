import { readFileSync, readdirSync, statSync } from "node:fs";
import { join, relative, resolve } from "node:path";
import { describe, expect, it } from "vitest";

const repoRoot = resolve(__dirname, "../../../..");
const adminIndex = readFileSync(resolve(__dirname, "../index.css"), "utf8");
const portalIndex = readFileSync(resolve(repoRoot, "kol-portal/src/style/index.css"), "utf8");
const adminTokens = readFileSync(resolve(__dirname, "../tokens.css"), "utf8");
const portalTokens = readFileSync(resolve(repoRoot, "kol-portal/src/style/tokens.css"), "utf8");

/** Anchor of the one shared skin (规范 13 §4.6); the block runs from it to the end of the file. */
const MARKER = "/* Select dropdown skin (规范 13 §4.6) */";

const SELECT_TOKENS = [
  "tcrm-color-text-disabled",
  "tcrm-color-disabled-bg",
  "tcrm-color-option-selected-bg",
  "tcrm-color-option-selected-hover-bg",
  "tcrm-select-panel-max-width",
  "tcrm-select-panel-max-height"
];

/** Roles the skin reaches through an Element Plus variable rather than a direct declaration. */
const MAPPED_TOKENS = ["tcrm-color-input-border-hover", "tcrm-color-disabled-border"];

const PAGE_DROPDOWN_SELECTOR = /\.el-select__(popper|dropdown)|\.el-select-dropdown/;

function skin(source: string, app: string): string {
  const start = source.indexOf(MARKER);
  expect(start, `${app} must keep the shared select skin marker`).toBeGreaterThan(-1);
  return source.slice(start).trimEnd();
}

function tokenValue(source: string, name: string): string | undefined {
  return source.match(new RegExp(`--${name}:\\s*([^;]+);`))?.[1]?.trim();
}

/** Every .vue/.css source of one app, so page-level dropdown overrides cannot hide in a page. */
function sourceFiles(appDir: string): string[] {
  const out: string[] = [];
  const walk = (dir: string) => {
    for (const name of readdirSync(dir)) {
      const full = join(dir, name);
      if (statSync(full).isDirectory()) {
        walk(full);
      } else if (/\.(vue|css)$/.test(name)) {
        out.push(full);
      }
    }
  };
  walk(resolve(repoRoot, appDir));
  return out;
}

/** Lines in one page stylesheet that would restyle the shared dropdown panel. */
function panelOverrides(file: string): string[] {
  const lines = readFileSync(file, "utf8").split("\n");
  const offenders: string[] = [];
  if (file.endsWith(".vue")) {
    let inStyle = false;
    lines.forEach((line, index) => {
      if (/<style\b/.test(line)) inStyle = true;
      if (inStyle && PAGE_DROPDOWN_SELECTOR.test(line)) {
        offenders.push(`${relative(repoRoot, file)}:${index + 1}: ${line.trim()}`);
      }
      if (/<\/style>/.test(line)) inStyle = false;
    });
    return offenders;
  }
  lines.forEach((line, index) => {
    if (PAGE_DROPDOWN_SELECTOR.test(line)) {
      offenders.push(`${relative(repoRoot, file)}:${index + 1}: ${line.trim()}`);
    }
  });
  return offenders;
}

describe("shared select dropdown skin (规范 13 §4.6)", () => {
  it("ships the same skin in the admin console and the creator portal", () => {
    expect(skin(portalIndex, "kol-portal/src/style/index.css")).toBe(
      skin(adminIndex, "admin-ui/src/style/index.css")
    );
  });

  it("keeps the select roles on tokens in both apps", () => {
    const block = skin(adminIndex, "admin-ui/src/style/index.css");
    for (const token of [...SELECT_TOKENS, ...MAPPED_TOKENS]) {
      const admin = tokenValue(adminTokens, token);
      expect(admin, `${token} must be defined`).toBeTruthy();
      expect(tokenValue(portalTokens, token), `${token} must match the portal`).toBe(admin);
    }
    for (const token of SELECT_TOKENS) {
      expect(block, `${token} must be used by the skin`).toContain(`var(--${token})`);
    }
    expect(tokenValue(adminTokens, "tcrm-select-panel-max-width")).toBe("480px");
    expect(tokenValue(adminTokens, "tcrm-select-panel-max-height")).toBe("280px");
    expect(block).toContain("min-height: var(--tcrm-control-height)");
    expect(block).toContain("border-radius: var(--tcrm-radius-control)");
    expect(block).toContain(".el-select__popper .el-popper__arrow");
  });

  it("keeps both identity lines in the selected colour", () => {
    const block = skin(adminIndex, "admin-ui/src/style/index.css");
    for (const part of [".name-code__name", ".name-code__code"]) {
      expect(block).toContain(`.el-select__popper .el-select-dropdown__item.is-selected ${part}`);
    }
    expect(block).toContain("color: var(--tcrm-color-primary-text)");
  });

  it("routes disabled controls and the hover border through the shared roles", () => {
    for (const source of [adminTokens, portalTokens]) {
      expect(source).toContain("--el-text-color-disabled: var(--tcrm-color-text-disabled)");
      expect(source).toContain("--el-disabled-text-color: var(--tcrm-color-text-disabled)");
      expect(source).toContain("--el-disabled-bg-color: var(--tcrm-color-disabled-bg)");
      expect(source).toContain("--el-disabled-border-color: var(--tcrm-color-disabled-border)");
      expect(source).toContain("--el-border-color-hover: var(--tcrm-color-input-border-hover)");
    }
    expect(tokenValue(adminTokens, "tcrm-color-input-border-hover")).toBe("#c0c4cc");
    expect(tokenValue(portalTokens, "tcrm-color-input-border-hover")).toBe("#c0c4cc");
  });

  it("keeps colours and font sizes of the skin in tokens", () => {
    const block = skin(adminIndex, "admin-ui/src/style/index.css");
    expect(block).not.toMatch(/#[0-9a-fA-F]{3,8}\b|rgba?\(/);
    expect(block).not.toMatch(/font-size:\s*\d/);
  });

  it("stops pages from restyling the dropdown panel themselves", () => {
    const offenders: string[] = [];
    for (const app of ["admin-ui/src", "kol-portal/src"]) {
      for (const file of sourceFiles(app)) {
        const rel = relative(repoRoot, file).replaceAll("\\", "/");
        if (rel.endsWith("/style/index.css") || rel.endsWith("/style/tokens.css")) continue;
        offenders.push(...panelOverrides(file));
      }
    }
    expect(
      offenders,
      "dropdown panel styling belongs to the shared skin, not to a page:\n" + offenders.join("\n")
    ).toEqual([]);
  });
});
