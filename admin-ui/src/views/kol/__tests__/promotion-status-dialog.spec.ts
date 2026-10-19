import { DOMWrapper, flushPromises, mount } from "@vue/test-utils";
import ElementPlus, { ElMessage, ElMessageBox } from "element-plus";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import * as promotionApi from "@/api/kol/promotion";
import type { KolVO } from "@/api/types";
import { i18n } from "@/locales";
import { ApiError } from "@/utils/http/error";
import PromotionStatusDialog from "../PromotionStatusDialog.vue";

vi.mock("@/api/kol/promotion");

const kol: KolVO = {
  id: "9007199254740993",
  kolCode: "KOL-ASD001",
  name: "Alice",
  email: "alice@example.com",
  country: null,
  socialAccounts: [],
  bdUserId: null,
  bdUserName: null,
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
  accountStatus: 1,
  reviewStatus: "APPROVED",
  relationStatus: "PENDING_PROMOTION",
  displayRelationStatus: "PENDING_PROMOTION",
  loginStatus: "NORMAL",
  platforms: [],
  jumpTemplateId: null,
  configVersion: null,
  inviteTime: "2026-10-05T01:02:03Z",
  createTime: "2026-10-05T01:02:03Z",
  updateTime: "2026-10-05T01:02:03Z"
};

function element(testId: string): DOMWrapper<Element> {
  const found = document.body.querySelector(`[data-test="${testId}"]`);
  if (!found) throw new Error(`missing ${testId}`);
  return new DOMWrapper(found);
}

async function openDialog() {
  const wrapper = mount(PromotionStatusDialog, {
    props: { modelValue: false, kol },
    global: { plugins: [ElementPlus, i18n] },
    attachTo: document.body
  });
  await wrapper.setProps({ modelValue: true });
  await flushPromises();
  return wrapper;
}

/** Picks a target status in the dialog select (options teleport to body). */
async function pickStatus(label: string) {
  await element("status-target").find(".el-select__wrapper").trigger("click");
  await flushPromises();
  const option = [...document.body.querySelectorAll(".el-select-dropdown__item")].find(item =>
    item.textContent?.includes(label)
  );
  if (!option) throw new Error(`missing option ${label}`);
  await new DOMWrapper(option).trigger("click");
  await flushPromises();
}

describe("promotion status dialog", () => {
  beforeEach(() => {
    document.body.innerHTML = "";
    vi.mocked(promotionApi.updateRelationStatus).mockReset();
    vi.mocked(promotionApi.updateRelationStatus).mockResolvedValue(null);
  });

  afterEach(() => {
    document.body.innerHTML = "";
    vi.restoreAllMocks();
  });

  it("offers only the three relationship statuses and requires a target", async () => {
    const wrapper = await openDialog();
    await element("status-target").find(".el-select__wrapper").trigger("click");
    await flushPromises();
    const options = [...document.body.querySelectorAll(".el-select-dropdown__item")].map(
      item => item.textContent
    );
    expect(options).toEqual(["待推广", "推广中", "无效"]);

    await element("status-submit").trigger("click");
    await flushPromises();
    expect(promotionApi.updateRelationStatus).not.toHaveBeenCalled();
    expect(element("status-error").text()).toContain("请选择目标状态");
    wrapper.unmount();
  });

  it("switches the relation status after confirmation", async () => {
    const success = vi.spyOn(ElMessage, "success").mockImplementation(() => ({}) as never);
    const confirm = vi.spyOn(ElMessageBox, "confirm").mockResolvedValue("confirm" as never);
    const wrapper = await openDialog();
    await pickStatus("无效");
    await element("status-submit").trigger("click");
    await flushPromises();
    expect(confirm).toHaveBeenCalledWith(
      expect.stringContaining("改为「无效」"),
      "修改关系状态",
      expect.anything()
    );
    expect(promotionApi.updateRelationStatus).toHaveBeenCalledWith(kol.id, "INVALID");
    expect(success).toHaveBeenCalledWith("状态已修改");
    expect(wrapper.emitted("changed")).toHaveLength(1);
    expect(wrapper.emitted("update:modelValue")?.at(-1)).toEqual([false]);
    wrapper.unmount();
  });

  it("does nothing when the confirmation is cancelled", async () => {
    vi.spyOn(ElMessageBox, "confirm").mockRejectedValue("cancel");
    const wrapper = await openDialog();
    await pickStatus("推广中");
    await element("status-submit").trigger("click");
    await flushPromises();
    expect(promotionApi.updateRelationStatus).not.toHaveBeenCalled();
    wrapper.unmount();
  });

  it("lists the missing settings on 422 and jumps to them via the button", async () => {
    vi.spyOn(ElMessageBox, "confirm").mockResolvedValue("confirm" as never);
    vi.mocked(promotionApi.updateRelationStatus).mockRejectedValue(
      new ApiError({
        status: 422,
        errorCode: "KOL_PROMOTION_CONFIG_INCOMPLETE",
        traceId: "t-2",
        payload: ["defaultRate", "platformAddress"]
      })
    );
    const wrapper = await openDialog();
    await pickStatus("推广中");
    await element("status-submit").trigger("click");
    await flushPromises();
    expect(element("status-error").text()).toContain("佣金比例");
    expect(element("status-error").text()).toContain("代购平台地址");
    await element("go-config").trigger("click");
    await flushPromises();
    expect(wrapper.emitted("openConfig")).toHaveLength(1);
    expect(wrapper.emitted("update:modelValue")?.at(-1)).toEqual([false]);
    wrapper.unmount();
  });

  it("still offers the settings button when the 422 payload is unreadable", async () => {
    vi.spyOn(ElMessageBox, "confirm").mockResolvedValue("confirm" as never);
    vi.mocked(promotionApi.updateRelationStatus).mockRejectedValue(
      new ApiError({
        status: 422,
        errorCode: "KOL_PROMOTION_CONFIG_INCOMPLETE",
        traceId: "t-3",
        payload: null
      })
    );
    const error = vi.spyOn(ElMessage, "error").mockImplementation(() => ({}) as never);
    const wrapper = await openDialog();
    await pickStatus("推广中");
    await element("status-submit").trigger("click");
    await flushPromises();
    expect(element("go-config").exists()).toBe(true);
    expect(element("status-error").text()).toContain("推广配置不完整");
    expect(error).not.toHaveBeenCalled();
    wrapper.unmount();
  });
});
