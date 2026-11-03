import { flushPromises, mount, type VueWrapper } from "@vue/test-utils";
import ElementPlus from "element-plus";
import { createPinia, setActivePinia } from "pinia";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { createMemoryHistory, createRouter } from "vue-router";
import * as operLogApi from "@/api/system/oper-log";
import type { OperLogVO } from "@/api/system/oper-log";
import { i18n } from "@/locales";
import { routes } from "@/router/routes";
import OperLogPage from "../index.vue";

vi.mock("@/api/system/oper-log");

/** Render the summary through the page itself, so no component change is needed for L-094. */
function log(overrides: Partial<OperLogVO> = {}): OperLogVO {
  return {
    id: "1",
    module: "KOL",
    action: "BD_TRANSFER",
    operatorName: "Alice",
    method: "POST",
    path: "/admin-api/kol/bd-transfers",
    summary: null,
    success: true,
    operTime: "2026-10-20T01:02:03Z",
    ip: "203.0.113.10",
    ...overrides
  };
}

async function mountPage(items: OperLogVO[]) {
  vi.mocked(operLogApi.listOperLogs).mockResolvedValue({
    items,
    total: String(items.length),
    page: 1,
    size: 20
  });
  const router = createRouter({ history: createMemoryHistory(), routes });
  await router.push("/system/oper-logs");
  const wrapper = mount(OperLogPage, {
    global: { plugins: [ElementPlus, i18n, router] },
    attachTo: document.body
  });
  await flushPromises();
  return wrapper;
}

/** The summary cell is the fifth column of the table. */
function summaryOf(wrapper: VueWrapper, rowIndex = 0): string {
  const cell = wrapper
    .findAll(".el-table__body .el-table__row")
    [rowIndex]?.findAll(".el-table__cell")[4];
  expect(cell, "summary cell must render").toBeDefined();
  return cell!.text();
}

describe("oper-log summary formatting (L-094)", () => {
  beforeEach(() => {
    document.body.innerHTML = "";
    localStorage.removeItem("tcrm.admin.table.oper-log");
    setActivePinia(createPinia());
  });

  it("keeps an explicit Chinese summary as written", async () => {
    const wrapper = await mountPage([
      log({ summary: "转移达人 KOL-ASD001 等 2 位：BD Five → 未分配" })
    ]);
    expect(summaryOf(wrapper)).toBe("转移达人 KOL-ASD001 等 2 位：BD Five → 未分配");
    wrapper.unmount();
  });

  it("resolves an action code through the locales in both languages", async () => {
    i18n.global.locale.value = "zh-CN";
    const zh = await mountPage([log({ action: "Review KOL", summary: "Review KOL" })]);
    expect(summaryOf(zh)).toBe("审核达人");
    zh.unmount();

    i18n.global.locale.value = "en-US";
    const en = await mountPage([log({ action: "Review KOL", summary: "Review KOL" })]);
    expect(summaryOf(en)).toBe(i18n.global.t("audit.actionCodes.Review KOL"));
    en.unmount();
  });

  it("falls back to the generic label for an unknown action code", async () => {
    i18n.global.locale.value = "zh-CN";
    const zh = await mountPage([log({ action: "MYSTERY_ACTION", summary: "MYSTERY_ACTION" })]);
    expect(summaryOf(zh)).toBe(i18n.global.t("audit.actionCodes.OTHER"));
    expect(summaryOf(zh)).toBe("其他操作");
    zh.unmount();

    i18n.global.locale.value = "en-US";
    const en = await mountPage([log({ action: "MYSTERY_ACTION", summary: "MYSTERY_ACTION" })]);
    expect(summaryOf(en)).toBe(i18n.global.t("audit.actionCodes.OTHER"));
    expect(summaryOf(en)).toBe("Other");
    en.unmount();
  });
});
