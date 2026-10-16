import { DOMWrapper, flushPromises, mount } from "@vue/test-utils";
import { nextTick } from "vue";
import ElementPlus, { ElMessage } from "element-plus";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import * as kolApi from "@/api/kol/kol";
import * as templateApi from "@/api/link/template";
import type { KolVO, LinkTemplateVO } from "@/api/types";
import { i18n } from "@/locales";
import KolFormDialog from "../KolFormDialog.vue";

vi.mock("@/api/kol/kol");
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
  kolCode: "KOL0001",
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

async function openDialog(current: KolVO | null = null) {
  vi.mocked(templateApi.listLinkTemplates).mockResolvedValue([enabled, disabled]);
  const wrapper = mount(KolFormDialog, {
    props: { modelValue: false, kol: current, canPickBd: false, bdUsers: [] },
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
