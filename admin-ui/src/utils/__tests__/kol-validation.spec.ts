import { describe, expect, it } from "vitest";
import {
  validateCountry,
  validateKolEmail,
  validateKolName,
  validateRemark,
  validateSocialAccount
} from "../kol-validation";

const account = { platform: "TikTok", handle: "@a", url: "", followers: "" };

describe("KOL form validation", () => {
  it("checks name, email and country like the backend", () => {
    expect(validateKolName("")).toBe("请输入姓名");
    expect(validateKolName("名".repeat(64))).toBeNull();
    expect(validateKolName("名".repeat(65))).toContain("64");
    expect(validateKolEmail("a@example.com")).toBeNull();
    expect(validateKolEmail("a@localhost")).toBe("邮箱格式不正确");
    expect(validateKolEmail(`${"a".repeat(117)}@example.com`)).toContain("128");
    expect(validateCountry("us")).toBeNull();
    expect(validateCountry("USA")).toContain("两位字母");
    expect(validateRemark("x".repeat(500))).toBeNull();
    expect(validateRemark("x".repeat(501))).toContain("500");
  });

  it("checks social accounts", () => {
    expect(validateSocialAccount(account)).toBeNull();
    expect(validateSocialAccount({ ...account, platform: "" })).toBe("请输入社媒平台");
    expect(validateSocialAccount({ ...account, handle: " " })).toBe("请输入社媒账号");
    expect(validateSocialAccount({ ...account, url: "http://x.io" })).toContain("https://");
    expect(validateSocialAccount({ ...account, url: "https://x.io/a" })).toBeNull();
    expect(validateSocialAccount({ ...account, followers: "-1" })).toContain("非负整数");
    expect(validateSocialAccount({ ...account, followers: "12000" })).toBeNull();
  });
});
