import { flushPromises, mount } from "@vue/test-utils";
import ElementPlus from "element-plus";
import { createPinia, setActivePinia } from "pinia";
import { createMemoryHistory, createRouter } from "vue-router";
import { beforeEach, describe, expect, it, vi } from "vitest";
import * as orderApi from "@/api/order/order";
import type { OrderSyncStateVO, SyncFailureVO } from "@/api/types";
import { i18n } from "@/locales";
import { routes } from "@/router/routes";
import { notifyError } from "@/utils/notify";
import SyncStatesDialog from "../SyncStatesDialog.vue";

vi.mock("@/api/order/order");
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
  rows: [
    {
      dataType: "ORDER",
      source: "SYNC",
      lastSuccessAt: "2026-10-21T08:00:00Z",
      lastError: null,
      failures24h: 2,
      missingFieldCount: 3,
      lastImportCutoffTime: "2026-10-20T18:00:00Z"
    },
    {
      dataType: "REFUND",
      source: "IMPORT",
      lastSuccessAt: "2026-10-21T07:30:00Z",
      lastError: "weidian timeout",
      failures24h: 0,
      missingFieldCount: 0,
      lastImportCutoffTime: null
    }
  ]
};

const failures: SyncFailureVO[] = [
  {
    id: "1",
    shopName: "t40 Shop",
    dataType: "REFUND",
    time: "2026-10-21T07:29:00Z",
    error: "weidian timeout",
    taskId: "9901"
  },
  {
    id: "2",
    shopName: "t40 Shop",
    dataType: "ORDER",
    time: "2026-10-21T06:00:00Z",
    error: "500",
    taskId: null
  }
];

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
    vi.mocked(notifyError).mockClear();
    setActivePinia(createPinia());
    vi.mocked(orderApi.listSyncStates).mockResolvedValue([shop]);
    vi.mocked(orderApi.listSyncFailures).mockResolvedValue(failures);
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

  it("falls back to one order row before the backend sends per-stream rows", async () => {
    vi.mocked(orderApi.listSyncStates).mockResolvedValue([
      {
        shopId: "1",
        shopName: "t40 Shop",
        enabled: true,
        authorized: true,
        lastSuccessAt: "2026-10-21T08:00:00Z",
        lastError: null
      }
    ]);
    const router = createRouter({ history: createMemoryHistory(), routes });
    const wrapper = mount(SyncStatesDialog, {
      props: { modelValue: true },
      global: { plugins: [ElementPlus, i18n, router] },
      attachTo: document.body
    });
    await flushPromises();
    expect(realRows('[data-test="sync-shop"]')).toHaveLength(1);
    wrapper.unmount();
  });

  it("lists failed jobs on the second tab and links import failures to the task", async () => {
    const { wrapper, router } = await mountDialog();
    const tabs = [...document.body.querySelectorAll<HTMLElement>(".el-tabs__item")];
    const failureTab = tabs.find(tab => tab.textContent.includes("失败任务"));
    failureTab?.click();
    await flushPromises();
    expect(vi.mocked(orderApi.listSyncFailures)).toHaveBeenCalledTimes(1);
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
});
