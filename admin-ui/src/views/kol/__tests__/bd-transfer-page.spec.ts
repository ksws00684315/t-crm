import { flushPromises, mount } from "@vue/test-utils";
import ElementPlus, { ElMessage, ElMessageBox } from "element-plus";
import { createMemoryHistory, createRouter } from "vue-router";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import * as transferApi from "@/api/kol/bd-transfer";
import type { BdTransferVO } from "@/api/kol/bd-transfer";
import * as bdApi from "@/api/system/bd-user";
import { i18n } from "@/locales";
import { routes } from "@/router/routes";
import { accessOf, canAccessRoute } from "@/utils/permission";
import { ApiError } from "@/utils/http/error";
import BdTransferPage from "../bd-transfers/index.vue";

vi.mock("@/api/kol/bd-transfer");
vi.mock("@/api/system/bd-user");

const scheduled: BdTransferVO = {
  id: "21",
  kolId: "11",
  kolCode: "KOL0001",
  kolName: "Alice",
  fromBdUserId: "5",
  fromBdUserName: "BD Five",
  toBdUserId: null,
  toBdUserName: null,
  effectiveAt: "2026-11-01T00:00:00Z",
  status: "SCHEDULED",
  executedAt: null,
  reason: "handover",
  cancelReason: null,
  createTime: "2026-10-16T00:00:00Z"
};

const done: BdTransferVO = {
  ...scheduled,
  id: "20",
  status: "DONE",
  toBdUserId: "9",
  toBdUserName: "BD Nine",
  executedAt: "2026-10-16T09:30:00Z"
};

function page(items: BdTransferVO[] = [scheduled, done], total = "2") {
  return { items, total, page: 1, size: 20 };
}

async function mountPage(query: Record<string, string> = {}, items?: BdTransferVO[]) {
  vi.mocked(transferApi.listBdTransfers).mockResolvedValue(page(items));
  vi.mocked(bdApi.listBdUsers).mockResolvedValue([
    { id: "5", username: "bd5", nickname: "BD Five" },
    { id: "9", username: "bd9", nickname: "BD Nine" }
  ]);
  // Same route name as the real table so the query watch can tell this page from others.
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: "/kol/bd-transfers", name: "KolBdTransfers", component: BdTransferPage },
      { path: "/kol/kols", name: "KolList", component: { template: "<div />" } }
    ]
  });
  await router.push({ path: "/kol/bd-transfers", query });
  await router.isReady();
  const wrapper = mount(BdTransferPage, {
    global: { plugins: [ElementPlus, i18n, router] }
  });
  await flushPromises();
  return { wrapper, router };
}

