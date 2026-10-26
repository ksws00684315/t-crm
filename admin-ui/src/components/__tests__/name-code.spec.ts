import { mount } from "@vue/test-utils";
import { describe, expect, it } from "vitest";
import NameCode from "../NameCode.vue";

describe("NameCode identity display", () => {
  it("keeps the name and business ID separate and never interprets names as markup", () => {
    const wrapper = mount(NameCode, { props: { name: "<b>johnny</b>", code: "KOL-100032" } });
    expect(wrapper.get('[data-test="identity-name"]').text()).toBe("<b>johnny</b>");
    expect(wrapper.get('[data-test="identity-code"]').text()).toBe("KOL-100032");
    expect(wrapper.find("b").exists()).toBe(false);
  });

  it("does not invent a name when the response only provides a business ID", () => {
    const wrapper = mount(NameCode, { props: { code: "KOL-100032" } });
    expect(wrapper.get('[data-test="identity-name"]').text()).toBe("-");
    expect(wrapper.get('[data-test="identity-code"]').text()).toBe("KOL-100032");
  });
});
