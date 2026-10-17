import { flushPromises, mount } from "@vue/test-utils";
import ElementPlus, { ElMessage } from "element-plus";
import { createPinia, setActivePinia } from "pinia";
import { createMemoryHistory, createRouter } from "vue-router";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import * as authApi from "@/api/auth";
import * as notificationApi from "@/api/notification/notification";
import type { UserVO } from "@/api/types";
import { i18n } from "@/locales";
import { routes } from "@/router/routes";
import { useTabsStore } from "@/stores/tabs";
import { useUserStore } from "@/stores/user";
import { ApiError } from "@/utils/http/error";
import { saveSession } from "@/utils/session";
import { tablePrefsKey } from "@/utils/table-prefs";
import { THEME_STORAGE_KEY } from "@/utils/theme";
import Layout from "../index.vue";

vi.mock("@/api/auth");
vi.mock("@/api/notification/notification");

const messages = {} as Record<"success" | "error", ReturnType<typeof vi.spyOn>>;

class FakeChannel {
  static instances: FakeChannel[] = [];
  onmessage: ((event: MessageEvent) => void) | null = null;
  posted: unknown[] = [];
  closed = false;
  constructor(readonly name: string) {
    FakeChannel.instances.push(this);
  }
  postMessage(message: unknown) {
    this.posted.push(message);
  }
  close() {
    this.closed = true;
  }
}

const base: UserVO = {
  id: "7",
  username: "alice",
  nickname: "Alice",
  email: null,
  role: "SUPER_ADMIN",
  postTemplate: null,
  permissions: [],
  dataScope: "ALL",
  tenantId: "1",
  tenantName: "A 公司",
  status: 1,
  lastLoginTime: null,
  createTime: "2026-10-05T00:00:00Z"
};
const companies = [
  { tenantId: "1", tenantName: "A 公司", role: "SUPER_ADMIN" as const },
  { tenantId: "2", tenantName: "B 公司", role: "STAFF" as const }
];
const inA: UserVO = { ...base, companies };
const inB: UserVO = {
  ...base,
  role: "STAFF",
  postTemplate: "BD",
  permissions: ["kol:view"],
  dataScope: "OWN",
  tenantId: "2",
  tenantName: "B 公司",
  companies
};

async function mountAs(user: UserVO, path = "/system/users") {
  saveSession({ token: "tok", expiresAt: Date.now() + 60_000, user });
  setActivePinia(createPinia());
  vi.mocked(authApi.fetchCurrentUser).mockResolvedValue(user);
  const router = createRouter({ history: createMemoryHistory(), routes });
  await router.push(path);
  const wrapper = mount(Layout, {
    global: { plugins: [ElementPlus, i18n, router], stubs: { RouterView: true } }
  });
  await flushPromises();
  return { wrapper, router, store: useUserStore(), tabs: useTabsStore() };
}

function companyDropdown(wrapper: Awaited<ReturnType<typeof mountAs>>["wrapper"]) {
  return wrapper
    .findAllComponents({ name: "ElDropdown" })
    .find(item => item.attributes("data-test") === "company-switch");
}

