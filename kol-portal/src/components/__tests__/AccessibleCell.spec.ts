import { mount } from "@vue/test-utils";
import ElementPlus from "element-plus";
import { nextTick } from "vue";
import { describe, expect, it } from "vitest";
import AccessibleCell from "../AccessibleCell.vue";

describe("AccessibleCell", () => {
  it("makes ordinary long text focusable and exposes its full value", async () => {
    const wrapper = mount(AccessibleCell, {
      slots: { default: "A long product name that may be truncated" },
      global: { plugins: [ElementPlus] }
    });
    await nextTick();
    const cell = wrapper.get(".accessible-cell");
    expect(cell.attributes("tabindex")).toBe("0");
    await cell.trigger("focusin");
    expect(wrapper.getComponent({ name: "ElTooltip" }).props("visible")).toBe(true);
    expect(wrapper.getComponent({ name: "ElTooltip" }).props("content")).toContain("product name");
    wrapper.unmount();
  });

  it("does not add a tab stop or cancel keys for an embedded link", async () => {
    const wrapper = mount(AccessibleCell, {
      slots: { default: '<a href="/example">A long product link</a>' },
      global: { plugins: [ElementPlus] }
    });
    await nextTick();
    expect(wrapper.get(".accessible-cell").attributes("tabindex")).toBe("-1");
    const link = wrapper.get("a").element;
    const event = new KeyboardEvent("keydown", { key: "Enter", bubbles: true, cancelable: true });
    link.dispatchEvent(event);
    expect(event.defaultPrevented).toBe(false);
    await wrapper.get("a").trigger("focusin");
    expect(wrapper.getComponent({ name: "ElTooltip" }).props("visible")).toBe(true);
    wrapper.unmount();
  });
});
