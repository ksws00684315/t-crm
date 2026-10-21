import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { beforeEach, describe, expect, it } from "vitest";
import { i18n } from "@/locales";
import enUS from "@/locales/en-US";
import zhCN from "@/locales/zh-CN";
import { messageForErrorCode } from "../error-codes";

/** Reads the enum constants of the backend `ErrorCode` straight from its source file. */
function backendErrorCodes(): string[] {
  // `pnpm test` runs with admin-ui as the working directory.
  const file = resolve(
    process.cwd(),
    "../backend/src/main/java/com/tcrm/admin/shared/ErrorCode.java"
  );
  const source = readFileSync(file, "utf8");
  const start = source.indexOf("enum ErrorCode");
  // Constants end at the first `);` that closes a line; messages may themselves contain `;`.
  const open = source.indexOf("{", start) + 1;
  const end = source.slice(open).search(/\);\s*$/m);
  const body = source.slice(open, open + end + 1);
  return [...body.matchAll(/^\s*([A-Z][A-Z0-9_]+)\(\d{3},/gm)].map(match => match[1]);
}

const CODE = /^[A-Z][A-Z0-9_]*$/;

describe("error code messages", () => {
  beforeEach(() => {
    i18n.global.locale.value = "zh-CN";
  });

  it("covers every value of the backend ErrorCode enum in both languages", () => {
    const codes = backendErrorCodes();
    expect(codes.length).toBeGreaterThanOrEqual(14);
    const missingZh = codes.filter(code => !(code in zhCN.errors));
    const missingEn = codes.filter(code => !(code in enUS.errors));
    expect(missingZh).toEqual([]);
    expect(missingEn).toEqual([]);
    for (const code of codes) {
      const zh = zhCN.errors[code as keyof typeof zhCN.errors];
      const en = enUS.errors[code as keyof typeof enUS.errors];
      expect(zh).toMatch(/[\u4e00-\u9fa5]/);
      expect(en.length).toBeGreaterThan(0);
      expect(en).not.toMatch(/[\u4e00-\u9fa5]/);
    }
  });

  it("uses the Chinese text of the base pack by default", () => {
    expect(messageForErrorCode("USER_USERNAME_EXISTS")).toBe("用户名已存在");
    expect(messageForErrorCode("NO_SUCH_CODE")).toBe("操作失败，请稍后重试");
    expect(messageForErrorCode(null)).toBe("操作失败，请稍后重试");
    expect(messageForErrorCode("toString")).toBe("操作失败，请稍后重试");
  });

  it("switches known and generic text with the language", () => {
    i18n.global.locale.value = "en-US";
    expect(messageForErrorCode("USER_USERNAME_EXISTS")).toBe(enUS.errors.USER_USERNAME_EXISTS);
    expect(messageForErrorCode("NO_SUCH_CODE")).toBe(enUS.errors.generic);
    const backend = backendErrorCodes();
    const known = Object.keys(zhCN.errors).filter(key => CODE.test(key));
    const extra = known.filter(code => !backend.includes(code));
    // Message ships with this task; the enum constant arrives with its own task.
    // USER_PERMISSION_INVALID: task 06b. The two KOL codes: task 25a (this branch is 25b).
    const pending = [
      "USER_PERMISSION_INVALID",
      "KOL_PROMOTION_CONFIG_INCOMPLETE",
      "KOL_ALREADY_CLAIMED",
      "KOL_REVIEW_REQUIRED",
      "SYSTEM_CONFIG_INVALID"
    ].filter(code => !backend.includes(code));
    expect(extra.sort()).toEqual(pending.sort());
  });
});
