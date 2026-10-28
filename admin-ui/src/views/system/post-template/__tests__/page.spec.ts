import { flushPromises, mount } from "@vue/test-utils";
import ElementPlus, { ElMessage, ElMessageBox } from "element-plus";
import { createPinia, setActivePinia } from "pinia";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
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

async function mountPage(items = templates(), permissionCatalog = catalog) {
  vi.mocked(postTemplateApi.listPostTemplates).mockResolvedValue(items);
  vi.mocked(userApi.getPermissionCatalog).mockResolvedValue(permissionCatalog);
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
    i18n.global.locale.value = "zh-CN";
    vi.stubGlobal(
      "matchMedia",
      vi.fn(() => ({ matches: false, addEventListener: vi.fn(), removeEventListener: vi.fn() }))
    );
    vi.mocked(postTemplateApi.resetPostTemplate).mockResolvedValue(null);
    vi.spyOn(ElMessage, "success").mockImplementation(() => ({}) as never);
    vi.spyOn(ElMessage, "warning").mockImplementation(() => ({}) as never);
    vi.mocked(postTemplateApi.savePostTemplate).mockReset();
    vi.mocked(postTemplateApi.resetPostTemplate).mockReset();
    setActivePinia(createPinia());
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
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

  it("shows three columns with all permissions expanded and one unified scope", async () => {
    const wrapper = await mountPage();
    expect(wrapper.findAll("th").map(item => item.text())).toEqual([
      "权限模块",
      "可配置权限",
      "模块全选"
    ]);
    expect(wrapper.findAll("details")).toHaveLength(0);
    expect(wrapper.find('[data-test^="module-level-"]').exists()).toBe(false);
    expect(wrapper.find('[data-test^="module-scope-"]').exists()).toBe(false);
    expect(wrapper.findAll('[data-test^="scope-"]')).toHaveLength(1);
    expect(wrapper.findAll('[data-test^="template-permission-"]')).toHaveLength(3);
    expect(wrapper.get('[data-test="module-count-KOL"]').text()).toBe("已选 2 / 2 项");
    wrapper.unmount();
  });

  it("updates module counts, tri-state selection and preserves unrelated grants", async () => {
    const wrapper = await mountPage(
      templates({ permissions: ["kol:view", "product:view", "future:permission"] })
    );
    const all = () =>
      wrapper.get('[data-test="template-module-all-KOL"] input').element as HTMLInputElement;
    expect(all().indeterminate).toBe(true);
    expect(wrapper.get('[data-test="module-count-KOL"]').text()).toBe("已选 1 / 2 项");
    await wrapper.get('[data-test="template-module-all-KOL"] input').setValue(true);
    expect(all().checked).toBe(true);
    expect(all().indeterminate).toBe(false);
    expect(wrapper.get('[data-test="module-count-KOL"]').text()).toBe("已选 2 / 2 项");
    await wrapper.get('[data-test="template-module-all-KOL"] input').setValue(false);
    expect(all().checked).toBe(false);
    expect(wrapper.get('[data-test="module-count-KOL"]').text()).toBe("已选 0 / 2 项");
    await wrapper.get('[data-test="template-save-BD"]').trigger("click");
    await flushPromises();
    expect(postTemplateApi.savePostTemplate).toHaveBeenCalledWith("BD", {
      dataScope: "OWN",
      permissions: ["product:view", "future:permission"]
    });
    wrapper.unmount();
  });

  it("applies edit/view dependencies to direct checkboxes", async () => {
    const wrapper = await mountPage(templates({ permissions: ["product:view"] }));
    await wrapper.get('[data-test="template-permission-kol:edit"] input').setValue(true);
    expect(
      (wrapper.get('[data-test="template-permission-kol:view"] input').element as HTMLInputElement)
        .checked
    ).toBe(true);
    await wrapper.get('[data-test="template-permission-kol:view"] input').setValue(false);
    expect(
      (wrapper.get('[data-test="template-permission-kol:edit"] input').element as HTMLInputElement)
        .checked
    ).toBe(false);
    wrapper.unmount();
  });

  it("preserves unknown grants and renders new catalog modules", async () => {
    const permissions = ["kol:view", "kol:edit", "product:view", "future:permission", "new:action"];
    const wrapper = await mountPage(templates({ permissions }), {
      ...catalog,
      permissions: [...catalog.permissions, { code: "new:action", group: "NEW_GROUP" }]
    });
    expect(wrapper.get('[data-test="template-permission-new:action"]').text()).toContain(
      "new:action"
    );
    await wrapper.get('[data-test="template-save-BD"]').trigger("click");
    await flushPromises();
    expect(postTemplateApi.savePostTemplate).toHaveBeenCalledWith("BD", {
      dataScope: "OWN",
      permissions
    });
    wrapper.unmount();
  });

  it("keeps dashboard grants as individually configurable checkboxes", async () => {
    const permissions = ["dashboard:shop", "dashboard:team", "dashboard:kol"];
    const wrapper = await mountPage(templates({ permissions }), {
      ...catalog,
      permissions: permissions.map(code => ({ code, group: "DASHBOARD" }))
    });
    expect(wrapper.get('[data-test="module-count-DASHBOARD"]').text()).toBe("已选 3 / 3 项");
    expect(wrapper.findAll('[data-test^="template-permission-dashboard:"]')).toHaveLength(3);
    wrapper.unmount();
  });

  it("provides descriptions on focus without toggling the permission", async () => {
    const wrapper = await mountPage();
    const input = wrapper.get('[data-test="template-permission-kol:view"] input');
    const before = (input.element as HTMLInputElement).checked;
    expect((input.element as HTMLInputElement).labels?.[0]?.textContent).toContain(
      "查看达人列表、详情和商务归属历史"
    );
    await input.trigger("focusin");
    await flushPromises();
    expect(document.body.textContent).toContain("查看达人列表、详情和商务归属历史");
    expect((input.element as HTMLInputElement).checked).toBe(before);
    wrapper.unmount();
  });

  it("shows mobile module cards without a duplicate table", async () => {
    vi.stubGlobal(
      "matchMedia",
      vi.fn(() => ({ matches: true, addEventListener: vi.fn(), removeEventListener: vi.fn() }))
    );
    const wrapper = await mountPage();
    expect(wrapper.find('[data-test="permission-matrix"]').exists()).toBe(false);
    expect(wrapper.find('[data-test="permission-cards"]').exists()).toBe(true);
    expect(wrapper.findAll('[data-test^="template-permission-"]')).toHaveLength(3);
    await wrapper.get('[data-test="template-permission-kol:view"] input').setValue(false);
    expect(wrapper.get('[data-test="module-count-KOL"]').text()).toBe("已选 0 / 2 项");
    wrapper.unmount();
  });

  it("saves the current template's changed unified scope and prevents duplicate submissions", async () => {
    let finish!: (value: null) => void;
    vi.mocked(postTemplateApi.savePostTemplate).mockImplementation(
      () =>
        new Promise(resolve => {
          finish = resolve;
        })
    );
    const wrapper = await mountPage();
    await wrapper.get('[data-test="scope-BD"] input[value="ALL"]').setValue(true);
    await wrapper.get('[data-test="template-save-BD"]').trigger("click");
    await wrapper.get('[data-test="template-save-BD"]').trigger("click");
    expect(postTemplateApi.savePostTemplate).toHaveBeenCalledTimes(1);
    expect(postTemplateApi.savePostTemplate).toHaveBeenCalledWith(
      "BD",
      expect.objectContaining({ dataScope: "ALL" })
    );
    expect(wrapper.get('[data-test="template-reset"]').attributes("disabled")).toBeDefined();
    finish(null);
    await flushPromises();
    wrapper.unmount();
  });

  it("keeps edits when saving fails and allows retry", async () => {
    vi.mocked(postTemplateApi.savePostTemplate).mockRejectedValue(new Error("save failed"));
    const wrapper = await mountPage();
    await wrapper.get('[data-test="template-permission-kol:edit"] input').setValue(false);
    await wrapper.get('[data-test="template-save-BD"]').trigger("click");
    await flushPromises();
    expect(
      (wrapper.get('[data-test="template-permission-kol:edit"] input').element as HTMLInputElement)
        .checked
    ).toBe(false);
    expect(wrapper.get('[data-test="template-save-BD"]').attributes("disabled")).toBeUndefined();
    wrapper.unmount();
  });

  it("does not reset when the confirmation is cancelled", async () => {
    vi.spyOn(ElMessageBox, "confirm").mockRejectedValue("cancel");
    const wrapper = await mountPage();
    await wrapper.get('[data-test="template-reset"]').trigger("click");
    await flushPromises();
    expect(postTemplateApi.resetPostTemplate).not.toHaveBeenCalled();
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
