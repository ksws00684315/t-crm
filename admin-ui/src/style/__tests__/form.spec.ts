import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { describe, expect, it } from "vitest";

const indexCss = readFileSync(resolve(__dirname, "../index.css"), "utf8");

/** Collapses whitespace so the assertions survive formatting-only edits. */
function rule(selector: string): string {
  const start = indexCss.indexOf(`${selector} {`);
  expect(start, `${selector} must exist in admin-ui/src/style/index.css`).toBeGreaterThan(-1);
  return indexCss.slice(start, indexCss.indexOf("}", start)).replace(/\s+/g, " ");
}

describe("shared form and select focus rules (任务 123)", () => {
  it("keeps field errors in the flow so they never overlap the next field (规范 13 §4.6)", () => {
    // The shared help role is 14/22px, taller than Element Plus's 16px error line; keeping the
    // error absolutely positioned made it cover the next input (实测「新增用户」弹窗).
    expect(rule(".el-form-item__error")).toContain("position: static");
  });

  it("leaves the focus ring to the select trigger, not its inner input (规范 13 §4.6.1)", () => {
    // The filterable select's input is keyboard-focused, so the global :focus-visible rule drew a
    // second outline inside the trigger — a stray vertical line near its right edge.
    expect(rule(".el-select__input:focus-visible")).toContain("outline: none");
  });
});
