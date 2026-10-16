import { flushPromises, mount } from "@vue/test-utils";
import { nextTick } from "vue";
import ElementPlus from "element-plus";
import { createPinia, setActivePinia } from "pinia";
import { beforeEach, describe, expect, it, vi } from "vitest";
import * as kolApi from "@/api/kol/kol";
import * as bdApi from "@/api/system/bd-user";
import type { DataScope, KolVO, Role } from "@/api/types";
import { i18n } from "@/locales";
import { useLocaleStore } from "@/stores/locale";
import { useUserStore } from "@/stores/user";
import KolPage from "../index.vue";

vi.mock("@/api/kol/kol");
vi.mock("@/api/link/link");
vi.mock("@/api/system/bd-user");

const row: KolVO = {
  id: "9007199254740993",
  kolCode: "KOL0001",
  name: "Alice",
  email: "alice@example.com",
  country: "US",
  socialAccounts: [],
  bdUserId: null,
  bdUserName: null,
  defaultRate: "0.1550",
  status: 1,
  remark: null,
  createTime: "2026-10-05T01:02:03Z",
  updateTime: "2026-10-05T01:02:03Z"
};

function signIn(role: Role, dataScope: DataScope, permissions: string[] = []) {
  useUserStore().user = {
    id: "1",
    username: "me",
    nickname: "Me",
    email: null,
    role,
    postTemplate: role === "STAFF" ? "BD" : null,
    permissions,
    dataScope,
    tenantId: "1",
    tenantName: "默认公司",
    status: 1,
    lastLoginTime: null,
    createTime: "2026-10-01T00:00:00Z"
  };
}

async function mountPage(total = "1") {
  vi.mocked(kolApi.listKols).mockResolvedValue({ items: [row], total, page: 1, size: 20 });
  vi.mocked(bdApi.listBdUsers).mockResolvedValue([
    { id: "5", username: "bd5", nickname: "BD Five" }
  ]);
  const wrapper = mount(KolPage, {
    global: { plugins: [ElementPlus, i18n] },
    attachTo: document.body
  });
  await flushPromises();
  return wrapper;
}

