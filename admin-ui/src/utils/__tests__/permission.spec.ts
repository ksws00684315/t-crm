import { describe, expect, it } from "vitest";
import { routes } from "@/router/routes";
import { buildMenu, hasAnyRole } from "../permission";

describe("hasAnyRole", () => {
  it("allows everyone when no roles are required", () => {
    expect(hasAnyRole(undefined, ["FINANCE"])).toBe(true);
    expect(hasAnyRole([], [])).toBe(true);
  });

  it("requires at least one matching role", () => {
    expect(hasAnyRole(["SUPER_ADMIN"], ["OPERATOR"])).toBe(false);
    expect(hasAnyRole(["SUPER_ADMIN", "FINANCE"], ["FINANCE"])).toBe(true);
    expect(hasAnyRole(["SUPER_ADMIN"], [])).toBe(false);
  });
});

describe("buildMenu", () => {
  const titles = (roles: Parameters<typeof buildMenu>[1]) =>
    buildMenu(routes, roles).map(item => item.title);

  it("shows user management and the audit log only to the super administrator", () => {
    expect(titles(["SUPER_ADMIN"])).toEqual(["首页", "用户管理", "操作日志"]);
    expect(titles(["OPERATOR"])).toEqual(["首页"]);
    expect(titles(["FINANCE"])).toEqual(["首页"]);
  });

  it("builds absolute paths and hides the login, password and error pages", () => {
    const items = buildMenu(routes, ["SUPER_ADMIN"]);
    expect(items.map(item => item.path)).toEqual(["/home", "/system/users", "/system/oper-logs"]);
  });
});
