import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { describe, expect, it } from "vitest";

/**
 * Style assertions on the component source: scoped styles are not applied in jsdom, so the
 * declarations themselves are checked. They guard against the auth links running together again
 * (task 58 review) and against falling back to the browser's default blue underlined links.
 */
const source = readFileSync(resolve(__dirname, "../AuthShell.vue"), "utf8");

function ruleBody(selector: string): string {
  const start = source.indexOf(`${selector} {`);
  if (start < 0) return "";
  return source.slice(start, source.indexOf("}", start) + 1);
}

describe("AuthShell links", () => {
  it("separates the sign-up, forgot and back-to-login links with a token gap", () => {
    const body = ruleBody(":deep(.auth-links)");
    expect(body).toContain("display: flex");
    expect(body).toContain("gap: var(--tcrm-space-4)");
  });

  it("colours links with the primary text token and leaves underline to hover", () => {
    const link = ruleBody(":deep(a)");
    expect(link).toContain("color: var(--tcrm-color-primary-text)");
    expect(link).toContain("text-decoration: none");
    expect(ruleBody(":deep(a:hover)")).toContain("text-decoration: underline");
  });
});
