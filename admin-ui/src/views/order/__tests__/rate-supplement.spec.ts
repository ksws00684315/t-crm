import { mount, flushPromises, DOMWrapper } from "@vue/test-utils";
import ElementPlus, { ElMessageBox } from "element-plus";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { i18n } from "@/locales";
import * as api from "@/api/order/order";
import RateSupplementDialog from "../RateSupplementDialog.vue";
vi.mock("@/api/order/order");
function field(id: string) {
  return new DOMWrapper(document.body.querySelector(`[data-test="${id}"]`) as Element);
}
function open() {
  return mount(RateSupplementDialog, {
    props: {
      modelValue: true,
      orderId: "10",
      orderNo: "t185-order",
      itemId: "20",
      itemName: "t185-item"
    },
    global: { plugins: [ElementPlus, i18n] },
    attachTo: document.body
  });
}
describe("missing order rate", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    i18n.global.locale.value = "zh-CN";
    document.body.innerHTML = "";
  });
  afterEach(() => {
    vi.restoreAllMocks();
  });
  it("blocks empty required fields", async () => {
    const wrapper = open();
    await flushPromises();
    await field("rate-save").trigger("click");
    await flushPromises();
    expect(api.supplementOrderRate).not.toHaveBeenCalled();
    wrapper.unmount();
  });
  it("submits exactly one line after confirmation with a fractional rate", async () => {
    vi.spyOn(ElMessageBox, "confirm").mockImplementation(
      () => Promise.resolve("confirm") as ReturnType<typeof ElMessageBox.confirm>
    );
    vi.mocked(api.supplementOrderRate).mockResolvedValue(null);
    const wrapper = open();
    await flushPromises();
    await field("rate-percent").setValue("12.5");
    await field("rate-reason").setValue("missing history");
    await field("rate-save").trigger("click");
    await flushPromises();
    expect(api.supplementOrderRate).toHaveBeenCalledWith("10", {
      orderItemId: "20",
      rate: "0.1250",
      reason: "missing history"
    });
    expect(wrapper.emitted("saved")).toHaveLength(1);
    wrapper.unmount();
  });
});
