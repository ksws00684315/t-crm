import { describe, expect, it } from "vitest";
import { decideUnauthorized } from "../reauth";

describe("decideUnauthorized", () => {
  const base = {
    onLoginPage: false,
    loggedIn: true,
    errorCode: "AUTH_UNAUTHORIZED",
    dialogOpen: false
  };

  it("reopens nothing on the login page and sends a signed-out caller to login", () => {
    expect(decideUnauthorized({ ...base, onLoginPage: true })).toBe("ignore");
    expect(decideUnauthorized({ ...base, loggedIn: false })).toBe("login");
  });

  it("opens one dialog for an expired session and ignores a second 401", () => {
    expect(decideUnauthorized(base)).toBe("reauth");
    expect(decideUnauthorized({ ...base, dialogOpen: true })).toBe("ignore");
    expect(
      decideUnauthorized({ ...base, dialogOpen: true, errorCode: "AUTH_INVALID_CREDENTIALS" })
    ).toBe("ignore");
  });

  it("sends a disabled account and a lockout during reauth to the login page", () => {
    expect(decideUnauthorized({ ...base, errorCode: "AUTH_ACCOUNT_DISABLED" })).toBe("login");
    expect(
      decideUnauthorized({ ...base, dialogOpen: true, errorCode: "AUTH_ACCOUNT_LOCKED" })
    ).toBe("login");
  });
});
