import { flushPromises, mount } from "@vue/test-utils";
import ElementPlus from "element-plus";
import { createPinia, setActivePinia } from "pinia";
import { beforeEach, describe, expect, it, vi } from "vitest";
import * as orderApi from "@/api/order/order";
import type { MatchEvidenceVO, OrderVO, Role } from "@/api/types";
import { i18n } from "@/locales";
import { useUserStore } from "@/stores/user";
import OrderUnmatchedPage from "../unmatched.vue";

vi.mock("@/api/order/order");
vi.mock("@/api/kol/kol", () => ({
  listKols: vi.fn().mockResolvedValue({ items: [], total: "0" })
}));
vi.mock("@/utils/notify", () => ({ notifyError: vi.fn() }));

/**
 * The matching pool after task 40 §6: every row gains "Evidence" (查看依据), which opens the
 * evidence drawer, and that drawer hands over to the existing assign-creator dialog.
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
      skuName: null,
      quantity: 1,
      payAmount: "120.00",
      refundAmount: "0.00",
      kolId: null,
      kolCode: null,
      kolName: null,
      productId: null,
      kolItemId: null,
      attributionType: 0,
      manualReason: null,
      imageUrl: null
    }
  ]
};

const evidence: MatchEvidenceVO = {
  orderItemId: "301",
  orderId: "101",
  orderNo: "2026102100001",
  shopId: "1",
  shopName: "t40 Shop",
  merchantNo: "wd-merchant-1",
  weidianItemId: "9001",
  itemName: "t40 Item",
  skuName: null,
  payTime: null,
  orderAttributionStatus: "UNMATCHED",
  lineAttributionType: "0",
  unmatchedReason: "NO_MAPPING",
  product: null,
  mappings: [],
  manual: null
};

function signIn(role: Role, permissions: string[]) {
  useUserStore().user = {
    id: "1",
    username: "me",
    nickname: "Me",
    email: null,
    role,
    postTemplate: null,
    permissions,
    dataScope: "ALL",
    tenantId: "1",
    tenantName: "默认公司",
    status: 1,
    lastLoginTime: null,
    createTime: "2026-10-01T00:00:00Z"
  };
}

async function mountPage() {
  vi.mocked(orderApi.listUnmatchedOrders).mockResolvedValue({
    items: [order],
    total: "1",
    page: 1,
    size: 20
  });
  vi.mocked(orderApi.listSyncStates).mockResolvedValue([]);
  vi.mocked(orderApi.getMatchEvidence).mockResolvedValue(evidence);
  const wrapper = mount(OrderUnmatchedPage, {
    global: { plugins: [ElementPlus, i18n] },
    attachTo: document.body
  });
  await flushPromises();
  return wrapper;
}

function rowButtons(id: string): HTMLButtonElement[] {
  return [...document.body.querySelectorAll<HTMLButtonElement>(`[data-test="${id}"]`)].filter(
    item => item.closest("tr") !== null
  );
}

describe("matching pool page (task 40)", () => {
  beforeEach(() => {
    document.body.innerHTML = "";
    localStorage.clear();
    vi.mocked(orderApi.listUnmatchedOrders).mockReset();
    vi.mocked(orderApi.getMatchEvidence).mockReset();
    setActivePinia(createPinia());
  });

  it("offers evidence as the first row action and loads the drawer", async () => {
    signIn("SUPER_ADMIN", ["order:view", "order:manage", "kol:view"]);
    const wrapper = await mountPage();
    const evidenceButton = rowButtons("action-evidence")[0];
    expect(evidenceButton.textContent).toContain("查看依据");
    evidenceButton.click();
    await flushPromises();
    expect(vi.mocked(orderApi.getMatchEvidence)).toHaveBeenCalledWith("301");
    expect(document.body.querySelector('[data-test="evidence-basic"]')).not.toBeNull();
    wrapper.unmount();
  });

  it("hands over from the evidence drawer to the assign-creator dialog", async () => {
    signIn("SUPER_ADMIN", ["order:view", "order:manage", "kol:view"]);
    const wrapper = await mountPage();
    rowButtons("action-evidence")[0].click();
    await flushPromises();
    const assign = document.body.querySelector<HTMLButtonElement>('[data-test="evidence-assign"]');
    assign?.click();
    await flushPromises();
    const dialog = [...document.body.querySelectorAll(".el-dialog")].find(node =>
      node.textContent?.includes("指定达人")
    );
    expect(dialog).toBeTruthy();
    wrapper.unmount();
  });
});
