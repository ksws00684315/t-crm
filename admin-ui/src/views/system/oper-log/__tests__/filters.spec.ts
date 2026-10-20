import { flushPromises, mount } from "@vue/test-utils";
import ElementPlus from "element-plus";
import { createPinia, setActivePinia } from "pinia";
import { createMemoryHistory, createRouter } from "vue-router";
import { beforeEach, describe, expect, it, vi } from "vitest";
import * as operLogApi from "@/api/system/oper-log";
import { i18n } from "@/locales";
import { routes } from "@/router/routes";
import OperLogPage from "../index.vue";

vi.mock("@/api/system/oper-log");

/**
 * The audit log filters (task 32 §2.3, contract 33 §3.3): module / keyword / operator /
 * time range, the summary column, and the BD-transfer quick filter.
 */
const log: operLogApi.OperLogVO = {
  id: "1",
  module: "KOL",
  action: "BD_TRANSFER",
  operatorName: "Alice",
  method: "POST",
  path: "/admin-api/kol/bd-transfers",
  summary: "转移达人 KOL-ASD001 等 2 位：BD Five → 未分配，立即生效，原因：组织调整",
  success: true,
  operTime: "2026-10-20T01:02:03Z",
  ip: "203.0.113.10"
};

async function mountPage(total = "1", items: operLogApi.OperLogVO[] = [log]) {
  vi.mocked(operLogApi.listOperLogs).mockResolvedValue({ items, total, page: 1, size: 20 });
  const router = createRouter({ history: createMemoryHistory(), routes });
  await router.push("/system/oper-logs");
  const wrapper = mount(OperLogPage, {
    global: { plugins: [ElementPlus, i18n, router] },
    attachTo: document.body
  });
  await flushPromises();
  return { wrapper, router };
}

describe("oper-log filters (task 32 §2.3)", () => {
  beforeEach(() => {
    document.body.innerHTML = "";
    localStorage.removeItem("tcrm.admin.table.oper-log");
    setActivePinia(createPinia());
  });

  it("shows the summary column with the transfer one-liner", async () => {
    const { wrapper } = await mountPage();
    const headers = wrapper.findAll(".el-table__header th .cell").map(item => item.text());
    expect(headers).toContain("内容");
    expect(wrapper.text()).toContain("转移达人 KOL-ASD001");
    expect(wrapper.text()).toContain("未分配");
    wrapper.unmount();
  });

  it("applies the BD-transfer quick filter", async () => {
    const { wrapper } = await mountPage();
    await wrapper.get('[data-test="transfer-filter"]').trigger("click");
    await flushPromises();
    // The backend transfer summaries read "达人…转至…" (joint debugging, 33 §3.3), so the
    // quick filter matches 转至 rather than 转移.
    expect(operLogApi.listOperLogs).toHaveBeenLastCalledWith(
      1,
      20,
      expect.objectContaining({ module: "KOL", keyword: "转至" })
    );
    wrapper.unmount();
  });

  it("sends the keyword and operator filters", async () => {
    const { wrapper } = await mountPage("0", []);
    await wrapper.get('[data-test="filter-keyword"]').setValue("KOL-ASD001");
    await wrapper.get('[data-test="filter-operator"]').setValue("Alice");
    wrapper.getComponent({ name: "ListSearch" }).vm.$emit("search");
    await flushPromises();
    expect(operLogApi.listOperLogs).toHaveBeenLastCalledWith(
      1,
      20,
      expect.objectContaining({ keyword: "KOL-ASD001", operator: "Alice" })
    );
    wrapper.unmount();
  });

  it("arrives from a notification link with the module and kol-code keyword", async () => {
    const { wrapper, router } = await mountPage();
    await router.push("/system/oper-logs?module=KOL&keyword=KOL-ASD001");
    await flushPromises();
    expect(operLogApi.listOperLogs).toHaveBeenLastCalledWith(
      1,
      20,
      expect.objectContaining({ module: "KOL", keyword: "KOL-ASD001" })
    );
    wrapper.unmount();
  });
});
