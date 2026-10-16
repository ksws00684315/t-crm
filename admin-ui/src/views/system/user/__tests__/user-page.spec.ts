import { flushPromises, mount } from "@vue/test-utils";
import ElementPlus, { ElMessageBox } from "element-plus";
import { createPinia, setActivePinia } from "pinia";
import { beforeEach, describe, expect, it, vi } from "vitest";
import * as bdApi from "@/api/system/bd-user";
import * as kolApi from "@/api/kol/kol";
import * as userApi from "@/api/system/user";
import type { UserVO } from "@/api/types";
import { i18n } from "@/locales";
import { useLocaleStore } from "@/stores/locale";
import { SELECTABLE_ROLES } from "@/utils/permission";
import UserPage from "../index.vue";

vi.mock("@/api/system/user");
vi.mock("@/api/kol/kol");
vi.mock("@/api/system/bd-user");

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
  beforeEach(() => {
    setActivePinia(createPinia());
    // BD rows ask for their creator count; unknown by default (no assertion depends on it).
    vi.mocked(kolApi.listKols).mockResolvedValue({ items: [], total: "0", page: 1, size: 1 });
  });

  async function mountPage(items: UserVO[] = [row], total = "1") {
    vi.mocked(userApi.listUsers).mockResolvedValue({ items, total, page: 1, size: 20 });
    const wrapper = mount(UserPage, { global: { plugins: [ElementPlus, i18n] } });
    await flushPromises();
    return wrapper;
  }

  function actionButton(wrapper: Awaited<ReturnType<typeof mountPage>>, text: string) {
    const button = wrapper.findAll("button").find(item => item.text() === text);
    if (!button) throw new Error(`missing action ${text}`);
    return button;
  }

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
    const wrapper = await mountPage([
      { ...row, role: "SUPER_ADMIN", postTemplate: null, permissions: [], dataScope: "ALL" }
    ]);
    expect(wrapper.text()).toContain("超级管理员");
    expect(wrapper.text()).toContain("全部");
    expect(wrapper.find('[data-test="permissions"]').exists()).toBe(false);
    expect(wrapper.find('[data-test="transfer-kols"]').exists()).toBe(false);
  });

  it("keeps platform administrator out of selectable company roles", () => {
    expect(SELECTABLE_ROLES).toEqual(["SUPER_ADMIN", "STAFF"]);
    expect(SELECTABLE_ROLES).not.toContain("PLATFORM_ADMIN");
  });

  it("asks for the creator count of every BD row once per load", async () => {
    await mountPage([row, { ...row, id: "2", username: "bob", postTemplate: "BD" }]);
    expect(kolApi.listKols).toHaveBeenCalledWith({
      bdUserId: "9007199254740993",
      page: 1,
      size: 1
    });
    expect(kolApi.listKols).toHaveBeenCalledWith({ bdUserId: "2", page: 1, size: 1 });
  });

  it("explains the consequences when disabling a BD that owns creators", async () => {
    vi.mocked(kolApi.listKols).mockResolvedValue({ items: [], total: "3", page: 1, size: 1 });
    const wrapper = await mountPage();
    const confirm = vi.spyOn(ElMessageBox, "confirm").mockRejectedValue("cancel");
    await actionButton(wrapper, "停用").trigger("click");
    await flushPromises();
    expect(confirm).toHaveBeenCalled();
    const message = confirm.mock.calls[0][0] as string;
    expect(message).toContain("停用用户 alice 吗？");
    expect(message).toContain(
      "其名下 3 位达人将立即转为未分配（归公司），转给他的预约转移将被取消。"
    );
    wrapper.unmount();
  });

  it("keeps the plain confirmation when the BD owns no creators", async () => {
    const wrapper = await mountPage();
    const confirm = vi.spyOn(ElMessageBox, "confirm").mockRejectedValue("cancel");
    await actionButton(wrapper, "停用").trigger("click");
    await flushPromises();
    const message = confirm.mock.calls[0][0] as string;
    expect(message).toBe("确定要停用用户 alice 吗？");
    wrapper.unmount();
  });

  it("keeps the plain confirmation for a non-BD staff account", async () => {
    const wrapper = await mountPage([{ ...row, dataScope: "ALL" }]);
    const confirm = vi.spyOn(ElMessageBox, "confirm").mockRejectedValue("cancel");
    await actionButton(wrapper, "停用").trigger("click");
    await flushPromises();
    const message = confirm.mock.calls[0][0] as string;
    expect(message).toBe("确定要停用用户 alice 吗？");
    wrapper.unmount();
  });

  it("opens the transfer dialog with the whole BD as the target", async () => {
    vi.mocked(kolApi.listKols).mockResolvedValue({ items: [], total: "3", page: 1, size: 1 });
    vi.mocked(bdApi.listBdUsers).mockResolvedValue([]);
    const wrapper = await mountPage();
    await actionButton(wrapper, "转移名下达人").trigger("click");
    await flushPromises();
    const dialog = wrapper.findComponent({ name: "BdTransferDialog" });
    expect(dialog.props("modelValue")).toBe(true);
    expect(dialog.props("target")).toEqual({
      fromBdUserId: "9007199254740993",
      fromBdName: "Alice",
      count: 3
    });
    wrapper.unmount();
  });

  it("disables the transfer action when the BD owns no creators", async () => {
    const wrapper = await mountPage();
    const button = wrapper.get('[data-test="transfer-kols"]');
    expect(button.attributes("disabled")).toBeDefined();
    wrapper.unmount();
  });
});
