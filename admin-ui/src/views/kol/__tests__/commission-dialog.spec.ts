import { flushPromises, mount } from "@vue/test-utils";
import ElementPlus, { ElMessage } from "element-plus";
import { afterEach, describe, expect, it, vi } from "vitest";
import * as templateApi from "@/api/link/template";
import * as promotionApi from "@/api/kol/promotion";
import type { KolVO, LinkTemplateVO } from "@/api/types";
import { i18n } from "@/locales";
import CommissionDialog from "../CommissionDialog.vue";

vi.mock("@/api/link/template");
vi.mock("@/api/kol/promotion");

const kol: KolVO = {
  id: "1",
  kolCode: "KOL-AAA001",
  name: "Alice",
  email: "alice@example.com",
  phone: null,
  country: null,
  socialAccounts: [],
  bdUserId: "2",
  bdUserName: "BD",
  bdDiscord: null,
  bdWhatsapp: null,
  defaultRate: "0.1500",
  status: 1,
  remark: null,
  whatsapp: null,
  linkTemplateId: "7",
  linkTemplateName: "AgentOne",
  inviteCode: "OLD",
  sheetUrl: "https://docs.google.com/spreadsheets/d/abc",
  reviewStatus: "APPROVED",
  relationStatus: "PROMOTING",
  displayRelationStatus: "PROMOTING",
  loginStatus: "NORMAL",
  platforms: [],
  jumpTemplateId: "7",
  configVersion: 1,
  autoCleanupDays: null,
  inviteTime: "2026-10-20T12:00:00Z",
  registerSource: 1,
  accountStatus: 1,
  createTime: "2026-10-20T12:00:00Z",
  updateTime: "2026-10-20T12:00:00Z"
};

const templates: LinkTemplateVO[] = ["7", "8"].map(id => ({
  id,
  templateCode: `platform-${id}`,
  templateName: `Platform ${id}`,
  templateUrl: `https://shop.example/${id}/{PRODUCT_ID}`,
  channelCode: "WEIDIAN",
  sortOrder: Number(id),
  status: 1,
  createTime: "2026-10-20T12:00:00Z"
}));

afterEach(() => {
  document.body.innerHTML = "";
  vi.clearAllMocks();
});

