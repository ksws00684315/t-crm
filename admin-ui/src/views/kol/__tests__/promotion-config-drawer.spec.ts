import { DOMWrapper, flushPromises, mount } from "@vue/test-utils";
import ElementPlus, { ElMessage } from "element-plus";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import * as promotionApi from "@/api/kol/promotion";
import type { PromotionRecordVO } from "@/api/kol/promotion";
import * as templateApi from "@/api/link/template";
import type { KolVO, LinkTemplateVO } from "@/api/types";
import { ApiError } from "@/utils/http/error";
import { i18n } from "@/locales";
import PromotionConfigDrawer from "../PromotionConfigDrawer.vue";

vi.mock("@/api/kol/promotion");
vi.mock("@/api/link/template");

const enabledTemplate: LinkTemplateVO = {
  id: "7",
  templateCode: "agent-one",
  templateName: "AgentOne",
  templateUrl: "https://shop.example/item/{PRODUCT_ID}",
  channelCode: "WEIDIAN",
  sortOrder: 1,
  status: 1
};

const disabledTemplate: LinkTemplateVO = {
  ...enabledTemplate,
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
  linkTemplateId: null,
  linkTemplateName: null,
  inviteCode: null,
  sheetUrl: null,
  registerSource: 2,
  accountStatus: 1,
  displayStatus: "PENDING_PROMOTION",
  promotionStatus: "PENDING_PROMOTION",
  createTime: "2026-10-05T01:02:03Z",
  updateTime: "2026-10-05T01:02:03Z"
};

const config: promotionApi.PromotionConfigVO = {
  defaultRate: "0.1550",
  platformTemplateIds: ["7"],
  landingUrl: "https://brand.example/ada",
  inviteCode: "ADA",
  sheetUrl: null,
  jumpTemplateId: null,
  remark: null
};

const record: PromotionRecordVO = {
  changeType: "CONFIG",
  before: { defaultRate: null, inviteCode: null, status: "PENDING_PROMOTION" },
  after: { defaultRate: "0.1550", inviteCode: "ADA", status: "PENDING_PROMOTION" },
  remark: "首次填写",
  operatorName: "admin",
  createTime: "2026-10-17T02:03:04Z"
};

function page<T>(items: T[], total = String(items.length)) {
  return { items, total, page: 1, size: 10 };
}

function element(testId: string): DOMWrapper<Element> {
  const found = document.body.querySelector(`[data-test="${testId}"]`);
  if (!found) throw new Error(`missing ${testId}`);
  return new DOMWrapper(found);
}

async function openDrawer() {
  const wrapper = mount(PromotionConfigDrawer, {
    props: { modelValue: false, kol },
    global: { plugins: [ElementPlus, i18n] },
    attachTo: document.body
  });
  await wrapper.setProps({ modelValue: true });
  await flushPromises();
  return wrapper;
}

