import { readFileSync } from "node:fs";
import { mount } from "@vue/test-utils";
import ElementPlus from "element-plus";
import { describe, expect, it } from "vitest";
import { i18n } from "@/locales";
import PrepayStatusTag from "../PrepayStatusTag.vue";

const STATUSES = [
  "WAITING_DETAILS",
  "PENDING_OPERATIONS_REVIEW",
  "PENDING_FINANCE_REVIEW",
  "APPROVED",
  "PAID",
  "REJECTED",
  "CANCELLED"
] as const;

describe("PrepayStatusTag", () => {
  it.each(STATUSES)("renders %s through a status class", status => {
    const wrapper = mount(PrepayStatusTag, {
      props: { status },
      global: { plugins: [ElementPlus, i18n] }
    });
    expect(wrapper.find(`.prepay-status-${status}`).exists()).toBe(true);
    wrapper.unmount();
  });

  it("uses semantic tokens instead of hard-coded colours", () => {
    const source = readFileSync("src/views/fund/prepay/PrepayStatusTag.vue", "utf8");
    expect(source).not.toMatch(/#[0-9a-fA-F]{3,8}\b/);
  });
});
