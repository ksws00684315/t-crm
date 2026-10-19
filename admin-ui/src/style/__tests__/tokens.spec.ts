import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { describe, expect, it } from "vitest";

const css = readFileSync(resolve(__dirname, "../tokens.css"), "utf8");

describe("light-only tokens (dark switch removed, task 32)", () => {
  it("keeps the color scheme pinned to light", () => {
    expect(css).toContain("color-scheme: light");
    expect(css).not.toContain("color-scheme: dark");
  });

  it("drops every html.dark override with the removed dark mode", () => {
    expect(css).not.toContain("html.dark");
  });
});
