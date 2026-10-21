import { DOMWrapper, flushPromises, mount } from "@vue/test-utils";
import ElementPlus, { ElMessage } from "element-plus";
import { createPinia, setActivePinia } from "pinia";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import * as userApi from "@/api/system/user";
import type { UserVO } from "@/api/types";
import { i18n } from "@/locales";
import { ApiError } from "@/utils/http/error";
import UserFormDialog from "../UserFormDialog.vue";
import UserPage from "../index.vue";

vi.mock("@/api/system/user");

const HINT = "该账号还属于其他公司；姓名、邮箱、密码只能由平台管理员修改";

const row: UserVO = {
  id: "9",
  username: "alice",
  nickname: "Alice",
  email: "alice@example.com",
  role: "STAFF",
  postTemplate: "BD",
  permissions: ["kol:view"],
  dataScope: "OWN",
  tenantId: "1",
  tenantName: "A 公司",
  status: 1,
  lastLoginTime: null,
  createTime: "2026-10-05T00:00:00Z",
  companyCount: 2
};
const single: UserVO = { ...row, id: "10", username: "bob", companyCount: 1 };

describe("multi-company accounts in user management", () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    vi.clearAllMocks();
    vi.spyOn(ElMessage, "success").mockImplementation(() => ({}) as never);
    vi.spyOn(ElMessage, "error").mockImplementation(() => ({}) as never);
  });

  afterEach(() => {
    document.body.innerHTML = "";
  });

  it("tags multi-company rows and disables their password reset", async () => {
    vi.mocked(userApi.listUsers).mockResolvedValue({
      items: [row, single],
      total: "2",
      page: 1,
      size: 20
    });
    const wrapper = mount(UserPage, { global: { plugins: [ElementPlus, i18n] } });
    await flushPromises();

    const tags = wrapper.findAll('[data-test="multi-company"]');
    expect(tags).toHaveLength(1);
    expect(tags[0].text()).toBe("多公司");
    // Reset lives in the row "more" dropdown; the multi-company row carries the hint as a
    // title on its disabled entry, the single-company row stays enabled.
    const multiReset = document.body.querySelector<HTMLElement>('[data-test="reset-password-9"]');
    expect(multiReset?.classList.contains("is-disabled")).toBe(true);
    expect(multiReset?.getAttribute("title")).toBe(HINT);
    const singleReset = document.body.querySelector<HTMLElement>('[data-test="reset-password-10"]');
    expect(singleReset?.classList.contains("is-disabled")).toBe(false);
  });

  async function openForm(user: UserVO | null) {
    const wrapper = mount(UserFormDialog, {
      props: { modelValue: false, user },
      global: { plugins: [ElementPlus, i18n] },
      attachTo: document.body
    });
    await wrapper.setProps({ modelValue: true });
    await flushPromises();
    return wrapper;
  }

  function input(testId: string): HTMLInputElement {
    const host = document.body.querySelector(`[data-test="${testId}"]`);
    const field = host instanceof HTMLInputElement ? host : host?.querySelector("input");
    if (!(field instanceof HTMLInputElement)) throw new Error(`missing input ${testId}`);
    return field;
  }

  it("makes name and email read-only for a multi-company account and keeps the role editable", async () => {
    vi.mocked(userApi.editUser).mockResolvedValue(null);
    await openForm(row);
    expect(input("nickname").disabled).toBe(true);
    expect(input("email").disabled).toBe(true);
    expect(document.body.textContent).toContain(HINT);
    expect(
      document.body.querySelector('[data-test="account-type"]')?.classList.contains("is-disabled")
    ).toBe(false);

    (document.body.querySelector('[data-test="save-user"]') as HTMLButtonElement).click();
    await flushPromises();
    expect(userApi.editUser).toHaveBeenCalledWith("9", {
      nickname: "Alice",
      email: "alice@example.com",
      role: "STAFF",
      postTemplate: "BD"
    });
  });

  it("keeps name and email editable for a single-company account", async () => {
    await openForm(single);
    expect(input("nickname").disabled).toBe(false);
    expect(input("email").disabled).toBe(false);
    expect(document.body.querySelector('[data-test="personal-locked"]')).toBeNull();
  });

  it("shows the text of a 422 USER_MULTI_COMPANY_LOCKED", async () => {
    vi.mocked(userApi.editUser).mockRejectedValue(
      new ApiError({ status: 422, errorCode: "USER_MULTI_COMPANY_LOCKED", traceId: "t" })
    );
    await openForm(single);
    (document.body.querySelector('[data-test="save-user"]') as HTMLButtonElement).click();
    await flushPromises();
    expect(ElMessage.error).toHaveBeenCalledWith(HINT);
  });

  it("adds the platform administrator hint when a new username already exists", async () => {
    vi.mocked(userApi.getPermissionCatalog).mockResolvedValue({ permissions: [], templates: [] });
    vi.mocked(userApi.createUser).mockRejectedValue(
      new ApiError({ status: 409, errorCode: "USER_USERNAME_EXISTS", traceId: "t" })
    );
    const wrapper = await openForm(null);
    wrapper
      .findAllComponents({ name: "ElSelect" })
      .find(item => item.attributes("data-test") === "account-type")!
      .vm.$emit("update:modelValue", "SUPER_ADMIN");
    await new DOMWrapper(input("username")).setValue("alice");
    await new DOMWrapper(input("password")).setValue("Secret123!");
    await new DOMWrapper(input("nickname")).setValue("Alice");
    (document.body.querySelector('[data-test="save-user"]') as HTMLButtonElement).click();
    await flushPromises();
    expect(userApi.createUser).toHaveBeenCalled();
    expect(ElMessage.error).toHaveBeenCalledWith(
      "用户名已存在。如需让其他公司的账号加入本公司，请联系平台管理员"
    );
  });
});
