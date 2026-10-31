import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { describe, expect, it } from "vitest";

const style = (view: string) => {
  const source = readFileSync(resolve(__dirname, `../${view}/index.vue`), "utf8");
  return source.slice(source.indexOf("<style scoped>"));
};
const rule = (css: string, selector: string) => {
  const start = css.indexOf(`${selector} {`);
  return start < 0 ? "" : css.slice(start, css.indexOf("}", start));
};
const after = (css: string, query: string) => css.slice(css.indexOf(query));

/**
 * L-043: DataTable caps the table from its parent's height, so a parent that follows its content
 * shrinks with the table and the ceiling collapses to the 160px floor. jsdom has no layout, so these
 * pin the CSS contract; the rendered result is in the task 86 report.
 */
describe("desktop list pages give DataTable a parent height that does not follow the rows", () => {
  it.each([
    ["items", ".items-page", "420px"],
    ["orders", ".orders-page", "420px"],
    ["income/prepay", ".prepay-results", "0"]
  ])("%s fills the content area and stays content height on phones", (view, selector, floor) => {
    const css = style(view);
    expect(rule(css, selector)).toContain("height: 100%");
    expect(rule(css, selector)).toContain(`min-height: ${floor}`);
    expect(rule(after(css, "@media (max-width: 767px)"), selector)).toContain("height: auto");
  });

  it("funds keeps a stable record card under its summary, on desktop only", () => {
    const desktop = after(style("funds"), "@media (min-width: 768px)");
    expect(rule(desktop, ".records-card")).toContain("height: 560px");
    expect(rule(desktop, ".records-card :deep(.el-card__body)")).toContain("height: 100%");
  });
});
