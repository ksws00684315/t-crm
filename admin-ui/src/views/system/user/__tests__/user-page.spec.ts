import { flushPromises, mount } from "@vue/test-utils";
import ElementPlus from "element-plus";
import { createPinia, setActivePinia } from "pinia";
import { beforeEach, describe, expect, it, vi } from "vitest";
import * as userApi from "@/api/system/user";
import type { UserVO } from "@/api/types";
import { i18n } from "@/locales";
import { useLocaleStore } from "@/stores/locale";
import { SELECTABLE_ROLES } from "@/utils/permission";
import UserPage from "../index.vue";

vi.mock("@/api/system/user");

const row: UserVO = {
  id: "9007199254740993",
  username: "alice",
  nickname: "Alice",
  email: "alice@example.com",
  role: "STAFF",
  postTemplate: "OPERATOR",
  permissions: ["kol:view"],
  dataScope: "OWN",
  tenantId: "1",
  tenantName: "默认公司",
  status: 1,
  lastLoginTime: null,
  createTime: "2026-10-05T01:02:03Z"
};

describe("user management page", () => {
  beforeEach(() => setActivePinia(createPinia()));

  it("loads the first page and renders the rows in Chinese", async () => {
    vi.mocked(userApi.listUsers).mockResolvedValue({
      items: [row],
      total: "1",
      page: 1,
      size: 20
    });
    const wrapper = mount(UserPage, { global: { plugins: [ElementPlus, i18n] } });
    await flushPromises();
    expect(userApi.listUsers).toHaveBeenCalledWith({
      page: 1,
      size: 20,
      username: undefined,
      role: undefined,
      status: undefined
    });
    const text = wrapper.text();
    expect(text).toContain("alice");
    expect(text).toContain("子账号");
    expect(text).toContain("运营");
    expect(text).toContain("1 项");
    expect(text).toContain("仅本人");
    expect(text).toContain("启用");
    expect(text).toContain("重置密码");
    expect(text).toContain("权限设置");
  });

  it("converts a string total from the API so el-pagination renders", async () => {
    // Spec 03 serializes long total as a string. Passing that string straight to
    // el-pagination leaves the pager unrendered (Element Plus deprecation warning).
    vi.mocked(userApi.listUsers).mockResolvedValue({
      items: [row],
      total: "42",
      page: 1,
      size: 20
    });
    const wrapper = mount(UserPage, { global: { plugins: [ElementPlus, i18n] } });
    await flushPromises();
    const pagination = wrapper.findComponent({ name: "ElPagination" });
    expect(pagination.exists()).toBe(true);
    expect(pagination.props("total")).toBe(42);
    expect(typeof pagination.props("total")).toBe("number");
    expect(wrapper.find(".el-pagination").exists()).toBe(true);
  });

  it("renders the user list in English", async () => {
    useLocaleStore().setLocale("en-US");
    vi.mocked(userApi.listUsers).mockResolvedValue({
      items: [row],
      total: "1",
      page: 1,
      size: 20
    });
    const wrapper = mount(UserPage, { global: { plugins: [ElementPlus, i18n] } });
    await flushPromises();
    const text = wrapper.text();
    expect(text).toContain("Staff");
    expect(text).toContain("Operator");
    expect(text).toContain("1 items");
    expect(text).toContain("Own only");
    expect(text).toContain("Enabled");
    expect(text).toContain("Reset Password");
    expect(text).toContain("Create User");
    expect(text).toContain("Permissions");
  });

  it("shows every permission for a super administrator and no permission action", async () => {
    vi.mocked(userApi.listUsers).mockResolvedValue({
      items: [
        { ...row, role: "SUPER_ADMIN", postTemplate: null, permissions: [], dataScope: "ALL" }
      ],
      total: "1",
      page: 1,
      size: 20
    });
    const wrapper = mount(UserPage, { global: { plugins: [ElementPlus, i18n] } });
    await flushPromises();
    expect(wrapper.text()).toContain("超级管理员");
    expect(wrapper.text()).toContain("全部");
    expect(wrapper.find('[data-test="permissions"]').exists()).toBe(false);
  });

  it("keeps platform administrator out of selectable company roles", () => {
    expect(SELECTABLE_ROLES).toEqual(["SUPER_ADMIN", "STAFF"]);
    expect(SELECTABLE_ROLES).not.toContain("PLATFORM_ADMIN");
  });
});
