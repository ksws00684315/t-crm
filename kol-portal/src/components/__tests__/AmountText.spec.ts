import { mount } from "@vue/test-utils";
import { describe, expect, it } from "vitest";
import AmountText from "../AmountText.vue";

function markup(value: string): string {
  const host = document.createElement("div");
  const wrapper = mount(AmountText, { props: { value }, attachTo: host });
  const html = (host.firstElementChild?.innerHTML ?? "").replace(/<!--.*?-->/g, "").trim();
  wrapper.unmount();
  return html;
}

describe("AmountText", () => {
  it("keeps the formatted text unchanged", () => {
    expect(mount(AmountText, { props: { value: "¥12,345,678.90" } }).text()).toBe("¥12,345,678.90");
  });

  it("offers a line break only right after each thousands separator", () => {
    expect(markup("¥12,345,678.90")).toBe("¥12,<wbr>345,<wbr>678.90");
    expect(markup("-¥5.00")).toBe("-¥5.00");
  });
});