describe("header company switch", () => {
  beforeEach(() => {
    localStorage.clear();
    sessionStorage.clear();
    FakeChannel.instances = [];
    vi.stubGlobal("BroadcastChannel", FakeChannel);
    vi.restoreAllMocks();
    messages.success = vi.spyOn(ElMessage, "success").mockImplementation(() => ({}) as never);
    messages.error = vi.spyOn(ElMessage, "error").mockImplementation(() => ({}) as never);
    vi.mocked(authApi.switchCompany).mockReset();
    vi.mocked(authApi.fetchCurrentUser).mockReset();
    vi.mocked(notificationApi.unreadNotificationCount).mockReset().mockResolvedValue(0);
    vi.mocked(notificationApi.listNotifications).mockReset();
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("shows plain text for a single company", async () => {
    const { wrapper } = await mountAs({ ...base, companies: companies.slice(0, 1) });
    expect(companyDropdown(wrapper)).toBeUndefined();
    expect(wrapper.find('[data-test="tenant-label"]').text()).toBe("A 公司");
  });

  it("shows nothing to switch for the platform administrator", async () => {
    const { wrapper } = await mountAs(
      { ...base, role: "PLATFORM_ADMIN", tenantId: null, tenantName: null, companies: [] },
      "/platform/tenants"
    );
    expect(companyDropdown(wrapper)).toBeUndefined();
    expect(wrapper.text()).toContain("平台管理");
  });

  it("lists every company with the role and marks the current one", async () => {
    const { wrapper } = await mountAs(inA);
    const dropdown = companyDropdown(wrapper);
    expect(dropdown).toBeDefined();
    expect(wrapper.find('[data-test="tenant-label"]').exists()).toBe(false);
    expect(dropdown!.text()).toContain("A 公司");
    const items = dropdown!.findAllComponents({ name: "ElDropdownItem" });
    expect(items.map(item => item.text())).toEqual(["A 公司超级管理员", "B 公司子账号"]);
    expect(items[0].find(".is-current").exists()).toBe(true);
    expect(items[1].find(".is-current").exists()).toBe(false);
  });

  it("switches: new menu, only the home tab, home page, message and a broadcast", async () => {
    localStorage.setItem(THEME_STORAGE_KEY, "dark");
    localStorage.setItem(tablePrefsKey("kols"), JSON.stringify({ size: 50 }));
    const { wrapper, router, store, tabs } = await mountAs(inA);
    expect(tabs.tabs.map(tab => tab.path)).toEqual(["/home", "/system/users"]);
    expect(wrapper.text()).toContain("用户管理");
    vi.mocked(authApi.switchCompany).mockResolvedValue(inB);

    companyDropdown(wrapper)!.vm.$emit("command", "2");
    await flushPromises();

    expect(authApi.switchCompany).toHaveBeenCalledWith("2");
    expect(store.user?.tenantId).toBe("2");
    await vi.waitFor(() => expect(router.currentRoute.value.path).toBe("/home"));
    await flushPromises();
    expect(tabs.tabs.map(tab => tab.path)).toEqual(["/home"]);
    expect(wrapper.text()).not.toContain("用户管理");
    expect(wrapper.text()).toContain("达人管理");
    expect(messages.success).toHaveBeenCalledWith("已切换到 B 公司");
    expect(FakeChannel.instances[0].name).toBe("tcrm-admin-company");
    expect(FakeChannel.instances[0].posted).toEqual([{ type: "switched", tenantId: "2" }]);
    // Personal preferences survive the switch.
    expect(localStorage.getItem(THEME_STORAGE_KEY)).toBe("dark");
    expect(localStorage.getItem(tablePrefsKey("kols"))).toBe(JSON.stringify({ size: 50 }));
  });

  it("drops the KeepAlive cache by changing the page area key", async () => {
    const { wrapper } = await mountAs(inA);
    vi.mocked(authApi.switchCompany).mockResolvedValue(inB);
    const before = (wrapper.vm as unknown as { company: { cacheKey: { value: string } } }).company
      .cacheKey.value;
    companyDropdown(wrapper)!.vm.$emit("command", "2");
    await vi.waitFor(() =>
      expect(
        (wrapper.vm as unknown as { company: { cacheKey: { value: string } } }).company.cacheKey
          .value
      ).toBe("2")
    );
    const after = (wrapper.vm as unknown as { company: { cacheKey: { value: string } } }).company
      .cacheKey.value;
    expect(before).toBe("1");
    expect(after).toBe("2");
  });

  it("ignores the company already shown", async () => {
    const { wrapper } = await mountAs(inA);
    companyDropdown(wrapper)!.vm.$emit("command", "1");
    await flushPromises();
    expect(authApi.switchCompany).not.toHaveBeenCalled();
  });

  it("refreshes from /auth/me when another tab switched", async () => {
    const { router, store, tabs } = await mountAs(inA);
    vi.mocked(authApi.fetchCurrentUser).mockResolvedValue(inB);

    FakeChannel.instances[0].onmessage?.(
      new MessageEvent("message", { data: { type: "switched", tenantId: "2" } })
    );
    await flushPromises();

    expect(authApi.fetchCurrentUser).toHaveBeenCalled();
    expect(store.user?.tenantId).toBe("2");
    await vi.waitFor(() => expect(router.currentRoute.value.path).toBe("/home"));
    expect(tabs.tabs.map(tab => tab.path)).toEqual(["/home"]);
    expect(FakeChannel.instances[0].posted).toEqual([]);
  });

  it("explains AUTH_COMPANY_NOT_MEMBER and re-reads the company list", async () => {
    const { wrapper, store, router } = await mountAs(inA);
    vi.mocked(authApi.switchCompany).mockRejectedValue(
      new ApiError({ status: 403, errorCode: "AUTH_COMPANY_NOT_MEMBER", traceId: "t" })
    );
    const remaining = { ...inA, companies: companies.slice(0, 1) };
    vi.mocked(authApi.fetchCurrentUser).mockResolvedValue(remaining);
    vi.mocked(authApi.fetchCurrentUser).mockClear();

    companyDropdown(wrapper)!.vm.$emit("command", "2");
    await flushPromises();

    expect(messages.error).toHaveBeenCalledWith("你已不是该公司的成员，或该公司已停用");
    expect(authApi.fetchCurrentUser).toHaveBeenCalledTimes(1);
    expect(store.user?.tenantId).toBe("1");
    expect(router.currentRoute.value.path).toBe("/system/users");
    expect(companyDropdown(wrapper)).toBeUndefined();
  });

  it("reloads the notification unread count right after the company changes", async () => {
    const { wrapper } = await mountAs(inA);
    expect(notificationApi.unreadNotificationCount).toHaveBeenCalledTimes(1);
    vi.mocked(authApi.switchCompany).mockResolvedValue(inB);
    vi.mocked(notificationApi.unreadNotificationCount).mockResolvedValue(4);

    companyDropdown(wrapper)!.vm.$emit("command", "2");
    await flushPromises();

    expect(notificationApi.unreadNotificationCount).toHaveBeenCalledTimes(2);
    expect(wrapper.get('[data-test="notification-badge"]').text()).toContain("4");
  });

  it("closes the channel when the layout goes away", async () => {
    const { wrapper } = await mountAs(inA);
    wrapper.unmount();
    expect(FakeChannel.instances[0].closed).toBe(true);
  });
});