describe("commission dialog, 10-20 supplement", () => {
  it("removes a selected platform and saves with hidden fields kept", async () => {
    vi.mocked(templateApi.listLinkTemplates).mockResolvedValue(templates);
    vi.mocked(promotionApi.getPromotionConfig).mockResolvedValue({
      defaultRate: "0.1500",
      platforms: [
        { templateId: "7", templateName: "Platform 7", address: "https://old.example/7" },
        { templateId: "8", templateName: "Platform 8", address: "https://old.example/8" }
      ],
      inviteCode: "OLD",
      jumpTemplateId: "7",
      sheetUrl: "https://docs.google.com/spreadsheets/d/abc",
      remark: null
    });
    vi.mocked(promotionApi.listPromotionRecords).mockResolvedValue({
      items: [],
      total: "0",
      page: 1,
      size: 10
    });
    vi.mocked(promotionApi.savePromotionConfig).mockResolvedValue(null);
    vi.spyOn(ElMessage, "success").mockImplementation(() => ({}) as never);

    const wrapper = mount(CommissionDialog, {
      props: { modelValue: false, kol },
      global: { plugins: [ElementPlus, i18n] },
      attachTo: document.body
    });
    await wrapper.setProps({ modelValue: true });
    await flushPromises();
    expect(document.body.querySelector('[data-test="commission-view"]')).not.toBeNull();
    // A saved config reopens read-only; the Edit button switches back to the form (task 156).
    await wrapper.find('[data-test="commission-edit"]').trigger("click");
    await flushPromises();
    const select = wrapper.findComponent({ name: "ElSelect" });
    expect(select.props("modelValue")).toEqual(["7", "8"]);
    select.vm.$emit("update:modelValue", ["8"]);
    await flushPromises();
    expect(select.props("modelValue")).toEqual(["8"]);
    expect(document.body.querySelector('[data-test="creator-invite-code"]')).toBeNull();
    expect(document.body.querySelector('[data-test="platform-address-0"]')).toBeNull();

    await wrapper.find('[data-test="commission-save"]').trigger("click");
    await flushPromises();
    expect(promotionApi.savePromotionConfig).toHaveBeenCalledWith(
      "1",
      expect.objectContaining({
        platforms: [{ templateId: "8", address: "https://old.example/8" }],
        inviteCode: "OLD",
        remark: ""
      })
    );
    wrapper.unmount();
  });

  it("marks required fields before any validation error appears", async () => {
    vi.mocked(templateApi.listLinkTemplates).mockResolvedValue(templates);
    vi.mocked(promotionApi.getPromotionConfig).mockResolvedValue({
      defaultRate: "0.1500",
      platforms: [],
      inviteCode: "OLD",
      jumpTemplateId: "7",
      sheetUrl: null,
      remark: null
    });
    const wrapper = mount(CommissionDialog, {
      props: { modelValue: false, kol },
      global: { plugins: [ElementPlus, i18n] },
      attachTo: document.body
    });
    await wrapper.setProps({ modelValue: true });
    await flushPromises();
    // The saved config opens as a detail view; Edit brings the form (with its rules) back.
    await wrapper.find('[data-test="commission-edit"]').trigger("click");
    await flushPromises();
    // 推广表格地址非必填（issue #256）：只有佣金比例为必填项。
    expect(wrapper.findAll(".el-form-item.is-required").length).toBeGreaterThanOrEqual(1);
    wrapper.unmount();
  });

  it("saves without a sheet URL", async () => {
    vi.mocked(templateApi.listLinkTemplates).mockResolvedValue(templates);
    vi.mocked(promotionApi.getPromotionConfig).mockResolvedValue({
      defaultRate: "0.1500",
      platforms: [
        { templateId: "7", templateName: "Platform 7", address: "https://old.example/7" }
      ],
      inviteCode: "OLD",
      jumpTemplateId: "7",
      sheetUrl: null,
      remark: null
    });
    vi.mocked(promotionApi.listPromotionRecords).mockResolvedValue({
      items: [],
      total: "0",
      page: 1,
      size: 10
    });
    vi.mocked(promotionApi.savePromotionConfig).mockResolvedValue(null);
    vi.spyOn(ElMessage, "success").mockImplementation(() => ({}) as never);

    const wrapper = mount(CommissionDialog, {
      props: { modelValue: false, kol },
      global: { plugins: [ElementPlus, i18n] },
      attachTo: document.body
    });
    await wrapper.setProps({ modelValue: true });
    await flushPromises();
    await wrapper.find('[data-test="commission-edit"]').trigger("click");
    await flushPromises();
    await wrapper.find('[data-test="commission-save"]').trigger("click");
    await flushPromises();
    expect(promotionApi.savePromotionConfig).toHaveBeenCalledWith(
      "1",
      expect.objectContaining({ sheetUrl: "" })
    );
    wrapper.unmount();
  });

  it("opens read-only once a config has been saved (task 156)", async () => {
    vi.mocked(templateApi.listLinkTemplates).mockResolvedValue(templates);
    vi.mocked(promotionApi.getPromotionConfig).mockResolvedValue({
      defaultRate: "0.1500",
      platforms: [],
      inviteCode: null,
      jumpTemplateId: null,
      sheetUrl: null,
      remark: null
    });
    vi.mocked(promotionApi.listPromotionRecords).mockResolvedValue({
      items: [],
      total: "0",
      page: 1,
      size: 10
    });

    const wrapper = mount(CommissionDialog, {
      props: { modelValue: false, kol },
      global: { plugins: [ElementPlus, i18n] },
      attachTo: document.body
    });
    await wrapper.setProps({ modelValue: true });
    await flushPromises();

    // A saved config opens as a detail view, not a disabled form.
    expect(document.body.querySelector('[data-test="commission-view"]')).not.toBeNull();
    expect(document.body.querySelector('[data-test="rate-input"]')).toBeNull();
    expect(document.body.querySelector('[data-test="rate-view"]')?.textContent).toBe("15%");
    expect(document.body.querySelector('[data-test="commission-edit"]')).not.toBeNull();
    expect(document.body.querySelector('[data-test="commission-save"]')).toBeNull();
    wrapper.unmount();
  });

  it("stays editable on the first fill (task 156)", async () => {
    vi.mocked(templateApi.listLinkTemplates).mockResolvedValue(templates);
    vi.mocked(promotionApi.getPromotionConfig).mockResolvedValue({
      defaultRate: null,
      platforms: [],
      inviteCode: null,
      jumpTemplateId: null,
      sheetUrl: null,
      remark: null
    });
    vi.mocked(promotionApi.listPromotionRecords).mockResolvedValue({
      items: [],
      total: "0",
      page: 1,
      size: 10
    });

    const wrapper = mount(CommissionDialog, {
      props: { modelValue: false, kol: { ...kol, configVersion: null } },
      global: { plugins: [ElementPlus, i18n] },
      attachTo: document.body
    });
    await wrapper.setProps({ modelValue: true });
    await flushPromises();

    expect(document.body.querySelector('[data-test="commission-edit"]')).toBeNull();
    expect(document.body.querySelector('[data-test="commission-save"]')).not.toBeNull();
    expect(document.body.querySelector('[data-test="commission-view"]')).toBeNull();
    expect(document.body.querySelector('[data-test="rate-input"]')).not.toBeNull();
    wrapper.unmount();
  });

  it("returns to the config tab when its validation fails from the records tab (task 156)", async () => {
    vi.mocked(templateApi.listLinkTemplates).mockResolvedValue(templates);
    vi.mocked(promotionApi.getPromotionConfig).mockResolvedValue({
      defaultRate: null,
      platforms: [],
      inviteCode: null,
      jumpTemplateId: null,
      sheetUrl: null,
      remark: null
    });
    vi.mocked(promotionApi.listPromotionRecords).mockResolvedValue({
      items: [],
      total: "0",
      page: 1,
      size: 10
    });

    const wrapper = mount(CommissionDialog, {
      props: { modelValue: false, kol: { ...kol, configVersion: null } },
      global: { plugins: [ElementPlus, i18n] },
      attachTo: document.body
    });
    await wrapper.setProps({ modelValue: true });
    await flushPromises();
    await wrapper.find('[data-test="sheet-url"]').setValue("not-a-url");
    wrapper.findComponent({ name: "ElTabs" }).vm.$emit("update:modelValue", "records");
    await flushPromises();

    await wrapper.find('[data-test="commission-save"]').trigger("click");
    await flushPromises();

    expect(wrapper.findComponent({ name: "ElTabs" }).props("modelValue")).toBe("config");
    expect(promotionApi.savePromotionConfig).not.toHaveBeenCalled();
    wrapper.unmount();
  });

  it("reloads the change records with the selected page size", async () => {
    vi.mocked(templateApi.listLinkTemplates).mockResolvedValue(templates);
    vi.mocked(promotionApi.getPromotionConfig).mockResolvedValue({
      defaultRate: "0.1500",
      platforms: [],
      inviteCode: "OLD",
      jumpTemplateId: "7",
      sheetUrl: null,
      remark: null
    });
    vi.mocked(promotionApi.listPromotionRecords).mockResolvedValue({
      items: [],
      total: "0",
      page: 1,
      size: 10
    });

    const wrapper = mount(CommissionDialog, {
      props: { modelValue: false, kol },
      global: { plugins: [ElementPlus, i18n] },
      attachTo: document.body
    });
    await wrapper.setProps({ modelValue: true });
    await flushPromises();
    wrapper.findComponent({ name: "ElTabs" }).vm.$emit("update:modelValue", "records");
    await flushPromises();
    vi.mocked(promotionApi.listPromotionRecords).mockClear();

    wrapper.findComponent({ name: "ElPagination" }).vm.$emit("size-change", 50);
    await flushPromises();
    expect(promotionApi.listPromotionRecords).toHaveBeenCalledTimes(1);
    expect(promotionApi.listPromotionRecords).toHaveBeenCalledWith("1", {
      page: 1,
      size: 50,
      rateOnly: true
    });
    wrapper.unmount();
  });

  it("asks the server for rate changes only and keeps its total (task 115, B5)", async () => {
    vi.mocked(templateApi.listLinkTemplates).mockResolvedValue(templates);
    vi.mocked(promotionApi.getPromotionConfig).mockResolvedValue({
      defaultRate: "0.1500",
      platforms: [],
      inviteCode: "OLD",
      jumpTemplateId: "7",
      sheetUrl: null,
      remark: null
    });
    vi.mocked(promotionApi.listPromotionRecords).mockResolvedValue({
      items: [
        {
          changeType: "CONFIG",
          versionNo: 2,
          effectiveTime: "2026-11-01T00:00:00Z",
          before: { defaultRate: "0.1000", sheetUrl: "https://docs.google.com/a" },
          after: { defaultRate: "0.1500", sheetUrl: "https://docs.google.com/b" },
          remark: null,
          operatorName: "Admin",
          createTime: "2026-11-01T00:00:00Z"
        }
      ],
      total: "23",
      page: 1,
      size: 10
    });

    const wrapper = mount(CommissionDialog, {
      props: { modelValue: false, kol },
      global: { plugins: [ElementPlus, i18n] },
      attachTo: document.body
    });
    await wrapper.setProps({ modelValue: true });
    await flushPromises();
    expect(promotionApi.listPromotionRecords).toHaveBeenCalledWith("1", {
      page: 1,
      size: 10,
      rateOnly: true
    });
    wrapper.findComponent({ name: "ElTabs" }).vm.$emit("update:modelValue", "records");
    await flushPromises();
    const table = wrapper
      .findAllComponents({ name: "DataTable" })
      .find(item => item.props("tableId") === "kol-commission-records");
    // Paging follows the server total; the page shows every returned row unfiltered.
    expect(table?.props("total")).toBe(23);
    expect(table?.props("rows")).toHaveLength(1);
    const changes = table!.text();
    expect(changes).toContain("10");
    expect(changes).toContain("15");
    expect(changes).not.toContain("docs.google.com");
    wrapper.unmount();
  });
});
