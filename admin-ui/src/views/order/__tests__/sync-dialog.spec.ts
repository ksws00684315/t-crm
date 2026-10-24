import { flushPromises, mount } from "@vue/test-utils";
import ElementPlus, { ElMessage, ElMessageBox } from "element-plus";
import { createPinia, setActivePinia } from "pinia";
import { createMemoryHistory, createRouter } from "vue-router";
import { beforeEach, describe, expect, it, vi } from "vitest";
import * as orderApi from "@/api/order/order";
import * as shopApi from "@/api/product/shop";
import type { OrderSyncStateVO, Role, ShopOptionVO, SyncFailureVO } from "@/api/types";
import { useUserStore } from "@/stores/user";
import { i18n } from "@/locales";
import { routes } from "@/router/routes";
import { notifyError } from "@/utils/notify";
import SyncStatesDialog from "../SyncStatesDialog.vue";

vi.mock("@/api/order/order");
vi.mock("@/api/product/shop");
vi.mock("@/utils/notify", () => ({ notifyError: vi.fn() }));

/**
 * Task 40 §3.5: the sync monitor dialog gives every shop an order and a refund row with
 * source, last success, last error, 24h failures, missing fields and the import cutoff, plus
 * a failed-jobs tab listing recent sync/import failures with their import task.
 */
const shop: OrderSyncStateVO = {
  shopId: "1",
  shopName: "t40 Shop",
  enabled: true,
  authorized: true,
  lastSuccessAt: "2026-10-21T08:00:00Z",
  lastError: null,
  orders: {
    source: "API",
    lastSuccessAt: "2026-10-21T08:00:00Z",
    lastError: null,
    failures24h: 2,
    missingFields: { payTime: 2, itemItemId: 1 }
  },
  refunds: {
    source: "IMPORT",
    lastSuccessAt: "2026-10-21T07:30:00Z",
    lastError: "weidian timeout",
    failures24h: 0,
    missingFields: {}
  },
  lastImport: { kind: "ORDER", dataCutoffTime: "2026-10-20T18:00:00Z", taskId: "9801" }
};

const failures: SyncFailureVO[] = [
  {
    id: "1",
    shopId: "1",
    shopName: "t40 Shop",
    kind: "REFUND_IMPORT",
    error: "weidian timeout",
    taskId: "9901",
    time: "2026-10-21T07:29:00Z"
  },
  {
    id: "2",
    shopId: "1",
    shopName: "t40 Shop",
    kind: "ORDER_SYNC",
    error: "500",
    taskId: null,
    time: "2026-10-21T06:00:00Z"
  }
];

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

async function mountDialog() {
  const router = createRouter({ history: createMemoryHistory(), routes });
  await router.push("/order/orders");
  const wrapper = mount(SyncStatesDialog, {
    props: { modelValue: true },
    global: { plugins: [ElementPlus, i18n, router] },
    attachTo: document.body
  });
  await flushPromises();
  return { wrapper, router };
}

function realRows(selector: string): HTMLElement[] {
  return [...document.body.querySelectorAll<HTMLElement>(selector)].filter(
    item => item.closest("tr") !== null
  );
}

