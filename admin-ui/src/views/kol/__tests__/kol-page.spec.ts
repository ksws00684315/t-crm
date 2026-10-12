import { flushPromises, mount } from "@vue/test-utils";
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

function signIn(role: Role, dataScope: DataScope) {
  useUserStore().user = {
    id: "1",
    username: "me",
    nickname: "Me",
    email: null,
    role,
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
  const wrapper = mount(KolPage, { global: { plugins: [ElementPlus, i18n] } });
  await flushPromises();
  return wrapper;
}

describe("KOL list page", () => {
  beforeEach(() => setActivePinia(createPinia()));

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
    signIn("OPERATOR", "ALL");
    const wrapper = await mountPage("42");
    const pagination = wrapper.findComponent({ name: "ElPagination" });
    expect(pagination.props("total")).toBe(42);
    expect(wrapper.find(".el-pagination").exists()).toBe(true);
  });

  it.each([
    ["SUPER_ADMIN", "ALL", true, true],
    ["OPERATOR", "ALL", true, true],
    ["OPERATOR", "OWN", true, false],
    ["FINANCE", "ALL", false, false]
  ] as const)(
    "%s + %s: write buttons %s, BD management %s",
    async (role, scope, canWrite, canManageBd) => {
      signIn(role, scope);
      const wrapper = await mountPage();
      expect(wrapper.find('[data-test="create"]').exists()).toBe(canWrite);
      expect(wrapper.find('[data-test="import"]').exists()).toBe(canWrite);
      expect(wrapper.find('[data-test="edit"]').exists()).toBe(canWrite);
      expect(wrapper.find('[data-test="status"]').exists()).toBe(canWrite);
      expect(wrapper.find('[data-test="change-bd"]').exists()).toBe(canManageBd);
      expect(wrapper.find('[data-test="bd-filter"]').exists()).toBe(canManageBd);
      expect(wrapper.text()).toContain("详情");
      // Links: every role; a BD only lists their own KOLs anyway (task 06 §4.7).
      expect(wrapper.find('[data-test="links"]').exists()).toBe(true);
      if (canManageBd) {
        expect(bdApi.listBdUsers).toHaveBeenCalledTimes(1);
      } else {
        expect(bdApi.listBdUsers).not.toHaveBeenCalled();
      }
    }
  );

  it("never sends a BD filter for a BD", async () => {
    signIn("OPERATOR", "OWN");
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
});
