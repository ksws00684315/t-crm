import { flushPromises, mount } from "@vue/test-utils";
import ElementPlus from "element-plus";
import { createPinia, setActivePinia } from "pinia";
import { beforeEach, describe, expect, it, vi } from "vitest";
import * as orderApi from "@/api/order/order";
import type { MatchEvidenceVO, OrderVO } from "@/api/types";
import { i18n } from "@/locales";
import { notifyError } from "@/utils/notify";
import MatchEvidenceDrawer from "../MatchEvidenceDrawer.vue";

vi.mock("@/api/order/order");
vi.mock("@/utils/notify", () => ({ notifyError: vi.fn() }));

/**
 * Matching-pool evidence (task 40 §6 / requirement 10.01): the drawer shows the line's
 * Weidian item and shop, the mappings sharing that item with pay-time coverage highlighted,
 * the source product, the unmatched reason, the manual records, and hands over to the
 * existing assign-creator flow.
 */
const order: OrderVO = {
  id: "101",
  shopId: "1",
  shopName: "t40 Shop",
  weidianOrderId: "2026102100001",
  kolId: null,
  kolCode: null,
  kolName: null,
  bdUserId: null,
  bdUserName: null,
  orderStatus: "PAID",
  attributionStatus: "UNMATCHED",
  orderTime: null,
  payTime: "2026-10-20T10:01:00Z",
  totalAmount: "125.00",
  payAmount: "120.00",
  refundAmount: "0.00",
  items: [
    {
      id: "301",
      subOrderId: null,
      weidianItemId: "9001",
      skuId: null,
      itemName: "t40 Item",
      skuName: "Blue / L",
      quantity: 1,
      payAmount: "120.00",
      refundAmount: "0.00",
      kolId: null,
      kolCode: null,
      productId: null,
      kolItemId: null,
      attributionType: 0,
      manualReason: null
    }
  ]
};

const evidence: MatchEvidenceVO = {
  orderItemId: "301",
  weidianItemId: "9001",
  shopName: "t40 Shop",
  shopCode: "S001",
  payTime: "2026-10-20T10:01:00Z",
  mappings: [
    {
      kolItemId: "701",
      kolName: "Zoe",
      kolCode: "KOL-ZS01",
      linkCode: "LC001",
      effectiveFrom: "2026-10-01T00:00:00Z",
      effectiveTo: null,
      coversPayTime: true
    },
    {
      kolItemId: "702",
      kolName: "Ada",
      kolCode: "KOL-AA02",
      linkCode: "LC002",
      effectiveFrom: "2026-10-25T00:00:00Z",
      effectiveTo: null,
      coversPayTime: false
    }
  ],
  sourceProduct: { productId: "8001", productName: "t40 Source" },
  unmatchedReason: "CONFLICT",
  manualRecords: [
    {
      operatorName: "ops",
      action: "ASSIGN",
      reason: "confirmed by chat",
      createTime: "2026-10-20T09:00:00Z"
    }
  ]
};

async function mountDrawer() {
  const wrapper = mount(MatchEvidenceDrawer, {
    props: { modelValue: true, order },
    global: { plugins: [ElementPlus, i18n] },
    attachTo: document.body
  });
  await flushPromises();
  return wrapper;
}

function realRows(selector: string): HTMLElement[] {
  return [...document.body.querySelectorAll<HTMLElement>(selector)].filter(
    item => item.closest("tr") !== null
  );
}

describe("match evidence drawer (task 40)", () => {
  beforeEach(() => {
    document.body.innerHTML = "";
    localStorage.clear();
    vi.mocked(orderApi.getMatchEvidence).mockReset();
    vi.mocked(notifyError).mockClear();
    setActivePinia(createPinia());
    vi.mocked(orderApi.getMatchEvidence).mockResolvedValue(evidence);
  });

  it("loads the first line's evidence with item, shop, reason and source product", async () => {
    const wrapper = await mountDrawer();
    expect(vi.mocked(orderApi.getMatchEvidence)).toHaveBeenCalledWith("301");
    const basic = document.body.querySelector('[data-test="evidence-basic"]');
    expect(basic?.textContent).toContain("9001");
    expect(basic?.textContent).toContain("t40 Shop（S001）");
    expect(basic?.textContent).toContain("证据冲突");
    expect(basic?.textContent).toContain("t40 Source（8001）");
    wrapper.unmount();
  });

  it("lists the mappings and highlights the one covering the pay time", async () => {
    const wrapper = await mountDrawer();
    const covers = realRows('[data-test="covers-pay-time"]');
    expect(covers.map(tag => tag.textContent)).toEqual(["覆盖", "不覆盖"]);
    // The covering mapping carries the highlight class the spec asks for.
    expect(covers[0].className).toContain("evidence-mapping--covers");
    expect(covers[1].className).not.toContain("evidence-mapping--covers");
    wrapper.unmount();
  });

  it("hands over to the existing assign-creator flow", async () => {
    const wrapper = await mountDrawer();
    const button = document.body.querySelector<HTMLButtonElement>('[data-test="evidence-assign"]');
    button?.click();
    expect(wrapper.emitted("assign")?.[0]).toEqual([order]);
    wrapper.unmount();
  });

  it("reports evidence failures through notifyError", async () => {
    vi.mocked(orderApi.getMatchEvidence).mockRejectedValue(new Error("boom"));
    const wrapper = await mountDrawer();
    expect(vi.mocked(notifyError)).toHaveBeenCalledTimes(1);
    wrapper.unmount();
  });
});
