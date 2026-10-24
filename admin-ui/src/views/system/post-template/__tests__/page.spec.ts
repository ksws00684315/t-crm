import { flushPromises, mount } from "@vue/test-utils";
import ElementPlus, { ElMessage, ElMessageBox } from "element-plus";
import { createPinia, setActivePinia } from "pinia";
import { beforeEach, describe, expect, it, vi } from "vitest";
import * as postTemplateApi from "@/api/system/post-template";
import * as userApi from "@/api/system/user";
import { i18n } from "@/locales";
import PostTemplatePage from "../index.vue";

vi.mock("@/api/system/post-template");
vi.mock("@/api/system/user");

/**
 * 权限设置 (task 32 §2.3, contract 33 §3.4): template tabs and module table, the "only future
 * accounts" hint, save and reset.
 */
const catalog: userApi.PermissionCatalogVO = {
  permissions: [
    { code: "kol:view", group: "KOL" },
    { code: "kol:edit", group: "KOL" },
    { code: "product:view", group: "PRODUCT" }
  ],
  templates: [
    { code: "BD", permissions: ["kol:view", "kol:edit"], dataScope: "OWN" },
    { code: "OPERATOR", permissions: ["product:view"], dataScope: "ALL" },
    {
      code: "FINANCE",
      permissions: ["kol:view", "product:view", "order:view"],
      dataScope: "ALL"
    }
  ]
};

function templates(overrides: Partial<postTemplateApi.PostTemplateVO> = {}) {
  return [
    {
      postTemplate: "BD",
      dataScope: "OWN",
      permissions: ["kol:view", "kol:edit"],
      isDefault: true
    },
    { postTemplate: "OPERATOR", dataScope: "ALL", permissions: ["product:view"], isDefault: false },
    {
      postTemplate: "FINANCE",
      dataScope: "ALL",
      permissions: ["kol:view", "product:view", "order:view"],
      isDefault: true
    }
  ].map(item => ({ ...item, ...overrides })) as postTemplateApi.PostTemplateVO[];
}

async function mountPage(items = templates()) {
  vi.mocked(postTemplateApi.listPostTemplates).mockResolvedValue(items);
  vi.mocked(userApi.getPermissionCatalog).mockResolvedValue(catalog);
  const wrapper = mount(PostTemplatePage, {
    global: { plugins: [ElementPlus, i18n] },
    attachTo: document.body
  });
  await flushPromises();
  return wrapper;
}

describe("post template page (task 32 §2.3)", () => {
  beforeEach(() => {
    document.body.innerHTML = "";
    vi.mocked(postTemplateApi.savePostTemplate).mockReset();
    vi.mocked(postTemplateApi.resetPostTemplate).mockReset();
    setActivePinia(createPinia());
  });

  it("shows role tabs, the active template state, and the only-future-accounts hint", async () => {
    const wrapper = await mountPage();
    const text = wrapper.text();
    expect(text).toContain("只影响之后新建的账号，不会修改已有账号的权限");
    expect(text).toContain("商务岗位模板");
    expect(text).toContain("运营");
    expect(text).toContain("财务");
    expect(text).toContain("默认");
    expect(wrapper.find('[data-test="permission-matrix"]').exists()).toBe(true);
    expect(wrapper.find('[data-test="template-tabs"]').exists()).toBe(true);
    await wrapper.get("#tab-OPERATOR").trigger("click");
    await flushPromises();
    expect(wrapper.text()).toContain("运营岗位模板");
    expect(wrapper.text()).toContain("已自定义");
    wrapper.unmount();
  });

  it("saves one card's scope and permission set", async () => {
    const success = vi.spyOn(ElMessage, "success").mockImplementation(() => ({}) as never);
    vi.mocked(postTemplateApi.savePostTemplate).mockResolvedValue(null);
    const wrapper = await mountPage();
    expect(wrapper.get('[data-test="template-permission-kol:view"] input').element).toBeTruthy();
    await wrapper.get('[data-test="template-save-BD"]').trigger("click");
    await flushPromises();
    expect(postTemplateApi.savePostTemplate).toHaveBeenCalledWith(
      "BD",
      expect.objectContaining({ dataScope: "OWN" })
    );
    const saved = vi.mocked(postTemplateApi.savePostTemplate).mock.calls[0][1];
    expect(saved.permissions).toContain("kol:view");
    expect(success).toHaveBeenCalledWith("岗位模板已保存");
    wrapper.unmount();
  });

  it("resets a card to the code default after the confirm", async () => {
    vi.spyOn(ElMessageBox, "confirm").mockResolvedValue({ action: "confirm" } as never);
    vi.mocked(postTemplateApi.resetPostTemplate).mockResolvedValue(null);
    vi.mocked(postTemplateApi.listPostTemplates).mockResolvedValue(templates());
    const success = vi.spyOn(ElMessage, "success").mockImplementation(() => ({}) as never);
    const wrapper = await mountPage();
    await wrapper.get("#tab-OPERATOR").trigger("click");
    await flushPromises();
    await wrapper.get('[data-test="template-reset"]').trigger("click");
    await flushPromises();
    expect(postTemplateApi.resetPostTemplate).toHaveBeenCalledWith("OPERATOR");
    expect(success).toHaveBeenCalledWith("已恢复默认");
    wrapper.unmount();
  });
});
