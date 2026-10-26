import { DOMWrapper, flushPromises, mount, type VueWrapper } from "@vue/test-utils";
import { nextTick } from "vue";
import ElementPlus, { ElMessage } from "element-plus";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import * as userApi from "@/api/system/user";
import type { PermissionCatalogVO } from "@/api/system/user";
import type { UserVO } from "@/api/types";
import { i18n } from "@/locales";
import UserFormDialog from "../UserFormDialog.vue";

vi.mock("@/api/system/user");

const catalog: PermissionCatalogVO = {
  permissions: [
    { code: "kol:view", group: "KOL" },
    { code: "kol:edit", group: "KOL" },
    { code: "product:view", group: "PRODUCT" },
    { code: "future:extra", group: "OTHER" },
    { code: "mystery:code", group: "NEWGROUP" }
  ],
  templates: [
    {
      code: "OPERATOR",
      permissions: ["kol:view", "product:view"],
      dataScope: "ALL"
    },
    {
      code: "BD",
      permissions: ["kol:view", "kol:edit", "future:extra"],
      dataScope: "OWN"
    }
  ]
};

const staff: UserVO = {
  id: "9",
  username: "alice",
  nickname: "Alice",
  email: "alice@example.com",
  role: "STAFF",
  postTemplate: "BD",
  permissions: ["kol:view"],
  dataScope: "OWN",
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

async function fill(testId: string, value: string): Promise<void> {
  const host = document.body.querySelector(`[data-test="${testId}"]`);
  const field = host instanceof HTMLInputElement ? host : host?.querySelector("input");
  if (!(field instanceof HTMLInputElement)) throw new Error(`missing input ${testId}`);
  await new DOMWrapper(field).setValue(value);
}

function checked(code: string): boolean {
  return (
    document.body.querySelector(`[data-test="perm-${code}"]`)?.classList.contains("is-checked") ===
    true
  );
}

describe("user form dialog", () => {
  beforeEach(() => {
    vi.mocked(userApi.getPermissionCatalog).mockClear();
    vi.mocked(userApi.getUserPermissions).mockClear();
    vi.mocked(userApi.createUser).mockClear();
    vi.mocked(userApi.editUser).mockClear();
    vi.mocked(userApi.getPermissionCatalog).mockResolvedValue(catalog);
    vi.mocked(userApi.getUserPermissions).mockResolvedValue({
      permissions: ["kol:view"],
      dataScope: "OWN"
    });
    vi.mocked(userApi.createUser).mockResolvedValue(staff);
    vi.mocked(userApi.editUser).mockResolvedValue(null);
    vi.spyOn(ElMessage, "success").mockImplementation(() => ({}) as never);
  });

  afterEach(() => {
    document.body.innerHTML = "";
  });

  async function open(user: UserVO | null) {
    const wrapper = mount(UserFormDialog, {
      props: { modelValue: false, user },
      global: { plugins: [ElementPlus, i18n] },
      attachTo: document.body
    });
    await wrapper.setProps({ modelValue: true });
    await flushPromises();
    return wrapper;
  }

  it("hides permissions and data scope when creating a super administrator", async () => {
    const wrapper = await open(null);
    expect(document.body.textContent).not.toContain("拥有全部权限");
    expect(document.body.querySelector('[data-test="permission-area"]')).toBeNull();

    byTest(wrapper, "ElSelect", "account-type").vm.$emit("update:modelValue", "SUPER_ADMIN");
    await flushPromises();
    expect(document.body.querySelector('[data-test="all-permissions"]')?.textContent).toContain(
      "拥有全部权限"
    );
    expect(document.body.querySelector('[data-test="post-template"]')).toBeNull();
    expect(document.body.querySelector('[data-test="permission-area"]')).toBeNull();
    expect(document.body.querySelector('[data-test="data-scope"]')).toBeNull();

    await fill("username", "root2");
    await fill("password", "StrongPass!42");
    await fill("nickname", "Root Two");
    await new DOMWrapper(document.body.querySelector('[data-test="save-user"]')!).trigger("click");
    await flushPromises();

    expect(userApi.createUser).toHaveBeenCalledWith({
      username: "root2",
      password: "StrongPass!42",
      nickname: "Root Two",
      email: null,
      role: "SUPER_ADMIN"
    });
    wrapper.unmount();
  });

  it("prefills the selected template, then keeps later edits in the create body", async () => {
    const wrapper = await open(null);
    byTest(wrapper, "ElSelect", "post-template").vm.$emit("update:modelValue", "BD");
    await flushPromises();

    expect(document.body.querySelector('[data-test="permission-area"]')).not.toBeNull();
    expect(checked("kol:view")).toBe(true);
    expect(checked("kol:edit")).toBe(true);
    expect(checked("future:extra")).toBe(true);
    expect(checked("product:view")).toBe(false);
    expect(document.body.textContent).toContain("future:extra");
    expect(document.body.textContent).toContain("mystery:code");
    expect(document.body.textContent).toContain("查看达人");
    expect(document.body.textContent).toContain("NEWGROUP");
    expect(document.body.textContent).toContain(
      "用户与权限、系统配置、操作日志、提现标记打款、转移达人。预付审核与支付可分别授权给子账号。"
    );
    expect(byTest(wrapper, "ElRadioGroup", "data-scope").props("modelValue")).toBe("OWN");

    byTest(wrapper, "ElSelect", "post-template").vm.$emit("update:modelValue", "OPERATOR");
    await flushPromises();
    expect(checked("kol:view")).toBe(true);
    expect(checked("product:view")).toBe(true);
    expect(checked("kol:edit")).toBe(false);
    expect(byTest(wrapper, "ElRadioGroup", "data-scope").props("modelValue")).toBe("ALL");

    byTest(wrapper, "ElCheckbox", "perm-kol:edit").vm.$emit("change", true);
    await nextTick();
    byTest(wrapper, "ElCheckbox", "perm-product:view").vm.$emit("change", false);
    await nextTick();
    byTest(wrapper, "ElRadioGroup", "data-scope").vm.$emit("update:modelValue", "OWN");
    await flushPromises();

    await fill("username", "bd1");
    await fill("password", "StrongPass!42");
    await fill("nickname", "Bee");
    await fill("email", "bee@example.com");
    await new DOMWrapper(document.body.querySelector('[data-test="save-user"]')!).trigger("click");
    await flushPromises();

    expect(userApi.createUser).toHaveBeenCalledWith({
      username: "bd1",
      password: "StrongPass!42",
      nickname: "Bee",
      email: "bee@example.com",
      role: "STAFF",
      postTemplate: "OPERATOR",
      dataScope: "OWN",
      permissions: ["kol:view", "kol:edit"]
    });
    wrapper.unmount();
  });

  it("saves a post change without replacing the existing permissions", async () => {
    const wrapper = await open(staff);
    expect(document.body.querySelector('[data-test="permission-area"]')).toBeNull();
    expect(document.body.querySelector('[data-test="data-scope"]')).toBeNull();
    expect(document.body.textContent).toContain(
      "在编辑信息弹窗中打开权限设置，可调整此账号的权限和数据范围。"
    );
    expect(userApi.getPermissionCatalog).not.toHaveBeenCalled();
    await new DOMWrapper(
      document.body.querySelector('[data-test="open-user-permissions"]')!
    ).trigger("click");
    await flushPromises();
    expect(userApi.getUserPermissions).toHaveBeenCalledWith(staff.id);
    expect(userApi.getPermissionCatalog).toHaveBeenCalledTimes(1);
    expect(document.body.querySelector('[data-test="current-post"]')?.textContent).toContain(
      "商务"
    );

    byTest(wrapper, "ElSelect", "post-template").vm.$emit("update:modelValue", "OPERATOR");
    await flushPromises();
    await new DOMWrapper(document.body.querySelector('[data-test="save-user"]')!).trigger("click");
    await flushPromises();

    expect(userApi.editUser).toHaveBeenCalledWith("9", {
      nickname: "Alice",
      email: "alice@example.com",
      role: "STAFF",
      postTemplate: "OPERATOR"
    });
    const body = vi.mocked(userApi.editUser).mock.calls[0][1];
    expect(body).not.toHaveProperty("dataScope");
    expect(body).not.toHaveProperty("permissions");
    wrapper.unmount();
  });
});
