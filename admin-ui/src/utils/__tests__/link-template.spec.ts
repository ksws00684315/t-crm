import { describe, expect, it } from "vitest";
import {
  javaUrlEncode,
  previewTemplateUrl,
  validateSortOrder,
  validateTemplateCode,
  validateTemplateName,
  validateTemplateUrl
} from "../link-template";

const TEMPLATE = "https://shop.example/item/{PRODUCT_ID}?code={INVITE_CODE}";

describe("link template preview", () => {
  it("encodes the invite code the way Java URLEncoder does and leaves the product id raw", () => {
    expect(javaUrlEncode("A B~!*'()")).toBe("A+B%7E%21*%27%28%29");
    expect(previewTemplateUrl(TEMPLATE, "12 34", "A B~")).toEqual({
      url: "https://shop.example/item/12 34?code=A+B%7E",
      fallback: false
    });
  });

  it("falls back to the Weidian item page when the invite code is missing", () => {
    expect(previewTemplateUrl(TEMPLATE, "7849640325", "")).toEqual({
      url: "https://weidian.com/item.html?itemID=7849640325",
      fallback: true
    });
    expect(previewTemplateUrl("https://shop.example/item/{PRODUCT_ID}", "7849640325", "")).toEqual({
      url: "https://shop.example/item/7849640325",
      fallback: false
    });
  });
});

describe("link template validation", () => {
  it("checks the platform name, legacy name, url and sort order", () => {
    expect(validateTemplateCode(" weidian ")).toBeNull();
    expect(validateTemplateCode("a")).toContain("平台名");
    expect(validateTemplateCode("AB")).toContain("平台名");
    expect(validateTemplateCode("a_b")).toContain("平台名");
    expect(validateTemplateCode("a".repeat(33))).toContain("平台名");

    expect(validateTemplateName("")).toBe("请输入名称");
    expect(validateTemplateName("名".repeat(64))).toBeNull();
    expect(validateTemplateName("名".repeat(65))).toContain("64");

    expect(validateTemplateUrl("https://shop.example/{PRODUCT_ID}")).toBeNull();
    expect(validateTemplateUrl("http://shop.example/{PRODUCT_ID}")).toContain("{PRODUCT_ID}");
    expect(validateTemplateUrl("https://shop.example/item")).toContain("{PRODUCT_ID}");
    expect(validateTemplateUrl("https://shop.example/{PRODUCT_ID} x")).toContain("{PRODUCT_ID}");
    expect(validateTemplateUrl(`https://shop.example/${"a".repeat(500)}{PRODUCT_ID}`)).toContain(
      "512"
    );

    expect(validateSortOrder(0)).toBeNull();
    expect(validateSortOrder(9999)).toBeNull();
    expect(validateSortOrder(-1)).toContain("排序");
    expect(validateSortOrder(10000)).toContain("排序");
    expect(validateSortOrder(1.5)).toContain("排序");
    expect(validateSortOrder(null)).toContain("排序");
  });
});
