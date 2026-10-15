import { describe, expect, it } from "vitest";
import { safeRedirect, safeSheetUrl, validEmail, validPassword } from "../auth";

describe("auth input boundaries", () => {
  it.each(["a".repeat(10), " ".repeat(10), "~".repeat(72)])(
    "accepts printable ASCII passwords",
    value => {
      expect(validPassword(value)).toBe(true);
    }
  );
  it.each([
    "",
    "a".repeat(9),
    "a".repeat(73),
    "密码1234567890",
    "abcdefghij\n",
    "abcdefghij\r",
    "abcdefghi\x7f"
  ])("rejects invalid passwords", value => {
    expect(validPassword(value)).toBe(false);
  });
  it.each(["", "bad", "user@", "user @example.test"])("rejects invalid emails", value => {
    expect(validEmail(value)).toBe(false);
  });
  it("accepts an ordinary email", () => {
    expect(validEmail("creator@example.test")).toBe(true);
  });
  it.each([
    undefined,
    ["/account"],
    "https://evil.test",
    "//evil.test",
    "/\\evil.test",
    "/%2fevil.test",
    "/%5cevil.test",
    "/%0aevil",
    "/%",
    "/login?redirect=/account",
    "/reset"
  ])("rejects unsafe or auth-page redirects", value => {
    expect(safeRedirect(value)).toBe("/");
  });
  it("keeps a local target with query and hash", () => {
    expect(safeRedirect("/account?tab=password#form")).toBe("/account?tab=password#form");
  });
  it.each(["javascript:alert(1)", "data:text/html,test", "invalid", null])(
    "rejects unsafe sheet links",
    value => {
      expect(safeSheetUrl(value)).toBeUndefined();
    }
  );
  it("accepts a web sheet link", () => {
    expect(safeSheetUrl("https://example.test/sheet")).toBe("https://example.test/sheet");
  });
});
