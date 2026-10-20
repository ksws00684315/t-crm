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
});
