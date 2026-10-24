import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { mount } from "@vue/test-utils";
import ElementPlus from "element-plus";
import { describe, expect, it } from "vitest";
import { i18n } from "@/locales";
import CopyText from "../CopyText.vue";

const repoRoot = resolve(__dirname, "../../../..");

/** jsdom applies no CSS, so the R-05 overflow rules are asserted against the component source. */
const source = readFileSync(resolve(__dirname, "../CopyText.vue"), "utf8");

function styleBlock(): string {
  const match = source.match(/<style scoped>([\s\S]*?)<\/style>/);
  expect(match).not.toBeNull();
  return match![1];
}

describe("CopyText", () => {
  it("keeps the value on one line: ellipsis style rules are present (R-05)", () => {
    const css = styleBlock();
    expect(css).toContain(".copy-text__value");
    expect(css).toContain("white-space: nowrap");
    expect(css).toContain("text-overflow: ellipsis");
    expect(css).toContain("overflow: hidden");
    expect(css).toContain("min-width: 0");
  });

  it("ships the same styles as the creator portal copy (spec 11 §12, R-05)", () => {
    const portal = readFileSync(
      resolve(repoRoot, "kol-portal/src/components/CopyText.vue"),
      "utf8"
    );
    const portalMatch = portal.match(/<style scoped>([\s\S]*?)<\/style>/);
    expect(portalMatch).not.toBeNull();
    expect(portalMatch![1]).toBe(styleBlock());
  });

  it("renders the value with a native tooltip carrying the full text (R-05)", () => {
    const wrapper = mount(CopyText, {
      props: { value: "KPL-4A72A9EC1732" },
      global: { plugins: [ElementPlus, i18n] }
    });
    const value = wrapper.get("[data-test=value]");
    expect(value.classes()).toContain("copy-text__value");
    expect(value.attributes("title")).toBe("KPL-4A72A9EC1732");
  });

  it("omits the tooltip when there is no value", () => {
    const wrapper = mount(CopyText, { global: { plugins: [ElementPlus, i18n] } });
    expect(wrapper.find("[data-test=value]").attributes("title")).toBeUndefined();
    expect(wrapper.find("[data-test=copy]").exists()).toBe(false);
  });
});
