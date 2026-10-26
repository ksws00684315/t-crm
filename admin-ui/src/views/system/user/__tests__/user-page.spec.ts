import { flushPromises, mount } from "@vue/test-utils";
import ElementPlus, { ElMessageBox } from "element-plus";
import { createPinia, setActivePinia } from "pinia";
import { beforeEach, describe, expect, it, vi } from "vitest";
import * as kolApi from "@/api/kol/kol";
import * as userApi from "@/api/system/user";
import type { UserVO } from "@/api/types";
import { i18n } from "@/locales";
import { useLocaleStore } from "@/stores/locale";
import { SELECTABLE_ROLES } from "@/utils/permission";
import UserPage from "../index.vue";

vi.mock("@/api/system/user");
vi.mock("@/api/kol/kol");

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
    // Pages mount into the body; start every test clean.
    document.body.innerHTML = "";
    // BD rows ask for their creator count; unknown by default (no assertion depends on it).
    vi.mocked(kolApi.listKols).mockResolvedValue({ items: [], total: "0", page: 1, size: 1 });
  });

  async function mountPage(items: UserVO[] = [row], total = "1") {
    vi.mocked(userApi.listUsers).mockResolvedValue({ items, total, page: 1, size: 20 });
    const wrapper = mount(UserPage, {
      global: { plugins: [ElementPlus, i18n] },
      attachTo: document.body
    });
    await flushPromises();
    return wrapper;
  }

  /** A row action button; Element Plus also renders a hidden column-measuring copy outside rows. */
  function bodyItem(testId: string): HTMLElement {
    const el = [...document.body.querySelectorAll<HTMLElement>(`[data-test="${testId}"]`)].find(
      item => item.closest("tr") !== null
    );
    if (!el) throw new Error(`missing row action ${testId}`);
    return el;
  }

  async function clickBodyItem(testId: string) {
    bodyItem(testId).dispatchEvent(new MouseEvent("click", { bubbles: true }));
    await flushPromises();
  }

  it("loads the first page and renders the rows in Chinese", async () => {
    vi.mocked(userApi.listUsers).mockResolvedValue({
      items: [row],
      total: "1",
      page: 1,
      size: 20
    });
    const wrapper = mount(UserPage, {
      global: { plugins: [ElementPlus, i18n] },
      attachTo: document.body
    });
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
    expect(document.body.textContent).toContain("重置密码");
    expect(text).toContain("编辑");
    expect(text).toContain("停用");
    wrapper.unmount();
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
    const wrapper = mount(UserPage, {
      global: { plugins: [ElementPlus, i18n] },
      attachTo: document.body
    });
    await flushPromises();
    const pagination = wrapper.findComponent({ name: "ElPagination" });
    expect(pagination.exists()).toBe(true);
    expect(pagination.props("total")).toBe(42);
    expect(typeof pagination.props("total")).toBe("number");
    expect(wrapper.find(".el-pagination").exists()).toBe(true);
    wrapper.unmount();
  });

  it("renders the user list in English", async () => {
    useLocaleStore().setLocale("en-US");
    vi.mocked(userApi.listUsers).mockResolvedValue({
      items: [row],
      total: "1",
      page: 1,
      size: 20
    });
    const wrapper = mount(UserPage, {
      global: { plugins: [ElementPlus, i18n] },
      attachTo: document.body
    });
    await flushPromises();
    const text = wrapper.text();
    expect(text).toContain("Staff");
    expect(text).toContain("Operator");
    expect(text).toContain("1 items");
    expect(text).toContain("Own only");
    expect(text).toContain("Enabled");
    expect(document.body.textContent).toContain("Reset Password");
    expect(text).toContain("Create User");
    expect(text).toContain("Permissions");
    wrapper.unmount();
  });

  it("shows every permission for a super administrator and no permission action", async () => {
    const wrapper = await mountPage([
      { ...row, role: "SUPER_ADMIN", postTemplate: null, permissions: [], dataScope: "ALL" }
    ]);
    expect(wrapper.text()).toContain("超级管理员");
    expect(wrapper.text()).toContain("全部");
    expect(document.body.querySelector('[data-test="permissions-9007199254740993"]')).toBeNull();
    expect(document.body.querySelector('[data-test="transfer-kols-9007199254740993"]')).toBeNull();
    wrapper.unmount();
  });

  it("keeps platform administrator out of selectable company roles", () => {
    expect(SELECTABLE_ROLES).toEqual(["SUPER_ADMIN", "STAFF"]);
    expect(SELECTABLE_ROLES).not.toContain("PLATFORM_ADMIN");
  });

  it("asks for the creator count of every BD row once per load", async () => {
    const wrapper = await mountPage([
      row,
      { ...row, id: "2", username: "bob", postTemplate: "BD" }
    ]);
    expect(kolApi.listKols).toHaveBeenCalledWith({
      bdUserId: "9007199254740993",
      page: 1,
      size: 1
    });
    expect(kolApi.listKols).toHaveBeenCalledWith({ bdUserId: "2", page: 1, size: 1 });
    wrapper.unmount();
  });

  it("loads creator counts without adding a transfer action to account rows", async () => {
    type CountPage = { items: never[]; total: string; page: number; size: number };
    let resolveCount: (value: CountPage) => void = () => {};
    vi.mocked(kolApi.listKols).mockReturnValue(
      new Promise<CountPage>(resolve => {
        resolveCount = resolve;
      })
    );
    const wrapper = await mountPage();
    // The count still supports the disable confirmation, but transfer is managed from KOL tools.
    expect(wrapper.findComponent({ name: "DataTable" }).props("loading")).toBe(false);
    expect(wrapper.text()).toContain("alice");
    expect(document.body.querySelector('[data-test="transfer-kols-9007199254740993"]')).toBeNull();

    resolveCount({ items: [], total: "0", page: 1, size: 1 });
    await flushPromises();
    expect(kolApi.listKols).toHaveBeenCalledWith({
      bdUserId: row.id,
      page: 1,
      size: 1
    });
    wrapper.unmount();
  });

  it("explains the consequences when disabling a BD that owns creators", async () => {
    vi.mocked(kolApi.listKols).mockResolvedValue({ items: [], total: "3", page: 1, size: 1 });
    const wrapper = await mountPage();
    const confirm = vi.spyOn(ElMessageBox, "confirm").mockRejectedValue("cancel");
    await clickBodyItem("status-9007199254740993");
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
    await clickBodyItem("status-9007199254740993");
    await flushPromises();
    const message = confirm.mock.calls[0][0] as string;
    expect(message).toBe("确定要停用用户 alice 吗？");
    wrapper.unmount();
  });

  it("keeps the plain confirmation for a non-BD staff account", async () => {
    const wrapper = await mountPage([{ ...row, dataScope: "ALL" }]);
    const confirm = vi.spyOn(ElMessageBox, "confirm").mockRejectedValue("cancel");
    await clickBodyItem("status-9007199254740993");
    await flushPromises();
    const message = confirm.mock.calls[0][0] as string;
    expect(message).toBe("确定要停用用户 alice 吗？");
    wrapper.unmount();
  });

  it("keeps the four row actions and removes permission and transfer actions from the list", async () => {
    const wrapper = await mountPage([{ ...row, postTemplate: "BD" }]);
    const rowActions = document.body.querySelector(".el-table__body tr .actions-cell");
    expect(rowActions?.querySelectorAll("button")).toHaveLength(4);
    expect(document.body.querySelector('[data-test="permissions-9007199254740993"]')).toBeNull();
    expect(document.body.querySelector('[data-test="transfer-kols-9007199254740993"]')).toBeNull();
    wrapper.unmount();
  });
});