describe("KOL list page", () => {
  beforeEach(() => {
    document.body.innerHTML = "";
    setActivePinia(createPinia());
  });

  it("renders rows with rate percentage and the unassigned BD in Chinese", async () => {
    signIn("SUPER_ADMIN", "ALL");
    const wrapper = await mountPage();
    const text = wrapper.text();
    expect(text).toContain("KOL0001");
    expect(text).toContain("15.5%");
    expect(text).toContain("未分配");
    expect(text).toContain("启用");
  });

  it("renders the creator list in English", async () => {
    useLocaleStore().setLocale("en-US");
    signIn("SUPER_ADMIN", "ALL");
    const wrapper = await mountPage();
    const text = wrapper.text();
    expect(text).toContain("Creator Code");
    expect(text).toContain("Unassigned");
    expect(text).toContain("Enabled");
    expect(text).toContain("Bulk Import");
  });

  it("converts the string total before handing it to el-pagination", async () => {
    signIn("STAFF", "ALL", ["kol:view"]);
    const wrapper = await mountPage("42");
    const pagination = wrapper.findComponent({ name: "ElPagination" });
    expect(pagination.props("total")).toBe(42);
    expect(wrapper.find(".el-pagination").exists()).toBe(true);
  });

  it("shows write, BD change, BD filter, links and export to the super administrator", async () => {
    signIn("SUPER_ADMIN", "ALL");
    const wrapper = await mountPage();
    expect(wrapper.find('[data-test="create"]').exists()).toBe(true);
    expect(wrapper.find('[data-test="import"]').exists()).toBe(true);
    expect(wrapper.find('[data-test="edit"]').exists()).toBe(true);
    expect(wrapper.find('[data-test="status"]').exists()).toBe(true);
    expect(wrapper.find('[data-test="change-bd"]').exists()).toBe(true);
    expect(wrapper.find('[data-test="bd-filter"]').exists()).toBe(true);
    expect(wrapper.find('[data-test="links"]').exists()).toBe(true);
    expect(wrapper.find('[data-test="export"]').exists()).toBe(true);
    expect(wrapper.text()).toContain("详情");
    expect(bdApi.listBdUsers).toHaveBeenCalledTimes(1);
  });

  it("lets a non-OWN editor write, filter and assign, but not change BD", async () => {
    signIn("STAFF", "ALL", ["kol:view", "kol:edit", "product:view", "data:export"]);
    const wrapper = await mountPage();
    expect(wrapper.find('[data-test="create"]').exists()).toBe(true);
    expect(wrapper.find('[data-test="import"]').exists()).toBe(true);
    expect(wrapper.find('[data-test="edit"]').exists()).toBe(true);
    expect(wrapper.find('[data-test="status"]').exists()).toBe(true);
    expect(wrapper.find('[data-test="change-bd"]').exists()).toBe(false);
    expect(wrapper.find('[data-test="bd-filter"]').exists()).toBe(true);
    expect(wrapper.find('[data-test="links"]').exists()).toBe(true);
    expect(wrapper.find('[data-test="export"]').exists()).toBe(true);
    expect(bdApi.listBdUsers).toHaveBeenCalledTimes(1);
    await wrapper.find('[data-test="create"]').trigger("click");
    await nextTick();
    await flushPromises();
    expect(document.body.querySelector('[data-test="assign-bd"]')).not.toBeNull();
    wrapper.unmount();
  });

  it("hides BD filter, BD assign and export for an OWN editor", async () => {
    signIn("STAFF", "OWN", ["kol:view", "kol:edit", "product:view"]);
    const wrapper = await mountPage();
    expect(wrapper.find('[data-test="create"]').exists()).toBe(true);
    expect(wrapper.find('[data-test="edit"]').exists()).toBe(true);
    expect(wrapper.find('[data-test="status"]').exists()).toBe(true);
    expect(wrapper.find('[data-test="change-bd"]').exists()).toBe(false);
    expect(wrapper.find('[data-test="bd-filter"]').exists()).toBe(false);
    expect(wrapper.find('[data-test="links"]').exists()).toBe(true);
    expect(wrapper.find('[data-test="export"]').exists()).toBe(false);
    expect(bdApi.listBdUsers).not.toHaveBeenCalled();
    await wrapper.find('[data-test="create"]').trigger("click");
    await nextTick();
    await flushPromises();
    expect(document.body.querySelector('[data-test="assign-bd"]')).toBeNull();
    expect(document.body.textContent).toContain("新增达人");
    wrapper.unmount();
  });

  it("shows the BD filter without write buttons when the account can only view creators", async () => {
    signIn("STAFF", "ALL", ["kol:view"]);
    const wrapper = await mountPage();
    expect(wrapper.find('[data-test="create"]').exists()).toBe(false);
    expect(wrapper.find('[data-test="import"]').exists()).toBe(false);
    expect(wrapper.find('[data-test="edit"]').exists()).toBe(false);
    expect(wrapper.find('[data-test="status"]').exists()).toBe(false);
    expect(wrapper.find('[data-test="change-bd"]').exists()).toBe(false);
    expect(wrapper.find('[data-test="bd-filter"]').exists()).toBe(true);
    expect(wrapper.find('[data-test="links"]').exists()).toBe(false);
    expect(wrapper.find('[data-test="export"]').exists()).toBe(false);
    expect(bdApi.listBdUsers).toHaveBeenCalledTimes(1);
  });

  it("never sends a BD filter for an OWN account", async () => {
    signIn("STAFF", "OWN", ["kol:view"]);
    await mountPage();
    expect(kolApi.listKols).toHaveBeenCalledWith({
      page: 1,
      size: 20,
      keyword: undefined,
      country: undefined,
      status: undefined,
      bdUserId: undefined
    });
  });

  it("pages with the applied conditions, not text typed without searching", async () => {
    signIn("SUPER_ADMIN", "ALL");
    const wrapper = await mountPage("60");
    const keyword = wrapper.findAll("input").find(input => input.attributes("maxlength") === "128");
    await keyword!.setValue("applied");
    await keyword!.trigger("keyup.enter");
    await flushPromises();
    await keyword!.setValue("typed-only");
    vi.mocked(kolApi.listKols).mockClear();
    wrapper.findComponent({ name: "ElPagination" }).vm.$emit("current-change", 2);
    await flushPromises();
    expect(kolApi.listKols).toHaveBeenLastCalledWith(
      expect.objectContaining({ page: 2, keyword: "applied" })
    );
    expect(wrapper.text()).toContain("applied");
    expect(wrapper.text()).not.toContain("typed-only");
  });
});
