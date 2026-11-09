import { DOMWrapper, flushPromises, mount, type VueWrapper } from "@vue/test-utils";
import ElementPlus, { ElMessage, ElMessageBox } from "element-plus";
import { createPinia, setActivePinia } from "pinia";
import { createMemoryHistory, createRouter } from "vue-router";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import * as libraryApi from "@/api/kol-library/kol";
import type { LibraryKolVO } from "@/api/kol-library/kol";
import type { CompanyEdition, Role } from "@/api/types";
import { i18n } from "@/locales";
import { routes } from "@/router/routes";
import { useTabsStore } from "@/stores/tabs";
import { useUserStore } from "@/stores/user";
import LibraryKolListPage from "../kol-list/index.vue";

vi.mock("@/api/kol-library/kol");

/**
 * Creator list of the library (requirement 1.2.5 ②): the columns of the requirement only, the
 * filters, the nickname opening the detail tab and the super-administrator-only delete.
 */

const row: LibraryKolVO = {
  id: "11",
  platform: "TIKTOK",
  kolUsername: "beautybyamy",
  displayName: "Beauty by Amy",
  followers: "12000",
  countryCode: "US",
  categories: ["BEAUTY_PERSONAL_CARE", "HOME_DAILY"],
  grade: "A",
  cooperationStage: "REPEAT_INVESTMENT",
  outreachStatus: "REPLIED",
  followUpOwner: "Amy BD",
  updateTime: "2026-11-01T02:00:00Z"
};

function signIn(edition: CompanyEdition, role: Role = "SUPER_ADMIN") {
  const user = {
    id: "1",
    username: "me",
    nickname: "Me",
    email: null,
    role,
    postTemplate: null,
    permissions: role === "STAFF" ? ["kol-library:view"] : [],
    dataScope: "ALL" as const,
    tenantId: "9",
    tenantName: "B 公司",
    edition,
    status: 1 as const,
    lastLoginTime: null,
    createTime: "2026-10-01T00:00:00Z"
  };
  useUserStore().user = user;
}

async function mountPage(
  edition: CompanyEdition = "KOL_LIBRARY",
  role: Role = "SUPER_ADMIN"
): Promise<{ wrapper: VueWrapper; router: ReturnType<typeof createRouter> }> {
  signIn(edition, role);
  const router = createRouter({ history: createMemoryHistory(), routes });
  await router.push("/kol-library/kols");
  const wrapper = mount(LibraryKolListPage, {
    global: { plugins: [ElementPlus, i18n, router], stubs: { DataTable: false } }
  });
  await flushPromises();
  return { wrapper, router };
}

/** Waits one macrotask: a page-initiated push settles only after the router's async queue. */
async function settle(): Promise<void> {
  await new Promise(resolve => setTimeout(resolve, 0));
  await flushPromises();
}

function headers(wrapper: VueWrapper): string[] {
  return wrapper.findAll(".el-table__header th").map(cell => cell.text());
}

