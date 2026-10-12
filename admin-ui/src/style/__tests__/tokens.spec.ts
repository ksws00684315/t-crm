import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { describe, expect, it } from "vitest";

const css = readFileSync(resolve(__dirname, "../tokens.css"), "utf8");

function blocksFor(prefix: string): string[] {
  return css
    .split("}")
    .map(block => block.split("{")[0].trim())
    .filter(selector => selector.startsWith(prefix));
}

describe("dark button tokens", () => {
  it.each(["success", "warning", "danger", "primary"])(
    "only the solid %s button gets the deep fill with white text",
    type => {
      for (const selector of blocksFor(`html.dark .el-button--${type}`)) {
        const body = css.slice(css.indexOf(selector) + selector.length).split("}")[0];
        if (!body.includes("--el-button-text-color: #ffffff")) continue;
        expect(selector).toContain(":not(.is-plain)");
        expect(selector).toContain(":not(.is-link)");
        expect(selector).toContain(":not(.is-text)");
      }
    }
  );

  it("uses the brightened primary text token for dark link and text buttons", () => {
    const block = css.slice(css.indexOf("html.dark .el-button--primary.is-link"));
    const rule = block.split("}")[0];
    expect(rule).toContain("html.dark .el-button--primary.is-text");
    expect(rule).toContain("html.dark .el-link--primary");
    expect(rule).toContain("--el-button-text-color: var(--tcrm-color-primary-text)");
  });
});
