import { DOMWrapper, flushPromises, mount, type VueWrapper } from "@vue/test-utils";
import ElementPlus, { ElMessage } from "element-plus";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import * as userApi from "@/api/system/user";
import type { PermissionCatalogVO } from "@/api/system/user";
import type { UserVO } from "@/api/types";
import { i18n } from "@/locales";
import PermissionsDialog from "../PermissionsDialog.vue";

vi.mock("@/api/system/user");

const catalog: PermissionCatalogVO = {
  permissions: [
    { code: "kol:view", group: "KOL" },
    { code: "kol:edit", group: "KOL" },
    { code: "mystery:code", group: "OTHER" }
  ],
  templates: [
    {
      code: "BD",
      permissions: ["kol:edit", "kol:view"],
      dataScope: "OWN"
    }
  ]
};

const staff: UserVO = {
  id: "9",
  username: "bd",
  nickname: "Bee",
  email: null,
  role: "STAFF",
  postTemplate: "BD",
  permissions: ["kol:view"],
  dataScope: "ALL",
  tenantId: "1",
  tenantName: "默认公司",
  status: 1,
  lastLoginTime: null,
  createTime: "2026-10-05T00:00:00Z"
};

function byTest(wrapper: VueWrapper, name: string, testId: string) {
  const found = wrapper
    .findAllComponents({ name })
    .find(item => item.attributes("data-test") === testId);
  if (!found) throw new Error(`missing ${name} ${testId}`);
  return found;
}

function checked(code: string): boolean {
  return (
    document.body.querySelector(`[data-test="perm-${code}"]`)?.classList.contains("is-checked") ===
    true
  );
}

describe("permissions dialog", () => {
  beforeEach(() => {
    vi.mocked(userApi.getPermissionCatalog).mockResolvedValue(catalog);
    vi.mocked(userApi.getUserPermissions).mockResolvedValue({
      permissions: ["kol:view", "mystery:code"],
      dataScope: "ALL"
    });
    vi.mocked(userApi.updateUserPermissions).mockResolvedValue(null);
    vi.spyOn(ElMessage, "success").mockImplementation(() => ({}) as never);
  });

  afterEach(() => {
    document.body.innerHTML = "";
  });

  async function open(user: UserVO) {
    const wrapper = mount(PermissionsDialog, {
      props: { modelValue: false, user },
      global: { plugins: [ElementPlus, i18n] },
      attachTo: document.body
    });
    await wrapper.setProps({ modelValue: true });
    await flushPromises();
    return wrapper;
  }

  it("loads the saved set, resets from the saved template, and replaces the whole set", async () => {
    const wrapper = await open(staff);
    expect(document.body.textContent).toContain("当前岗位：商务");
    expect(document.body.textContent).toContain("mystery:code");
    expect(document.body.textContent).toContain("查看达人");
    expect(checked("kol:view")).toBe(true);
    expect(checked("mystery:code")).toBe(true);
    expect(checked("kol:edit")).toBe(false);
    expect(byTest(wrapper, "ElRadioGroup", "data-scope").props("modelValue")).toBe("ALL");

    await new DOMWrapper(document.body.querySelector('[data-test="reset-template"]')!).trigger(
      "click"
    );
    await flushPromises();
    expect(checked("kol:edit")).toBe(true);
    expect(checked("kol:view")).toBe(true);
    expect(checked("mystery:code")).toBe(false);
    expect(byTest(wrapper, "ElRadioGroup", "data-scope").props("modelValue")).toBe("OWN");

    await new DOMWrapper(document.body.querySelector('[data-test="save-permissions"]')!).trigger(
      "click"
    );
    await flushPromises();
    expect(userApi.updateUserPermissions).toHaveBeenCalledWith("9", {
      permissions: ["kol:edit", "kol:view"],
      dataScope: "OWN"
    });
    wrapper.unmount();
  });

  it("disables reset when the account has no post template", async () => {
    const wrapper = await open({ ...staff, postTemplate: null });
    const button = document.body.querySelector('[data-test="reset-template"]');
    expect(button).not.toBeNull();
    expect((button as HTMLButtonElement).disabled).toBe(true);
    wrapper.unmount();
  });

  it("checks kol:view together with kol:edit and explains the linkage", async () => {
    const wrapper = await open(staff);
    expect(document.body.textContent).toContain("勾选操作类权限会自动勾上它依赖的查看权限");

    document.body.querySelector<HTMLInputElement>('[data-test="perm-kol:edit"] input')!.click();
    await flushPromises();
    expect(checked("kol:edit")).toBe(true);
    expect(checked("kol:view")).toBe(true);
    wrapper.unmount();
  });

  it("clears kol:edit when kol:view is cleared", async () => {
    const wrapper = await open(staff);
    // Both on: the saved set already has kol:view; add kol:edit first.
    document.body.querySelector<HTMLInputElement>('[data-test="perm-kol:edit"] input')!.click();
    await flushPromises();
    expect(checked("kol:edit")).toBe(true);

    document.body.querySelector<HTMLInputElement>('[data-test="perm-kol:view"] input')!.click();
    await flushPromises();
    expect(checked("kol:view")).toBe(false);
    expect(checked("kol:edit")).toBe(false);
    wrapper.unmount();
  });
});
