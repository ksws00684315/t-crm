import { DOMWrapper, flushPromises, mount, type VueWrapper } from "@vue/test-utils";
import ElementPlus, { ElMessage } from "element-plus";
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
 * Creator list of the library (requirement 1.2.5 ② as revised on 2026-11-10/11): the eight
 * columns, the six filters, a plain-text nickname, and the Edit / View row actions. Delete moved
 * into the edit form.
 */

const row: LibraryKolVO = {
  id: "11",
  platform: "TIKTOK",
  kolUsername: "beautybyamy",
  displayName: "Beauty by Amy",
  followers: "1234567",
  countryCode: "US",
  categories: ["BEAUTY_PERSONAL_CARE", "HOME_DAILY"],
  grade: "A",
  outreachStatus: "REPLIED",
  followUpOwner: "Amy BD"
};

let staffPermissions = ["kol-library:view"];

function signIn(edition: CompanyEdition, role: Role = "SUPER_ADMIN") {
  const user = {
    id: "1",
    username: "me",
    nickname: "Me",
    email: null,
    role,
    postTemplate: null,
    permissions: role === "STAFF" ? staffPermissions : [],
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
    staffPermissions = ["kol-library:view"];
    vi.spyOn(ElMessage, "success").mockImplementation(() => ({}) as never);
    vi.spyOn(ElMessage, "warning").mockImplementation(() => ({}) as never);
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it("shows the eight columns of requirement 1.2.5 ② in order", async () => {
    const { wrapper } = await mountPage();
    expect(headers(wrapper)).toEqual([
      "达人名/ID",
      "平台",
      "粉丝数",
      "国家站点",
      "内容类目",
      "建联状态",
      "负责人",
      "达人分级",
      "操作"
    ]);
    const text = wrapper.text();
    expect(text).toContain("Beauty by Amy");
    expect(text).toContain("@beautybyamy");
    expect(text).toContain("1,234,567");
    expect(text).toContain("美妆个护");
    expect(text).toContain("已回复");
    expect(text).toContain("Amy BD");
    expect(text).not.toContain("更新时间");
    wrapper.unmount();
  });

  it("shows a dash for a creator without a tier", async () => {
    vi.mocked(libraryApi.listLibraryKols).mockResolvedValue({
      items: [{ ...row, grade: null }],
      total: "1",
      page: 1,
      size: 20
    });
    const { wrapper } = await mountPage();
    const cells = wrapper.findAll(".el-table__body tr:first-child td").map(cell => cell.text());
    expect(cells[7]).toBe("—");
    wrapper.unmount();
  });

  it("keeps the six filters with their labels", async () => {
    const { wrapper } = await mountPage();
    for (const testId of [
      "library-keyword",
      "library-platform",
      "library-country",
      "library-category",
      "library-outreach-status",
      "library-follow-up-owner"
    ]) {
      expect(wrapper.find(`[data-test="${testId}"]`).exists()).toBe(true);
    }
    const labels = wrapper.findAll(".el-form-item__label").map(label => label.text());
    expect(labels).toEqual(
      expect.arrayContaining(["所属平台", "国家站点", "内容类目", "建联状态", "跟进负责人"])
    );
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

  it("shows the nickname as plain text that opens nothing", async () => {
    const { wrapper, router } = await mountPage();
    const name = wrapper.find('[data-test="kol-name"]');
    expect(name.element.tagName).toBe("SPAN");
    expect(name.text()).toBe("Beauty by Amy");
    await name.trigger("click");
    await settle();
    expect(router.currentRoute.value.path).toBe("/kol-library/kols");
    wrapper.unmount();
  });

  it("lists Edit then View, and View opens the detail tab once", async () => {
    const { wrapper, router } = await mountPage();
    const tabs = useTabsStore();
    const actions = wrapper
      .find(".el-table__body tr:first-child")
      .findAll('[data-test^="library-"]')
      .map(button => button.text());
    expect(actions).toEqual(["编辑", "查看"]);
    expect(wrapper.find('[data-test="library-delete-11"]').exists()).toBe(false);

    await wrapper.find('[data-test="library-view-11"]').trigger("click");
    await vi.waitFor(() => expect(router.currentRoute.value.path).toBe("/kol-library/kols/11"));
    const opened = tabs.tabs.find(tab => tab.path === "/kol-library/kols/11");
    // The tab carries the nickname, not a language key (requirement 1.2.5 ③).
    expect(opened?.titleKey).toBe("Beauty by Amy");
    expect(opened?.literalTitle).toBe(true);

    await router.push("/kol-library/kols");
    await wrapper.find('[data-test="library-view-11"]').trigger("click");
    await settle();
    expect(tabs.tabs.filter(tab => tab.path === "/kol-library/kols/11")).toHaveLength(1);
    wrapper.unmount();
  });

  it("opens the same form as Add, for this creator, from Edit", async () => {
    const { wrapper, router } = await mountPage();
    const tabs = useTabsStore();
    await wrapper.find('[data-test="library-edit-11"]').trigger("click");
    await vi.waitFor(() =>
      expect(router.currentRoute.value.path).toBe("/kol-library/kols/11/edit")
    );
    const opened = tabs.tabs.find(tab => tab.path === "/kol-library/kols/11/edit");
    expect(opened?.componentName).toBe("LibraryKolFormPage");
    expect(opened?.titleKey).toContain("Beauty by Amy");
    expect(router.currentRoute.value.matched.at(-1)?.path).toBe("/kol-library/kols/:id/edit");
    wrapper.unmount();
  });

  it("shows the create button and Edit to a library super administrator", async () => {
    const { wrapper } = await mountPage();
    expect(wrapper.find('[data-test="library-create"]').exists()).toBe(true);
    expect(wrapper.find('[data-test="library-edit-11"]').exists()).toBe(true);
    wrapper.unmount();
  });

  it("shows Edit to a sub-account with the edit permission", async () => {
    staffPermissions = ["kol-library:view", "kol-library:edit"];
    const { wrapper } = await mountPage("KOL_LIBRARY", "STAFF");
    expect(wrapper.find('[data-test="library-edit-11"]').exists()).toBe(true);
    expect(wrapper.find('[data-test="library-view-11"]').exists()).toBe(true);
    wrapper.unmount();
  });

  it("shows only View to a view-only sub-account", async () => {
    const { wrapper } = await mountPage("KOL_LIBRARY", "STAFF");
    expect(wrapper.find('[data-test="library-create"]').exists()).toBe(false);
    expect(wrapper.find('[data-test="library-edit-11"]').exists()).toBe(false);
    expect(wrapper.find('[data-test="library-view-11"]').exists()).toBe(true);
    wrapper.unmount();
  });
});