describe("promotion config drawer", () => {
  beforeEach(() => {
    document.body.innerHTML = "";
    vi.mocked(promotionApi.getPromotionConfig).mockReset();
    vi.mocked(promotionApi.savePromotionConfig).mockReset();
    vi.mocked(promotionApi.updatePromotionStatus).mockReset();
    vi.mocked(promotionApi.listPromotionRecords).mockReset();
    vi.mocked(promotionApi.getPromotionConfig).mockResolvedValue(config);
    vi.mocked(promotionApi.savePromotionConfig).mockResolvedValue(null);
    vi.mocked(promotionApi.updatePromotionStatus).mockResolvedValue(null);
    vi.mocked(promotionApi.listPromotionRecords).mockResolvedValue(page([record]));
    vi.mocked(templateApi.listLinkTemplates).mockResolvedValue([enabledTemplate, disabledTemplate]);
  });

  afterEach(() => {
    document.body.innerHTML = "";
  });

  it("prefills the form from the config and shows the change history", async () => {
    const wrapper = await openDrawer();
    expect(promotionApi.getPromotionConfig).toHaveBeenCalledWith(kol.id);
    expect(promotionApi.listPromotionRecords).toHaveBeenCalledWith(kol.id, { page: 1, size: 10 });
    const text = document.body.textContent ?? "";
    expect(text).toContain("推广配置 - KOL-ASD001（Alice）");
    // el-input puts data-test on the native input; the value lives on the element, not the text.
    const landing = document.body.querySelector<HTMLInputElement>('[data-test="landing-url"]');
    expect(landing?.value).toBe("https://brand.example/ada");
    // Change history renders the two changed fields with before → after values.
    expect(text).toContain("佣金比例：- → 15.5%");
    expect(text).toContain("邀请码：- → ADA");
    expect(text).toContain("admin");
    expect(text).toContain("首次填写");
    wrapper.unmount();
  });

  it("renders an unknown record type as a dash instead of a missing i18n key", async () => {
    // Element Plus measures columns with an empty row, and a newer backend may send a new type;
    // neither may reach the message pack as `recordTypeOption.undefined`.
    const warn = vi.spyOn(console, "warn").mockImplementation(() => {});
    vi.mocked(promotionApi.listPromotionRecords).mockResolvedValue(
      page([{ ...record, changeType: "UNKNOWN" as never }])
    );
    const wrapper = await openDrawer();
    const text = document.body.textContent ?? "";
    expect(text).not.toContain("promotion.recordTypeOption");
    expect(text).toContain("-");
    expect(warn.mock.calls.flat().join(" ")).not.toContain("recordTypeOption");
    warn.mockRestore();
    wrapper.unmount();
  });

  it("offers only enabled company templates for the multi-select", async () => {
    const wrapper = await openDrawer();
    await element("platform-select").find(".el-select__wrapper").trigger("click");
    await flushPromises();
    const options = [...document.body.querySelectorAll(".el-select-dropdown__item")].map(
      item => item.textContent
    );
    expect(options).toContain("AgentOne");
    expect(options).not.toContain("OldShop");
    wrapper.unmount();
  });

  it("saves with the percentage converted to a fraction and refreshes the records", async () => {
    const success = vi.spyOn(ElMessage, "success").mockImplementation(() => ({}) as never);
    const wrapper = await openDrawer();
    await element("config-save").trigger("click");
    await flushPromises();
    expect(promotionApi.savePromotionConfig).toHaveBeenCalledWith(kol.id, {
      defaultRate: "0.1550",
      platformTemplateIds: ["7"],
      landingUrl: "https://brand.example/ada",
      inviteCode: "ADA",
      sheetUrl: null,
      jumpTemplateId: "7",
      remark: null
    });
    expect(promotionApi.updatePromotionStatus).not.toHaveBeenCalled();
    expect(success).toHaveBeenCalledWith("推广配置已保存");
    // A plain save keeps the drawer open so the new record can be checked.
    expect(wrapper.props("modelValue")).toBe(true);
    wrapper.unmount();
  });

  it("blocks an invalid landing page before any request", async () => {
    const wrapper = await openDrawer();
    await element("landing-url").setValue("http://brand.example");
    await element("config-save").trigger("click");
    await vi.waitFor(() =>
      expect(document.body.textContent).toContain("落地页地址须以 https:// 开头")
    );
    expect(promotionApi.savePromotionConfig).not.toHaveBeenCalled();
    wrapper.unmount();
  });

  it("save-and-start first blocks on the client when a required field is empty", async () => {
    vi.mocked(promotionApi.getPromotionConfig).mockResolvedValue({
      ...config,
      defaultRate: null,
      landingUrl: null
    });
    const wrapper = await openDrawer();
    await element("config-save-start").trigger("click");
    await flushPromises();
    expect(promotionApi.savePromotionConfig).not.toHaveBeenCalled();
    expect(promotionApi.updatePromotionStatus).not.toHaveBeenCalled();
    expect(element("incomplete-alert").text()).toContain("佣金比例");
    expect(element("incomplete-alert").text()).toContain("落地页地址");
    wrapper.unmount();
  });

  it("save-and-start saves the config and switches to PROMOTING", async () => {
    const success = vi.spyOn(ElMessage, "success").mockImplementation(() => ({}) as never);
    const wrapper = await openDrawer();
    await element("config-save-start").trigger("click");
    await flushPromises();
    expect(promotionApi.savePromotionConfig).toHaveBeenCalledTimes(1);
    expect(promotionApi.updatePromotionStatus).toHaveBeenCalledWith(kol.id, "PROMOTING");
    expect(success).toHaveBeenCalledWith("已保存并开始推广");
    expect(wrapper.emitted("saved")).toHaveLength(1);
    wrapper.unmount();
  });

  it("stays open with the missing list when the backend answers 422", async () => {
    vi.mocked(promotionApi.updatePromotionStatus).mockRejectedValue(
      new ApiError({
        status: 422,
        errorCode: "KOL_PROMOTION_CONFIG_INCOMPLETE",
        traceId: "t-1",
        payload: { missingFields: ["platformTemplateIds"] }
      })
    );
    const wrapper = await openDrawer();
    await element("config-save-start").trigger("click");
    await flushPromises();
    expect(element("incomplete-alert").text()).toContain("代购平台");
    // The config was saved; only the status change failed, so the drawer stays open.
    expect(promotionApi.savePromotionConfig).toHaveBeenCalledTimes(1);
    expect(wrapper.props("modelValue")).toBe(true);
    wrapper.unmount();
  });

  it("renders a status record with translated status names", async () => {
    vi.mocked(promotionApi.listPromotionRecords).mockResolvedValue(
      page([
        {
          changeType: "STATUS",
          before: { status: "PENDING_PROMOTION" },
          after: { status: "PROMOTING" },
          remark: null,
          operatorName: "bd5",
          createTime: "2026-10-17T05:00:00Z"
        }
      ])
    );
    const wrapper = await openDrawer();
    const text = document.body.textContent ?? "";
    expect(text).toContain("状态");
    expect(text).toContain("待推广 → 推广中");
    wrapper.unmount();
  });
});
