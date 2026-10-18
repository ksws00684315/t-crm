import { DOMWrapper, flushPromises, mount } from "@vue/test-utils";
import { nextTick } from "vue";
import ElementPlus, { ElMessage } from "element-plus";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import * as kolApi from "@/api/kol/kol";
import * as promotionApi from "@/api/kol/promotion";
import * as templateApi from "@/api/link/template";
import type { KolVO, LinkTemplateVO } from "@/api/types";
import { i18n } from "@/locales";
import { ApiError } from "@/utils/http/error";
import KolFormDialog from "../KolFormDialog.vue";

vi.mock("@/api/kol/kol");
vi.mock("@/api/kol/promotion");
vi.mock("@/api/link/template");

const enabled: LinkTemplateVO = {
  id: "7",
  templateCode: "agent-one",
  templateName: "AgentOne",
  templateUrl: "https://shop.example/item/{PRODUCT_ID}",
  channelCode: "WEIDIAN",
  sortOrder: 1,
  status: 1
};

const disabled: LinkTemplateVO = {
  ...enabled,
  id: "8",
  templateCode: "old-shop",
  templateName: "OldShop",
  status: 0
};

const kol: KolVO = {
  id: "9007199254740993",
  kolCode: "KOL-ASD001",
  name: "Alice",
  email: "alice@example.com",
  country: "US",
  socialAccounts: [],
  bdUserId: null,
  bdUserName: null,
  defaultRate: null,
  status: 1,
  remark: null,
  whatsapp: null,
  linkTemplateId: disabled.id,
  linkTemplateName: disabled.templateName,
  inviteCode: null,
  sheetUrl: null,
  registerSource: 1,
  accountStatus: 0,
  createTime: "2026-10-05T01:02:03Z",
  updateTime: "2026-10-05T01:02:03Z"
};

function input(testId: string): DOMWrapper<Element> {
  const element = document.body.querySelector(`[data-test="${testId}"]`);
  if (!element) throw new Error(`missing ${testId}`);
  return new DOMWrapper(element);
}

async function setLabeled(label: string, value: string) {
  const item = [...document.body.querySelectorAll(".el-form-item")].find(element =>
    element.querySelector(".el-form-item__label")?.textContent?.includes(label)
  );
  const field = item?.querySelector("input, textarea");
  if (!field) throw new Error(`missing field ${label}`);
  await new DOMWrapper(field).setValue(value);
}

async function openDialog(current: KolVO | null = null, review = false) {
  vi.mocked(templateApi.listLinkTemplates).mockResolvedValue([enabled, disabled]);
  const wrapper = mount(KolFormDialog, {
    props: { modelValue: false, kol: current, canPickBd: false, bdUsers: [], review },
    global: { plugins: [ElementPlus, i18n] },
    attachTo: document.body
  });
  await wrapper.setProps({ modelValue: true });
  await flushPromises();
  await nextTick();
  return wrapper;
}

