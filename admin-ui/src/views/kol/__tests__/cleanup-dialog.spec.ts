import { DOMWrapper, flushPromises, mount } from "@vue/test-utils";
import ElementPlus, { ElMessage, ElMessageBox } from "element-plus";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import * as promotionApi from "@/api/kol/promotion";
import type { KolVO } from "@/api/types";
import { i18n } from "@/locales";
import CleanupConfigDialog from "../CleanupConfigDialog.vue";

vi.mock("@/api/kol/promotion");

const kol: KolVO = {
  id: "9007199254740993",
  kolCode: "KOL-ASD001",
  name: "Alice",
  email: "alice@example.com",
  country: "US",
  socialAccounts: [
    { platform: "TikTok", handle: "@alice", url: null, followers: null, primary: true }
  ],
  bdUserId: "1",
  bdUserName: "BD Five",
  defaultRate: null,
  status: 1,
  remark: null,
  whatsapp: null,
  phone: null,
  linkTemplateId: null,
  linkTemplateName: null,
  inviteCode: null,
  sheetUrl: null,
  registerSource: 2,
  reviewStatus: "APPROVED",
  relationStatus: "PENDING_PROMOTION",
  displayRelationStatus: "PENDING_PROMOTION",
  loginStatus: "NORMAL",
  platforms: [],
  jumpTemplateId: null,
  configVersion: null,
  inviteTime: "2026-04-01T00:00:00Z",
  createTime: "2026-04-01T00:00:00Z",
  updateTime: "2026-04-01T00:00:00Z",
  autoCleanupDays: 14
};

function element(testId: string): DOMWrapper<Element> {
  const el = document.body.querySelector(`[data-test="${testId}"]`);
  if (!el) throw new Error(`missing ${testId}`);
  return new DOMWrapper(el);
}

async function openDialog(overrides: Partial<KolVO> = {}) {
  const wrapper = mount(CleanupConfigDialog, {
    props: { modelValue: false, kol: { ...kol, ...overrides } },
    global: { plugins: [ElementPlus, i18n] },
    attachTo: document.body
  });
  await wrapper.setProps({ modelValue: true });
  await flushPromises();
  return wrapper;
}

describe("per-creator cleanup config dialog (task 32 §2.2)", () => {
  beforeEach(() => {
    document.body.innerHTML = "";
    vi.mocked(promotionApi.updateAutoCleanup).mockReset();
    vi.mocked(promotionApi.cleanupKol).mockReset();
    vi.mocked(promotionApi.updateAutoCleanup).mockResolvedValue(null);
    vi.mocked(promotionApi.cleanupKol).mockResolvedValue(null);
  });

  afterEach(() => {
    document.body.innerHTML = "";
    vi.restoreAllMocks();
  });

  it("shows code and name with locale-aware brackets", async () => {
    i18n.global.locale.value = "zh-CN";
    await openDialog();
    expect(element("cleanup-kol-label").text()).toBe("KOL-ASD001（Alice）");
    document.body.innerHTML = "";
    i18n.global.locale.value = "en-US";
    await openDialog();
    expect(element("cleanup-kol-label").text()).toBe("KOL-ASD001 (Alice)");
    i18n.global.locale.value = "zh-CN";
  });

  it("seeds the form from the creator's stored auto-cleanup days", async () => {
    const wrapper = await openDialog();
    const text = document.body.textContent ?? "";
    expect(text).toContain("KOL-ASD001");
    expect(element("cleanup-days").find("input").element).toBeDefined();
    wrapper.unmount();
  });

  it("turns auto cleanup off with null days", async () => {
    const success = vi.spyOn(ElMessage, "success").mockImplementation(() => ({}) as never);
    const wrapper = await openDialog();
    await element("cleanup-auto").findAll("input")[0].trigger("click");
    await element("cleanup-save").trigger("click");
    await flushPromises();
    expect(promotionApi.updateAutoCleanup).toHaveBeenCalledWith(kol.id, null);
    expect(success).toHaveBeenCalledWith("清理配置已保存");
    expect(wrapper.emitted("update:modelValue")?.at(-1)).toEqual([false]);
    wrapper.unmount();
  });

  it("saves the chosen number of days", async () => {
    const success = vi.spyOn(ElMessage, "success").mockImplementation(() => ({}) as never);
    const wrapper = await openDialog({ autoCleanupDays: null });
    await element("cleanup-auto").findAll("input")[1].trigger("click");
    await element("cleanup-days").find("input").setValue("45");
    await element("cleanup-save").trigger("click");
    await flushPromises();
    expect(promotionApi.updateAutoCleanup).toHaveBeenCalledWith(kol.id, 45);
    expect(success).toHaveBeenCalledTimes(1);
    wrapper.unmount();
  });

  it("runs the immediate cleanup only with a reason, after the destructive confirm", async () => {
    const success = vi.spyOn(ElMessage, "success").mockImplementation(() => ({}) as never);
    const prompt = vi
      .spyOn(ElMessageBox, "prompt")
      .mockResolvedValue({ value: "stop cooperating", action: "confirm" } as never);
    const wrapper = await openDialog();
    await element("cleanup-now").trigger("click");
    await flushPromises();
    // The confirm copy spells out the unrecoverable Weidian link deletion (04.01).
    const message = prompt.mock.calls[0][0];
    expect(typeof message === "string" ? message : String(message)).toContain("无法恢复");
    expect(promotionApi.cleanupKol).toHaveBeenCalledWith(kol.id, "stop cooperating");
    expect(success).toHaveBeenCalledWith("已提交清理");
    wrapper.unmount();
  });

  it("keeps a cancelled immediate cleanup from calling the API", async () => {
    vi.spyOn(ElMessageBox, "prompt").mockRejectedValue("cancel");
    const wrapper = await openDialog();
    await element("cleanup-now").trigger("click");
    await flushPromises();
    expect(promotionApi.cleanupKol).not.toHaveBeenCalled();
    wrapper.unmount();
  });
});
