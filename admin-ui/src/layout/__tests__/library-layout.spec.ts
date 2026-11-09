import { flushPromises, mount } from "@vue/test-utils";
import ElementPlus from "element-plus";
import { createPinia, setActivePinia } from "pinia";
import { createMemoryHistory, createRouter } from "vue-router";
import { beforeEach, describe, expect, it, vi } from "vitest";
import * as authApi from "@/api/auth";
import * as libraryApi from "@/api/kol-library/kol";
import * as notificationApi from "@/api/notification/notification";
import type { LibraryKolDetailVO } from "@/api/kol-library/kol";
import type { CompanyEdition, UserVO } from "@/api/types";
import { i18n } from "@/locales";
import { installGuard } from "@/router/guard";
import { routes } from "@/router/routes";
import { useTabsStore } from "@/stores/tabs";
import { accessOf } from "@/utils/permission";
import { useUserStore } from "@/stores/user";
import { saveSession } from "@/utils/session";
import Layout from "../index.vue";

vi.mock("@/api/auth");
vi.mock("@/api/notification/notification");
vi.mock("@/api/kol-library/kol");

/**
 * The layout of a creator-library company (ADR-038 §3.3, requirement 1.2.5 ③): the creator list is
 * its home and affix tab, there is no notification bell, and several creator detail tabs stay open
 * side by side with their own content.
 */

function detail(id: string, name: string): LibraryKolDetailVO {
  return {
    id,
    platform: "TIKTOK",
    kolUsername: `user${id}`,
    displayName: name,
    followers: null,
    countryCode: "US",
    profileUrl: null,
    categories: ["OTHER"],
    avgViews: null,
    engagementRate: null,
    audienceCountry: null,
    audienceAgeRange: null,
    audienceGender: null,
    contact: null,
    contentQuality: null,
    salesPotential: null,
    cooperationAttitude: null,
    grade: null,
    cooperationStage: null,
    tags: [],
    remark: null,
    outreachStatus: "NOT_CONTACTED",
    followUpOwner: "Amy BD",
    lastContactDate: null,
    sampleStatus: "NOT_SENT",
    trackingNo: null,
    sampleDate: null,
    createTime: "2026-10-01T00:00:00Z",
    updateTime: "2026-11-01T00:00:00Z",
    totals: { contentCount: 0, clicks: 0, orders: 0, gmv: "0.00", totalCost: "0.00", roi: null }
  };
}

function user(edition: CompanyEdition): UserVO {
  return {
    id: "7",
    username: "owner",
    nickname: "Owner",
    email: null,
    role: "SUPER_ADMIN",
    postTemplate: null,
    permissions: [],
    dataScope: "ALL",
    tenantId: "9",
    tenantName: "B 公司",
    edition,
    status: 1,
    lastLoginTime: null,
    createTime: "2026-10-05T00:00:00Z",
    companies: [{ tenantId: "9", tenantName: "B 公司", role: "SUPER_ADMIN", edition }]
  };
}

/**
 * Creates the store before any navigation: the home redirect is decided by the guard, which reads
 * the edition from the store.
 */
function signIn(signedIn: UserVO): void {
  saveSession({ token: "tok", expiresAt: Date.now() + 60_000, user: signedIn });
  setActivePinia(createPinia());
  useUserStore();
}

async function mountAs(signedIn: UserVO, path = "/kol-library/kols") {
  signIn(signedIn);
  void useTabsStore();
  vi.mocked(authApi.fetchCurrentUser).mockResolvedValue(signedIn);
  const router = createRouter({ history: createMemoryHistory(), routes });
  // Same home redirect as the app shell: "/" goes to the company's own home.
  router.addRoute({
    path: "/",
    redirect: () => (useUserStore().user?.edition === "KOL_LIBRARY" ? "/kol-library/kols" : "/home")
  });
  // The in-app guard reads the store lazily, so it always sees the signed-in account.
  installGuard(router, () => {
    const signed = useUserStore();
    return { isLoggedIn: signed.isLoggedIn, ...signed.access };
  });
  await router.push(path);
  const wrapper = mount(Layout, {
    global: { plugins: [ElementPlus, i18n, router] }
  });
  await flushPromises();
  return { wrapper, router, store: useUserStore(), tabs: useTabsStore() };
}

