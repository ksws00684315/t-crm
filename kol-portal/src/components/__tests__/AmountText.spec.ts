import { mount } from "@vue/test-utils";
import { describe, expect, it } from "vitest";
import AmountText from "../AmountText.vue";

function markup(value: string, currency?: string): string {
  const host = document.createElement("div");
  const wrapper = mount(AmountText, { props: { value, currency }, attachTo: host });
  const html = (host.firstElementChild?.innerHTML ?? "").replace(/<!--.*?-->/g, "").trim();
  wrapper.unmount();
  return html;
}

describe("AmountText", () => {
  it("formats the raw amount with the currency symbol", () => {
    expect(markup("12345678.90")).toBe("¥12,<wbr>345,<wbr>678.90");
    expect(markup("1234.56", "USD")).toBe("$1,<wbr>234.56");
    expect(markup("1234", "JPY")).toBe("1,<wbr>234 JPY");
  });

  it("offers a line break only right after each thousands separator", () => {
    expect(markup("-1234.56")).toBe("-¥1,<wbr>234.56");
    expect(markup("-5.00")).toBe("-¥5.00");
  });

  it("renders a dash for empty values", () => {
    expect(markup("")).toBe("-");
  });
});
