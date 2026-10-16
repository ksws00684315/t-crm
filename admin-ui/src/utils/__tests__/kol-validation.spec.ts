import { describe, expect, it } from "vitest";
import {
  validateCountry,
  validateInviteCode,
  validateKolEmail,
  validateKolName,
  validateRemark,
  validateSheetUrl,
  validateSocialAccount,
  validateWhatsapp,
  withPrimary,
  type SocialAccountInput
} from "../kol-validation";

const account: SocialAccountInput = {
  platform: "TikTok",
  handle: "@a",
  url: "",
  followers: "",
  primary: false
};

describe("KOL form validation", () => {
  it("checks name, email and country like the backend", () => {
    expect(validateKolName("")).toBe("请输入姓名");
    expect(validateKolName("名".repeat(64))).toBeNull();
    expect(validateKolName("名".repeat(65))).toContain("64");
    expect(validateKolEmail("a@example.com")).toBeNull();
    expect(validateKolEmail("a@localhost")).toBe("邮箱格式不正确");
    expect(validateKolEmail(`${"a".repeat(117)}@example.com`)).toContain("128");
    expect(validateCountry("")).toBeNull();
    expect(validateCountry("  ")).toBeNull();
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

  it("checks optional WhatsApp, invite code and sheet URL", () => {
    expect(validateWhatsapp("")).toBeNull();
    expect(validateWhatsapp("+8613800000000")).toBeNull();
    expect(validateWhatsapp("+1234567")).toContain("WhatsApp");
    expect(validateWhatsapp("+".padEnd(33, "1"))).toContain("WhatsApp");
    expect(validateWhatsapp("8613800000000")).toContain("WhatsApp");

    expect(validateInviteCode("")).toBeNull();
    expect(validateInviteCode("ABC_123-x")).toBeNull();
    expect(validateInviteCode("a".repeat(64))).toBeNull();
    expect(validateInviteCode("a".repeat(65))).toContain("邀请码");
    expect(validateInviteCode("has space")).toContain("邀请码");

    const sheet = "https://docs.google.com/spreadsheets/d/abc";
    expect(validateSheetUrl("")).toBeNull();
    expect(validateSheetUrl(sheet)).toBeNull();
    expect(validateSheetUrl(` ${sheet} `)).toBeNull();
    expect(validateSheetUrl("https://docs.google.com/document/d/abc")).toContain("spreadsheets");
    expect(validateSheetUrl(`${sheet}${"a".repeat(512)}`)).toContain("512");
  });

  it("keeps at most one primary social account", () => {
    const rows = [account, { ...account, handle: "@b", primary: true }];
    expect(withPrimary(rows, 0).map(row => row.primary)).toEqual([true, false]);
    expect(withPrimary(rows, 1).map(row => row.primary)).toEqual([false, false]);
    expect(withPrimary([], 0)).toEqual([]);
  });
});
