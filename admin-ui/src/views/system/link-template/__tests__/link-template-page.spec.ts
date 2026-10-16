import { DOMWrapper, flushPromises, mount } from "@vue/test-utils";
import { nextTick } from "vue";
import ElementPlus, { ElMessage, ElMessageBox } from "element-plus";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import * as templateApi from "@/api/link/template";
import type { LinkTemplateVO } from "@/api/types";
import { i18n } from "@/locales";
import LinkTemplatePage from "../index.vue";

vi.mock("@/api/link/template");

const row: LinkTemplateVO = {
  id: "7",
  templateCode: "agent-one",
  templateName: "AgentOne",
  templateUrl: "https://shop.example/item/{PRODUCT_ID}?code={INVITE_CODE}",
  channelCode: "WEIDIAN",
  sortOrder: 10,
  status: 1
};

function field(testId: string): DOMWrapper<HTMLInputElement> {
  const element = document.body.querySelector<HTMLInputElement>(`[data-test="${testId}"]`);
  if (!element) throw new Error(`missing ${testId}`);
  return new DOMWrapper(element);
}

async function mountPage(items: LinkTemplateVO[] = [row]) {
  vi.mocked(templateApi.listLinkTemplates).mockResolvedValue(items);
  const wrapper = mount(LinkTemplatePage, {
    global: { plugins: [ElementPlus, i18n] },
    attachTo: document.body
  });
  await flushPromises();
  return wrapper;
}

describe("link template page", () => {
  beforeEach(() => {
    document.body.innerHTML = "";
    localStorage.removeItem("tcrm.admin.table.link-templates");
  });

  afterEach(() => {
    document.body.innerHTML = "";
  });

  it("lists every template without a pager", async () => {
    const wrapper = await mountPage();
    expect(wrapper.findComponent({ name: "ListSearch" }).exists()).toBe(true);
    expect(wrapper.getComponent({ name: "DataTable" }).props("paged")).toBe(false);
    expect(wrapper.find(".el-pagination").exists()).toBe(false);
    expect(wrapper.text()).toContain("agent-one");
    expect(wrapper.text()).toContain(row.templateUrl);
    wrapper.unmount();
  });

  it("lets the code be entered on create and shows the encoded preview", async () => {
    vi.spyOn(ElMessage, "success").mockImplementation(() => ({}) as never);
    vi.mocked(templateApi.createLinkTemplate).mockResolvedValue(row);
    const wrapper = await mountPage();
    await wrapper.get('[data-test="create"]').trigger("click");
    await nextTick();
    expect(field("template-code").element.disabled).toBe(false);
    await field("template-code").setValue("agent-one");
    await field("template-name").setValue("AgentOne");
    await field("template-url").setValue(row.templateUrl);
    await field("sample-invite").setValue("A B~");
    await nextTick();
    expect(document.body.querySelector('[data-test="template-preview"]')?.textContent).toContain(
      "code=A+B%7E"
    );
    expect(document.body.querySelector('[data-test="template-preview"]')?.textContent).toContain(
      "7849640325"
    );
    await field("sample-invite").setValue("");
    await nextTick();
    expect(document.body.querySelector('[data-test="template-fallback"]')?.textContent).toContain(
      "该达人未设置邀请码时，链接会回退为微店商品页。"
    );
    await field("template-save").trigger("click");
    await flushPromises();
    expect(templateApi.createLinkTemplate).toHaveBeenCalledWith({
      templateCode: "agent-one",
      templateName: "AgentOne",
      templateUrl: row.templateUrl,
      sortOrder: 0
    });
    wrapper.unmount();
  });

  it("keeps the code read-only while editing", async () => {
    vi.spyOn(ElMessage, "success").mockImplementation(() => ({}) as never);
    vi.mocked(templateApi.editLinkTemplate).mockResolvedValue(null);
    const wrapper = await mountPage();
    const dataRow = wrapper.findAll("tr").find(item => item.text().includes(row.templateCode));
    await dataRow!.get('[data-test="edit"]').trigger("click");
    await nextTick();
    expect(field("template-code").element.disabled).toBe(true);
    await field("template-name").setValue("Renamed");
    await field("template-save").trigger("click");
    await flushPromises();
    expect(templateApi.editLinkTemplate).toHaveBeenCalledWith(row.id, {
      templateName: "Renamed",
      templateUrl: row.templateUrl,
      sortOrder: 10
    });
    wrapper.unmount();
  });

  it("asks with the exact disable sentence before changing status", async () => {
    vi.spyOn(ElMessageBox, "confirm").mockResolvedValue("confirm" as never);
    vi.spyOn(ElMessage, "success").mockImplementation(() => ({}) as never);
    vi.mocked(templateApi.updateLinkTemplateStatus).mockResolvedValue(null);
    const wrapper = await mountPage();
    const dataRow = wrapper.findAll("tr").find(item => item.text().includes(row.templateCode));
    await dataRow!.get('[data-test="status"]').trigger("click");
    await flushPromises();
    expect(ElMessageBox.confirm).toHaveBeenCalledWith(
      "停用后，使用该模板的达人链接将改为跳转到微店商品页。",
      "停用模板",
      { type: "warning" }
    );
    expect(templateApi.updateLinkTemplateStatus).toHaveBeenCalledWith(row.id, 0);
    wrapper.unmount();
  });
});