describe("KOL form dialog", () => {
  beforeEach(() => {
    document.body.innerHTML = "";
    vi.mocked(kolApi.createKol).mockReset();
    vi.mocked(kolApi.editKol).mockReset();
  });

  afterEach(() => {
    document.body.innerHTML = "";
  });

  it("blocks save when WhatsApp does not match the backend pattern", async () => {
    const wrapper = await openDialog();
    await setLabeled("姓名", "Alice");
    await setLabeled("邮箱", "alice@example.com");
    await input("whatsapp").setValue("+123");
    await input("kol-save").trigger("click");
    await flushPromises();
    expect(kolApi.createKol).not.toHaveBeenCalled();
    expect(document.body.textContent).toContain("WhatsApp");
    wrapper.unmount();
  });

  it("submits trimmed optional fields, an upper-case country and one primary account", async () => {
    vi.spyOn(ElMessage, "success").mockImplementation(() => ({}) as never);
    vi.mocked(kolApi.createKol).mockResolvedValue(kol);
    const wrapper = await openDialog();
    await setLabeled("姓名", " Alice ");
    await setLabeled("邮箱", " alice@example.com ");
    await setLabeled("国家", "us");
    await input("whatsapp").setValue(" +8613800000000 ");
    await input("invite-code").setValue(" ABC123 ");
    await input("sheet-url").setValue(" https://docs.google.com/spreadsheets/d/abc ");
    const add = [...document.body.querySelectorAll("button")].find(button =>
      button.textContent?.includes("添加社媒账号")
    );
    await new DOMWrapper(add!).trigger("click");
    const fields = document.body.querySelectorAll(".social__row input.el-input__inner");
    await new DOMWrapper(fields[0]!).setValue("TikTok");
    await new DOMWrapper(fields[1]!).setValue(" @a ");
    await input("primary").trigger("click");
    await input("kol-save").trigger("click");
    await flushPromises();

    expect(kolApi.createKol).toHaveBeenCalledWith({
      name: "Alice",
      email: "alice@example.com",
      country: "US",
      socialAccounts: [
        { platform: "TikTok", handle: "@a", url: null, followers: null, primary: true }
      ],
      defaultRate: null,
      remark: null,
      whatsapp: "+8613800000000",
      linkTemplateId: null,
      inviteCode: "ABC123",
      sheetUrl: "https://docs.google.com/spreadsheets/d/abc",
      bdUserId: null
    });
    wrapper.unmount();
  });

  it("lists enabled templates and keeps the current disabled template as a marked option", async () => {
    const wrapper = await openDialog(kol);
    await input("template-select").trigger("click");
    await flushPromises();
    const text = document.body.textContent ?? "";
    expect(text).toContain("AgentOne");
    expect(text).toContain("OldShop（已停用）");
    expect(text).not.toContain("old-shop");
    wrapper.unmount();
  });

  describe("review mode (task 25b §3.3)", () => {
    it("locks the email and the code and offers the approve button", async () => {
      const wrapper = await openDialog(kol, true);
      expect(document.body.textContent).toContain("审核达人 KOL-ASD001");
      const code = document.body.querySelector<HTMLInputElement>('[data-test="review-code"]');
      expect(code?.value).toBe("KOL-ASD001");
      const email = document.body.querySelector<HTMLInputElement>('[data-test="email-input"]');
      expect(email?.value).toBe("alice@example.com");
      expect(email?.disabled).toBe(true);
      expect(document.body.querySelector('[data-test="review-approve"]')).not.toBeNull();
      wrapper.unmount();
    });

    it("approve saves the profile and then moves the status to PENDING_PROMOTION", async () => {
      const success = vi.spyOn(ElMessage, "success").mockImplementation(() => ({}) as never);
      vi.mocked(kolApi.editKol).mockResolvedValue(null);
      const wrapper = await openDialog(kol, true);
      await input("review-approve").trigger("click");
      await flushPromises();
      expect(kolApi.editKol).toHaveBeenCalledTimes(1);
      expect(promotionApi.updatePromotionStatus).toHaveBeenCalledWith(kol.id, "PENDING_PROMOTION");
      expect(success).toHaveBeenCalledWith("资料已保存，状态已改为待推广");
      expect(wrapper.emitted("saved")).toHaveLength(1);
      expect(wrapper.emitted("update:modelValue")?.at(-1)).toEqual([false]);
      wrapper.unmount();
    });

    it("approve stops after the first step when the profile save fails", async () => {
      const error = vi.spyOn(ElMessage, "error").mockImplementation(() => ({}) as never);
      vi.mocked(kolApi.editKol).mockRejectedValue(
        new ApiError({ status: 400, errorCode: "COMMON_VALIDATION_FAILED", traceId: "t" })
      );
      const wrapper = await openDialog(kol, true);
      await input("review-approve").trigger("click");
      await flushPromises();
      expect(promotionApi.updatePromotionStatus).not.toHaveBeenCalled();
      expect(error).toHaveBeenCalledWith("资料保存失败，状态未修改");
      expect(wrapper.emitted("update:modelValue")?.at(-1)).toBeUndefined();
      wrapper.unmount();
    });

    it("approve explains when the profile saved but the status change failed", async () => {
      const error = vi.spyOn(ElMessage, "error").mockImplementation(() => ({}) as never);
      vi.mocked(kolApi.editKol).mockResolvedValue(null);
      vi.mocked(promotionApi.updatePromotionStatus).mockRejectedValue(
        new ApiError({ status: 500, errorCode: "COMMON_INTERNAL_ERROR", traceId: "t" })
      );
      const wrapper = await openDialog(kol, true);
      await input("review-approve").trigger("click");
      await flushPromises();
      expect(kolApi.editKol).toHaveBeenCalledTimes(1);
      expect(error).toHaveBeenCalledWith("资料已保存，但修改状态失败");
      wrapper.unmount();
    });
  });

  it("allows none of the social rows to stay primary", async () => {
    const wrapper = await openDialog();
    const add = () =>
      [...document.body.querySelectorAll("button")].find(button =>
        button.textContent?.includes("添加社媒账号")
      );
    await new DOMWrapper(add()!).trigger("click");
    await new DOMWrapper(add()!).trigger("click");
    const radios = () => document.body.querySelectorAll('[data-test="primary"]');
    await new DOMWrapper(radios()[0]!).trigger("click");
    await new DOMWrapper(radios()[1]!).trigger("click");
    expect(radios()[0]?.classList.contains("is-checked")).toBe(false);
    expect(radios()[1]?.classList.contains("is-checked")).toBe(true);
    await new DOMWrapper(radios()[1]!).trigger("click");
    expect(radios()[0]?.classList.contains("is-checked")).toBe(false);
    expect(radios()[1]?.classList.contains("is-checked")).toBe(false);
    wrapper.unmount();
  });
});