describe("sync states dialog (task 40)", () => {
  beforeEach(() => {
    document.body.innerHTML = "";
    localStorage.clear();
    vi.mocked(orderApi.listSyncStates).mockReset();
    vi.mocked(orderApi.listSyncFailures).mockReset();
    vi.mocked(orderApi.deleteSyncFailures).mockReset();
    vi.mocked(notifyError).mockClear();
    setActivePinia(createPinia());
    signIn("SUPER_ADMIN", ["order:view", "order:manage"]);
    vi.mocked(orderApi.listSyncStates).mockResolvedValue([shop]);
    vi.mocked(orderApi.listSyncFailures).mockResolvedValue(failures);
    vi.mocked(orderApi.deleteSyncFailures).mockResolvedValue(3);
    const shopOption: ShopOptionVO = {
      id: "1",
      shopCode: "SHOP-1",
      shopName: "t40 Shop",
      channel: "WEIDIAN",
      status: 1
    };
    vi.mocked(shopApi.listShops).mockResolvedValue([shopOption]);
  });

  it("shows an order and a refund row per shop with the monitoring fields", async () => {
    const { wrapper } = await mountDialog();
    const shops = realRows('[data-test="sync-shop"]');
    expect(shops).toHaveLength(2);
    // The dialog teleports its body, so read the rendered document, not the component root.
    const text = document.body.textContent ?? "";
    expect(text).toContain("订单");
    expect(text).toContain("退款");
    expect(text).toContain("接口同步");
    expect(text).toContain("导入");
    expect(text).toContain("24 小时失败次数");
    expect(text).toContain("缺字段记录数");
    expect(text).toContain("最近导入数据截止时间");
    wrapper.unmount();
  });

  it("still shows both streams with dashes when a shop never synced", async () => {
    vi.mocked(orderApi.listSyncStates).mockResolvedValue([
      { shopId: "1", shopName: "t40 Shop", enabled: false, authorized: false }
    ]);
    const router = createRouter({ history: createMemoryHistory(), routes });
    await router.push("/order/orders");
    const wrapper = mount(SyncStatesDialog, {
      props: { modelValue: true },
      global: { plugins: [ElementPlus, i18n, router] },
      attachTo: document.body
    });
    await flushPromises();
    const shops = realRows('[data-test="sync-shop"]');
    expect(shops).toHaveLength(2);
    expect(shops[0].textContent).toContain("t40 Shop");
    wrapper.unmount();
  });

  it("lists failed jobs on the second tab and links import failures to the task", async () => {
    const { wrapper, router } = await mountDialog();
    const tabs = [...document.body.querySelectorAll<HTMLElement>(".el-tabs__item")];
    const failureTab = tabs.find(tab => tab.textContent.includes("失败任务"));
    failureTab?.click();
    await flushPromises();
    expect(vi.mocked(orderApi.listSyncFailures)).toHaveBeenCalledTimes(1);
    // L-003: the failure shops read "name (code)" via the shop options. The failures table is
    // the second one in the dialog (the states tab keeps its own table).
    const bodies = [...document.body.querySelectorAll(".el-table__body")];
    const shopCell = bodies[bodies.length - 1]?.textContent ?? "";
    expect(shopCell).toContain("t40 Shop（SHOP-1）");
    const taskLinks = [
      ...document.body.querySelectorAll<HTMLButtonElement>('[data-test="failure-task"]')
    ].filter(item => item.closest("tr") !== null);
    expect(taskLinks).toHaveLength(1);
    taskLinks[0].click();
    // The task route lazy-loads its page; poll instead of guessing flush counts.
    await vi.waitFor(() => expect(router.currentRoute.value.query.taskId).toBe("9901"));
    wrapper.unmount();
  });

  it("reports load failures through notifyError", async () => {
    vi.mocked(orderApi.listSyncStates).mockRejectedValue(new Error("boom"));
    const router = createRouter({ history: createMemoryHistory(), routes });
    const wrapper = mount(SyncStatesDialog, {
      props: { modelValue: true },
      global: { plugins: [ElementPlus, i18n, router] },
      attachTo: document.body
    });
    await flushPromises();
    expect(vi.mocked(notifyError)).toHaveBeenCalledTimes(1);
    wrapper.unmount();
  });

  it("shows the cleanup button only with order:manage", async () => {
    signIn("STAFF", ["order:view"]);
    const { wrapper } = await mountDialog();
    const tabs = [...document.body.querySelectorAll<HTMLElement>(".el-tabs__item")];
    tabs.find(tab => tab.textContent?.includes("失败任务"))?.click();
    await flushPromises();
    expect(document.body.querySelector('[data-test="clean-failures"]')).toBeNull();
    wrapper.unmount();

    signIn("SUPER_ADMIN", ["order:view", "order:manage"]);
    const again = await mountDialog();
    const tabs2 = [...document.body.querySelectorAll<HTMLElement>(".el-tabs__item")];
    tabs2.find(tab => tab.textContent?.includes("失败任务"))?.click();
    await flushPromises();
    expect(document.body.querySelector('[data-test="clean-failures"]')).not.toBeNull();
    again.wrapper.unmount();
  });

  it("prompts for days, confirms, calls deleteSyncFailures and refreshes", async () => {
    const prompt = vi.spyOn(ElMessageBox, "prompt").mockResolvedValue({ value: "30" } as never);
    const confirm = vi.spyOn(ElMessageBox, "confirm").mockResolvedValue("confirm" as never);
    const success = vi.spyOn(ElMessage, "success").mockImplementation(() => undefined as never);
    const { wrapper } = await mountDialog();
    const tabs = [...document.body.querySelectorAll<HTMLElement>(".el-tabs__item")];
    tabs.find(tab => tab.textContent?.includes("失败任务"))?.click();
    await flushPromises();
    document.body.querySelector<HTMLButtonElement>('[data-test="clean-failures"]')?.click();
    await flushPromises();
    expect(prompt).toHaveBeenCalled();
    expect(confirm).toHaveBeenCalled();
    expect(vi.mocked(orderApi.deleteSyncFailures)).toHaveBeenCalledWith(30);
    expect(success).toHaveBeenCalled();
    // reload after clear
    expect(vi.mocked(orderApi.listSyncFailures).mock.calls.length).toBeGreaterThanOrEqual(2);
    wrapper.unmount();
    prompt.mockRestore();
    confirm.mockRestore();
    success.mockRestore();
  });

  it("keeps the plain shop name when the shop options cannot load (L-003 fallback)", async () => {
    vi.mocked(shopApi.listShops).mockRejectedValue(new Error("no product:view"));
    const { wrapper } = await mountDialog();
    const tabs = [...document.body.querySelectorAll<HTMLElement>(".el-tabs__item")];
    tabs.find(tab => tab.textContent?.includes("失败任务"))?.click();
    await flushPromises();
    const bodies = [...document.body.querySelectorAll(".el-table__body")];
    const shopCell = bodies[bodies.length - 1]?.textContent ?? "";
    expect(shopCell).toContain("t40 Shop");
    expect(shopCell).not.toContain("SHOP-1");
    wrapper.unmount();
  });
});