describe("creator library layout", () => {
  beforeEach(() => {
    localStorage.clear();
    sessionStorage.clear();
    vi.restoreAllMocks();
    vi.mocked(authApi.fetchCurrentUser).mockReset();
    vi.mocked(notificationApi.unreadNotificationCount).mockReset().mockResolvedValue(0);
    vi.mocked(notificationApi.listNotifications).mockReset().mockResolvedValue({
      items: [],
      total: "0",
      page: 1,
      size: 20
    });
    vi.mocked(libraryApi.getLibraryKol)
      .mockReset()
      .mockImplementation(async (id: string) =>
        id === "11" ? detail("11", "Beauty by Amy") : detail(id, `Creator ${id}`)
      );
    vi.mocked(libraryApi.listLibraryContents).mockReset().mockResolvedValue([]);
    vi.mocked(libraryApi.listLibraryKols).mockReset().mockResolvedValue({
      items: [],
      total: "0",
      page: 1,
      size: 20
    });
  });

  it("pins the creator list as the home tab and shows the library menu only", async () => {
    const { wrapper, tabs } = await mountAs(user("KOL_LIBRARY"));
    // The workbench tab always comes first, then the library home.
    expect(tabs.tabs[0]).toMatchObject({ path: "/kol-library/kols", affix: true });
    expect(tabs.tabs.some(tab => tab.path === "/home")).toBe(false);
    const menu = wrapper.text();
    expect(menu).toContain("达人列表");
    expect(menu).toContain("账号管理");
    expect(menu).not.toContain("经营分析");
    expect(menu).not.toContain("订单管理");
    expect(menu).not.toContain("财务结算");
    // No notification bell in the library edition (ADR-038 §3.2).
    expect(wrapper.find('[data-test="notification-bell"]').exists()).toBe(false);
    wrapper.unmount();
  });

  it("keeps the workbench and the bell in a distribution company", async () => {
    const { wrapper, tabs } = await mountAs(user("DISTRIBUTION"), "/home");
    expect(tabs.tabs[0]).toMatchObject({ path: "/home", affix: true });
    expect(wrapper.find('[data-test="notification-bell"]').exists()).toBe(true);
    // The A company keeps its own creator menu; the library group is not there.
    expect(wrapper.text()).toContain("达人列表");
    expect(wrapper.text()).not.toContain("达人库");
    wrapper.unmount();
  });

  it("keeps several creator detail tabs open, each with its own content", async () => {
    const { wrapper, router, tabs } = await mountAs(user("KOL_LIBRARY"));
    await router.push("/kol-library/kols/11");
    await flushPromises();
    await router.push("/kol-library/kols/22");
    await flushPromises();

    const details = tabs.tabs.filter(tab => tab.path.startsWith("/kol-library/kols/"));
    expect(details.map(tab => tab.path)).toEqual(["/kol-library/kols/11", "/kol-library/kols/22"]);
    // Each tab title is its creator's nickname.
    expect(details.map(tab => tab.titleKey)).toEqual(["Beauty by Amy", "Creator 22"]);
    expect(details.every(tab => tab.literalTitle)).toBe(true);

    // Both pages stay cached; switching back shows the first creator, not the second.
    await router.push("/kol-library/kols/11");
    await flushPromises();
    expect(wrapper.find('[data-test="detail-name"]').text()).toBe("Beauty by Amy");
    await router.push("/kol-library/kols/22");
    await flushPromises();
    expect(wrapper.find('[data-test="detail-name"]').text()).toBe("Creator 22");
    wrapper.unmount();
  });

  it("sends / to the creator list of a library company", async () => {
    // The store must exist before the first navigation: the "/" redirect reads the edition from it.
    signIn(user("KOL_LIBRARY"));
    const library = await mountAs(user("KOL_LIBRARY"), "/");
    await vi.waitFor(() =>
      expect(library.router.currentRoute.value.path).toBe("/kol-library/kols")
    );
    library.wrapper.unmount();
    // A distribution company keeps the workbench.
    signIn(user("DISTRIBUTION"));
    const distribution = await mountAs(user("DISTRIBUTION"), "/");
    expect(distribution.router.currentRoute.value.path).toBe("/home");
    distribution.wrapper.unmount();
  });

  it("decides the /home of a library company in the guard", async () => {
    // The guard sees "/home" with the edition of the account and sends a library company to the
    // creator list; a distribution company is let through (ADR-038 §3.3, requirement 1.2.5).
    const { resolveAccess } = await import("@/router/guard");
    const meta = createRouter({ history: createMemoryHistory(), routes }).resolve("/home").meta;
    const at = (path: string) => ({ path, fullPath: path, meta });
    const libraryAuth = { isLoggedIn: true, ...accessOf(user("KOL_LIBRARY")) };
    expect(resolveAccess(at("/home"), libraryAuth)).toBe("/kol-library/kols");
    const distributionAuth = { isLoggedIn: true, ...accessOf(user("DISTRIBUTION")) };
    expect(resolveAccess(at("/home"), distributionAuth)).toBe(true);
  });

  it("hides account management from a library sub-account", async () => {
    const staff: UserVO = {
      ...user("KOL_LIBRARY"),
      id: "8",
      username: "bd01",
      nickname: "BD",
      role: "STAFF",
      dataScope: "ALL",
      permissions: ["kol-library:view", "kol-library:edit"]
    };
    const { wrapper } = await mountAs(staff);
    expect(wrapper.text()).toContain("达人列表");
    expect(wrapper.text()).not.toContain("账号管理");
    wrapper.unmount();
  });

  it("closes the previous company's tabs and pins the new home on a company switch", async () => {
    const bothCompanies: UserVO = {
      ...user("KOL_LIBRARY"),
      companies: [
        { tenantId: "9", tenantName: "B 公司", role: "SUPER_ADMIN", edition: "KOL_LIBRARY" },
        { tenantId: "3", tenantName: "A 公司", role: "SUPER_ADMIN", edition: "DISTRIBUTION" }
      ]
    };
    const { wrapper, tabs } = await mountAs(bothCompanies);
    expect(tabs.tabs[0]).toMatchObject({ path: "/kol-library/kols", affix: true });
    tabs.open({
      path: "/kol-library/kols/11",
      titleKey: "Beauty by Amy",
      literalTitle: true,
      affix: false,
      componentName: "LibraryKolDetailPage"
    });
    expect(tabs.tabs.some(tab => tab.path === "/kol-library/kols/11")).toBe(true);
    // The switch answer puts the same person into the distribution company.
    vi.mocked(authApi.switchCompany).mockResolvedValue({
      ...bothCompanies,
      tenantId: "3",
      tenantName: "A 公司",
      edition: "DISTRIBUTION",
      permissions: []
    });
    const dropdown = wrapper
      .findAllComponents({ name: "ElDropdown" })
      .find(item => item.attributes("data-test") === "company-switch");
    dropdown!.vm.$emit("command", "3");
    await vi.waitFor(() => expect(authApi.switchCompany).toHaveBeenCalledWith("3"));
    // Every tab of the previous company is closed and only the new edition's home stays pinned.
    await vi.waitFor(() => expect(tabs.tabs.map(tab => tab.path)).toEqual(["/home"]));
    expect(tabs.tabs[0]).toMatchObject({ affix: true, titleKey: "route.home" });
    wrapper.unmount();
  });

  it("restores the library home instead of the workbench after a reload", async () => {
    const { wrapper, tabs } = await mountAs(user("KOL_LIBRARY"));
    tabs.open({
      path: "/kol-library/kols/11",
      titleKey: "Beauty by Amy",
      literalTitle: true,
      affix: false,
      componentName: "LibraryKolDetailPage"
    });
    wrapper.unmount();
    // A fresh mount reads the stored tabs of the same account.
    const again = await mountAs(user("KOL_LIBRARY"));
    expect(again.tabs.tabs[0]).toMatchObject({ path: "/kol-library/kols", affix: true });
    again.wrapper.unmount();
  });
});
