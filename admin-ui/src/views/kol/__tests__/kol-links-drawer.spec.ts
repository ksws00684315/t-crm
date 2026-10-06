import { flushPromises, mount } from "@vue/test-utils";
import ElementPlus, { ElMessage } from "element-plus";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import * as linkApi from "@/api/link/link";
import type { LinkVO } from "@/api/types";
import KolLinksDrawer from "../KolLinksDrawer.vue";

vi.mock("@/api/link/link");

const available: LinkVO = {
  kolItemId: "9007199254740993",
  productCode: "P000001",
  productName: "鞋",
  weidianItemId: "9001",
  linkUrl: "https://t.example.com/t/KOL0001/P000001",
  targetUrl: "https://weidian.com/item.html?itemID=9001",
  available: true,
  unavailableReasons: [],
  clicks30d: "12",
  validClicks30d: "7"
};

const unavailable: LinkVO = {
  ...available,
  kolItemId: "2",
  productCode: "P000002",
  linkUrl: "https://t.example.com/t/KOL0001/P000002",
  available: false,
  unavailableReasons: ["KOL_DISABLED", "PRODUCT_OFF_SHELF", "SHOP_DISABLED"],
  clicks30d: "0",
  validClicks30d: "0"
};

async function mountDrawer() {
  vi.mocked(linkApi.listLinks).mockResolvedValue({
    items: [available, unavailable],
    total: "42",
    page: 1,
    size: 20
  });
  const wrapper = mount(KolLinksDrawer, {
    props: { modelValue: false, kolCode: "KOL0001", kolName: "Alice" },
    global: { plugins: [ElementPlus] },
    attachTo: document.body
  });
  await wrapper.setProps({ modelValue: true });
  await flushPromises();
  return wrapper;
}

describe("KOL links drawer", () => {
  beforeEach(() => vi.mocked(linkApi.listLinks).mockClear());
  afterEach(() => {
    document.body.innerHTML = "";
  });

  it("loads the KOL links on open with status, reasons and click counts", async () => {
    const wrapper = await mountDrawer();
    expect(linkApi.listLinks).toHaveBeenCalledWith({
      kolCode: "KOL0001",
      productCode: undefined,
      page: 1,
      size: 20
    });
    const text = document.body.textContent ?? "";
    expect(text).toContain("https://t.example.com/t/KOL0001/P000001");
    expect(text).toContain("可用");
    expect(text).toContain("不可用：达人停用、商品下架、店铺停用");
    expect(text).toContain("12 / 7");
    expect(wrapper.findComponent({ name: "ElPagination" }).props("total")).toBe(42);
  });

  it("copies the tracking link", async () => {
    const writeText = vi.fn().mockResolvedValue(undefined);
    Object.defineProperty(navigator, "clipboard", { value: { writeText }, configurable: true });
    vi.spyOn(ElMessage, "success").mockImplementation(() => ({}) as never);
    const wrapper = await mountDrawer();
    await wrapper.findAll('[data-test="copy"]')[0].trigger("click");
    await flushPromises();
    expect(writeText).toHaveBeenCalledWith("https://t.example.com/t/KOL0001/P000001");
    expect(ElMessage.success).toHaveBeenCalledWith("已复制链接");
  });

  it("reports a failed copy", async () => {
    const writeText = vi.fn().mockRejectedValue(new Error("denied"));
    Object.defineProperty(navigator, "clipboard", { value: { writeText }, configurable: true });
    vi.spyOn(ElMessage, "error").mockImplementation(() => ({}) as never);
    const wrapper = await mountDrawer();
    await wrapper.findAll('[data-test="copy"]')[1].trigger("click");
    await flushPromises();
    expect(ElMessage.error).toHaveBeenCalledWith("复制失败，请手动选择链接复制");
  });

  it("filters by exact product code from the first page", async () => {
    const wrapper = await mountDrawer();
    await wrapper.find('[data-test="link-product-code"]').setValue(" P000002 ");
    await wrapper.find('[data-test="link-search"]').trigger("click");
    await flushPromises();
    expect(linkApi.listLinks).toHaveBeenLastCalledWith({
      kolCode: "KOL0001",
      productCode: "P000002",
      page: 1,
      size: 20
    });
  });
});
