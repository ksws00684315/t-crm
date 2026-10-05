import { describe, expect, it } from "vitest";
import { ApiError } from "@/utils/http/error";
import {
  serverFieldErrors,
  validateEmail,
  validateExistingPassword,
  validateNewPassword,
  validateNickname,
  validateUsername
} from "../validation";

describe("validation mirrors the backend constraints", () => {
  it("username: 3-64 of letters, digits, underscore, dot, hyphen", () => {
    expect(validateUsername("ab")).not.toBeNull();
    expect(validateUsername("abc")).toBeNull();
    expect(validateUsername("a.b_c-9")).toBeNull();
    expect(validateUsername("a".repeat(64))).toBeNull();
    expect(validateUsername("a".repeat(65))).not.toBeNull();
    expect(validateUsername("张三丰")).not.toBeNull();
    expect(validateUsername("with space")).not.toBeNull();
    expect(validateUsername("")).not.toBeNull();
  });

  it("new password: 10-72 printable ASCII", () => {
    expect(validateNewPassword("123456789")).not.toBeNull();
    expect(validateNewPassword("1234567890")).toBeNull();
    expect(validateNewPassword("p".repeat(72))).toBeNull();
    expect(validateNewPassword("p".repeat(73))).not.toBeNull();
    expect(validateNewPassword("密码密码密码密码密码密码")).not.toBeNull();
    expect(validateNewPassword("tab\there-pass")).not.toBeNull();
    expect(validateNewPassword("with space ok!")).toBeNull();
  });

  it("existing password: present and at most 72", () => {
    expect(validateExistingPassword("x")).toBeNull();
    expect(validateExistingPassword("")).not.toBeNull();
    expect(validateExistingPassword("p".repeat(73))).not.toBeNull();
  });

  it("nickname: not blank, at most 64", () => {
    expect(validateNickname("  ")).not.toBeNull();
    expect(validateNickname("小王")).toBeNull();
    expect(validateNickname("n".repeat(65))).not.toBeNull();
  });

  it("email: optional, well formed, at most 254", () => {
    expect(validateEmail("")).toBeNull();
    expect(validateEmail(null)).toBeNull();
    expect(validateEmail("a@b.co")).toBeNull();
    expect(validateEmail("not-an-email")).not.toBeNull();
    expect(validateEmail(`${"a".repeat(250)}@b.co`)).not.toBeNull();
  });
});

describe("serverFieldErrors", () => {
  it("marks only known fields and uses the front end's own text", () => {
    const error = new ApiError({
      status: 400,
      errorCode: "COMMON_VALIDATION_FAILED",
      traceId: "t",
      fields: ["username", "somethingElse"]
    });
    expect(serverFieldErrors(error, ["username", "password"])).toEqual({
      username: "该字段格式不正确"
    });
  });

  it("returns nothing for other errors", () => {
    expect(serverFieldErrors(new Error("x"), ["username"])).toEqual({});
    expect(serverFieldErrors(null, ["username"])).toEqual({});
  });
});