describe("BD transfer records page", () => {
  beforeEach(() => {
    document.body.innerHTML = "";
    localStorage.removeItem("tcrm.admin.table.kol-bd-transfers");
    vi.mocked(transferApi.cancelBdTransfer).mockReset();
    vi.spyOn(ElMessage, "success").mockImplementation(() => ({}) as never);
    vi.spyOn(ElMessage, "error").mockImplementation(() => ({}) as never);
  });

  afterEach(() => {
    document.body.innerHTML = "";
  });

  it("is a super-admin route placed after the creator list", () => {
    const children = routes.find(item => item.path === "/")?.children ?? [];
    const index = children.findIndex(item => item.path === "kol/bd-transfers");
    expect(index).toBeGreaterThan(-1);
    expect(children[index - 1]?.path).toBe("kol/kols");
    const meta = { superAdmin: true } as const;
    expect(canAccessRoute(meta, accessOf({ role: "SUPER_ADMIN", dataScope: "ALL" }))).toBe(true);
    expect(
      canAccessRoute(
        meta,
        accessOf({ role: "STAFF", dataScope: "ALL", permissions: ["kol:view", "kol:edit"] })
      )
    ).toBe(false);
  });

  it("renders the rows with unassigned targets and per-status actions", async () => {
    const { wrapper } = await mountPage();
    const text = wrapper.text();
    expect(text).toContain("KOL0001");
    expect(text).toContain("Alice");
    expect(text).toContain("BD Five");
    expect(text).toContain("BD Nine");
    expect(text).toContain("预约中");
    expect(text).toContain("已完成");
    expect(text).toContain("未分配");
    expect(text).toContain("handover");
    // Only the SCHEDULED row offers the cancel action.
    expect(wrapper.findAll('[data-test="cancel-transfer"]')).toHaveLength(1);
    wrapper.unmount();
  });

  it("uses the status and kolCode query parameters as initial filters (task 20 links)", async () => {
    const { wrapper } = await mountPage({ status: "SCHEDULED", kolCode: "KOL0001" });
    expect(transferApi.listBdTransfers).toHaveBeenCalledWith(
      expect.objectContaining({ status: "SCHEDULED", kolCode: "KOL0001", page: 1 })
    );
    expect(wrapper.text()).toContain("达人编号");
    wrapper.unmount();
  });

  it("ignores unknown status values in the query", async () => {
    await mountPage({ status: "NOPE" });
    expect(transferApi.listBdTransfers).toHaveBeenCalledWith(
      expect.objectContaining({ status: undefined })
    );
  });

  it("re-applies the filters when the query changes on the cached page", async () => {
    const { wrapper, router } = await mountPage();
    vi.mocked(transferApi.listBdTransfers).mockClear();
    await router.push({ path: "/kol/bd-transfers", query: { status: "DONE" } });
    await flushPromises();
    expect(transferApi.listBdTransfers).toHaveBeenCalledWith(
      expect.objectContaining({ status: "DONE", page: 1 })
    );
    wrapper.unmount();
  });

  it("ignores the query vanishing when another page becomes current (kept-alive page)", async () => {
    const { wrapper, router } = await mountPage({ status: "SCHEDULED", kolCode: "KOL0001" });
    vi.mocked(transferApi.listBdTransfers).mockClear();
    await router.push({ path: "/kol/kols" });
    await flushPromises();
    expect(transferApi.listBdTransfers).not.toHaveBeenCalled();
    // The filters typed on this page are kept for when the user comes back.
    expect(wrapper.text()).toContain("达人编号：KOL0001");
    wrapper.unmount();
  });

  it("shows known cancel reasons from the language pack and unknown ones as stored", async () => {
    const cancelled: BdTransferVO = {
      ...scheduled,
      id: "30",
      status: "CANCELLED",
      cancelReason: "手动取消"
    };
    const unknown: BdTransferVO = {
      ...scheduled,
      id: "31",
      status: "CANCELLED",
      cancelReason: "A reason this build does not know"
    };
    i18n.global.locale.value = "en-US";
    try {
      const { wrapper } = await mountPage({}, [cancelled, unknown]);
      const text = wrapper.text();
      expect(text).toContain("Cancelled manually");
      expect(text).not.toContain("手动取消");
      expect(text).toContain("A reason this build does not know");
      wrapper.unmount();
    } finally {
      i18n.global.locale.value = "zh-CN";
    }
  });

  it("searches by creator code typed in the filter", async () => {
    const { wrapper } = await mountPage();
    vi.mocked(transferApi.listBdTransfers).mockClear();
    const input = wrapper
      .findAll("input")
      .find(element => element.attributes("maxlength") === "16");
    await input!.setValue("KOL0002");
    await input!.trigger("keyup.enter");
    await flushPromises();
    expect(transferApi.listBdTransfers).toHaveBeenCalledWith(
      expect.objectContaining({ kolCode: "KOL0002", page: 1 })
    );
    wrapper.unmount();
  });

  it("cancels a reservation after confirmation and reloads", async () => {
    vi.mocked(transferApi.cancelBdTransfer).mockResolvedValue(null);
    const { wrapper } = await mountPage();
    const confirm = vi.spyOn(ElMessageBox, "confirm").mockResolvedValue("confirm" as never);
    vi.mocked(transferApi.listBdTransfers).mockClear();
    await wrapper.get('[data-test="cancel-transfer"]').trigger("click");
    await flushPromises();
    expect(confirm).toHaveBeenCalled();
    expect(transferApi.cancelBdTransfer).toHaveBeenCalledWith("21");
    expect(ElMessage.success).toHaveBeenCalledWith("已取消预约");
    expect(transferApi.listBdTransfers).toHaveBeenCalledTimes(1);
    wrapper.unmount();
  });

  it("refreshes with the mapped text when the record is no longer scheduled", async () => {
    vi.mocked(transferApi.cancelBdTransfer).mockRejectedValue(
      new ApiError({ status: 409, errorCode: "KOL_TRANSFER_NOT_SCHEDULED", traceId: "t-1" })
    );
    const { wrapper } = await mountPage();
    vi.spyOn(ElMessageBox, "confirm").mockResolvedValue("confirm" as never);
    vi.mocked(transferApi.listBdTransfers).mockClear();
    await wrapper.get('[data-test="cancel-transfer"]').trigger("click");
    await flushPromises();
    expect(ElMessage.error).toHaveBeenCalledWith("只能取消预约中的商务转移");
    expect(transferApi.listBdTransfers).toHaveBeenCalledTimes(1);
    wrapper.unmount();
  });
});