describe("creator library list", () => {
  beforeEach(() => {
    document.body.innerHTML = "";
    localStorage.clear();
    sessionStorage.clear();
    setActivePinia(createPinia());
    vi.mocked(libraryApi.listLibraryKols)
      .mockReset()
      .mockResolvedValue({
        items: [row],
        total: "1",
        page: 1,
        size: 20
      });
    vi.mocked(libraryApi.deleteLibraryKol).mockReset().mockResolvedValue(null);
    vi.spyOn(ElMessage, "success").mockImplementation(() => ({}) as never);
    vi.spyOn(ElMessage, "warning").mockImplementation(() => ({}) as never);
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it("shows exactly the columns of requirement 1.2.5 ②", async () => {
    const { wrapper } = await mountPage();
    expect(headers(wrapper)).toEqual([
      "达人",
      "所属平台",
      "国家站点",
      "内容类目",
      "建联状态",
      "跟进负责人",
      "更新时间",
      "操作"
    ]);
    const text = wrapper.text();
    expect(text).toContain("Beauty by Amy");
    expect(text).toContain("@beautybyamy");
    expect(text).toContain("美妆个护");
    expect(text).toContain("已回复");
    expect(text).toContain("Amy BD");
    wrapper.unmount();
  });

  it("loads with the chosen filters and the chip of each one", async () => {
    const { wrapper } = await mountPage();
    await new DOMWrapper(wrapper.find('[data-test="library-keyword"]').element).setValue(
      "@beautybyamy"
    );
    await new DOMWrapper(wrapper.find('[data-test="library-follow-up-owner"]').element).setValue(
      "Amy"
    );
    const search = wrapper
      .findAllComponents({ name: "ElButton" })
      .find(item => item.attributes("data-test") === "search");
    await search!.trigger("click");
    await flushPromises();
    expect(libraryApi.listLibraryKols).toHaveBeenLastCalledWith(
      expect.objectContaining({
        page: 1,
        keyword: "@beautybyamy",
        followUpOwner: "Amy"
      })
    );
    wrapper.unmount();
  });

  it("opens the creator detail as an in-app tab and switches to it when already open", async () => {
    const { wrapper, router } = await mountPage();
    const tabs = useTabsStore();
    const name = wrapper
      .findAllComponents({ name: "ElButton" })
      .find(item => item.attributes("data-test") === "kol-name");
    await name!.trigger("click");
    await vi.waitFor(() => expect(router.currentRoute.value.path).toBe("/kol-library/kols/11"));
    expect(tabs.tabs.map(tab => tab.path)).toContain("/kol-library/kols/11");
    const opened = tabs.tabs.find(tab => tab.path === "/kol-library/kols/11");
    // The tab carries the nickname, not a language key (requirement 1.2.5 ③).
    expect(opened?.titleKey).toBe("Beauty by Amy");
    expect(opened?.literalTitle).toBe(true);

    await router.push("/kol-library/kols");
    await name!.trigger("click");
    await settle();
    expect(tabs.tabs.filter(tab => tab.path === "/kol-library/kols/11")).toHaveLength(1);
    wrapper.unmount();
  });

  it("shows the create button and the delete action to a library super administrator", async () => {
    const { wrapper } = await mountPage();
    expect(wrapper.find('[data-test="library-create"]').exists()).toBe(true);
    expect(wrapper.find('[data-test="library-delete-11"]').exists()).toBe(true);
    wrapper.unmount();
  });

  it("hides the create button and the delete action from a view-only sub-account", async () => {
    const { wrapper } = await mountPage("KOL_LIBRARY", "STAFF");
    expect(wrapper.find('[data-test="library-create"]').exists()).toBe(false);
    expect(wrapper.find('[data-test="library-delete-11"]').exists()).toBe(false);
    wrapper.unmount();
  });

  it("confirms with the creator name before deleting, then reloads", async () => {
    const { wrapper } = await mountPage();
    const confirm = vi.spyOn(ElMessageBox, "confirm").mockResolvedValue("confirm" as never);
    await wrapper.find('[data-test="library-delete-11"]').trigger("click");
    await flushPromises();
    expect(confirm).toHaveBeenCalledWith(
      expect.stringContaining("Beauty by Amy"),
      expect.any(String),
      expect.any(Object)
    );
    expect(libraryApi.deleteLibraryKol).toHaveBeenCalledWith("11");
    expect(libraryApi.listLibraryKols).toHaveBeenCalledTimes(2);
    wrapper.unmount();
  });

  it("keeps the row when the delete confirmation is cancelled", async () => {
    const { wrapper } = await mountPage();
    vi.spyOn(ElMessageBox, "confirm").mockRejectedValue(new Error("cancel"));
    await wrapper.find('[data-test="library-delete-11"]').trigger("click");
    await flushPromises();
    expect(libraryApi.deleteLibraryKol).not.toHaveBeenCalled();
    wrapper.unmount();
  });
});
