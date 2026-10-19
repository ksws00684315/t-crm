import { mount } from "@vue/test-utils";
import ElementPlus from "element-plus";
import { createPinia, setActivePinia } from "pinia";
import { nextTick } from "vue";
import { createMemoryHistory, createRouter } from "vue-router";
import { beforeEach, describe, expect, it, vi } from "vitest";
import type { UserVO } from "@/api/types";
import { i18n } from "@/locales";
import { routes } from "@/router/routes";
import { useLocaleStore } from "@/stores/locale";
import { useMenuSearchStore } from "@/stores/menu-search";
import { SIDEBAR_STORAGE_KEY } from "@/utils/sidebar";
import Layout from "../index.vue";

const storeState = vi.hoisted(() => ({ user: null as Record<string, unknown> | null }));

vi.mock("@/stores/user", () => ({
  useUserStore: () => {
    const current = storeState.user;
    const role = typeof current?.role === "string" ? current.role : "";
    const dataScope = current?.dataScope === "OWN" ? "OWN" : "ALL";
    const permissions = Array.isArray(current?.permissions) ? current.permissions : [];
    const isSuperAdmin = role === "SUPER_ADMIN";
    return {
      user: current,
      access: {
        isSuperAdmin,
        isOwnScope: role === "STAFF" && dataScope === "OWN",
        isPlatformAdmin: role === "PLATFORM_ADMIN",
        can: (code: string) => isSuperAdmin || permissions.includes(code)
      },
      refreshProfile: vi.fn().mockResolvedValue(undefined),
      logout: vi.fn()
    };
  }
}));

describe("layout header (task 32 §2.4)", () => {
  beforeEach(() => {
    localStorage.clear();
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
    setActivePinia(createPinia());
  });

  it("drops the refresh, fullscreen, lock and theme buttons; keeps the rest", async () => {
    storeState.user = {
      id: "1",
      username: "alice",
      nickname: "Alice",
      email: null,
      role: "SUPER_ADMIN",
      postTemplate: null,
      permissions: [],
      dataScope: "ALL",
      tenantId: "1",
      tenantName: "北美公司",
      status: 1,
      lastLoginTime: null,
      createTime: "2026-10-05T00:00:00Z"
    } satisfies UserVO;
    const router = createRouter({ history: createMemoryHistory(), routes });
    await router.push("/home");
    const wrapper = mount(Layout, {
      global: { plugins: [ElementPlus, i18n, router], stubs: { RouterView: true } }
    });
    for (const removed of ["refresh-page", "fullscreen", "lock", "theme"]) {
      expect(wrapper.find(`[data-test="${removed}"]`).exists()).toBe(false);
    }
    // What the owner kept: menu search, bell, locale, company switch, user menu, tab bar.
    expect(wrapper.find('[data-test="menu-search"]').exists()).toBe(true);
    expect(wrapper.findComponent({ name: "NotificationBell" }).exists()).toBe(true);
    expect(wrapper.findComponent({ name: "LocaleSwitch" }).exists()).toBe(true);
    expect(wrapper.findComponent({ name: "TabBar" }).exists()).toBe(true);
    expect(wrapper.findComponent({ name: "LockScreen" }).exists()).toBe(false);
    // The user menu no longer offers locking or idle times.
    const userMenuText = wrapper.text();
    expect(userMenuText).not.toContain("锁定屏幕");
    expect(userMenuText).not.toContain("自动锁定");
    expect(wrapper.text()).toContain("SparkTrack 管理端");
    wrapper.unmount();
  });
});

describe("layout tenant label", () => {
  beforeEach(() => {
    localStorage.clear();
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
    setActivePinia(createPinia());
  });

  it("shows the tenant name next to a company user", async () => {
    storeState.user = {
      id: "1",
      username: "alice",
      nickname: "Alice",
      email: null,
      role: "SUPER_ADMIN",
      postTemplate: null,
      permissions: [],
      dataScope: "ALL",
      tenantId: "1",
      tenantName: "北美公司",
      status: 1,
      lastLoginTime: null,
      createTime: "2026-10-05T00:00:00Z"
    } satisfies UserVO;
    const router = createRouter({ history: createMemoryHistory(), routes });
    await router.push("/home");
    const wrapper = mount(Layout, {
      global: { plugins: [ElementPlus, i18n, router], stubs: { RouterView: true } }
    });

    expect(wrapper.text()).toContain("北美公司");
  });

  it("shows platform management for a platform administrator", () => {
    storeState.user = {
      id: "2",
      username: "platform",
      nickname: "Platform",
      email: null,
      role: "PLATFORM_ADMIN",
      postTemplate: null,
      permissions: [],
      dataScope: "ALL",
      tenantId: null,
      tenantName: null,
      status: 1,
      lastLoginTime: null,
      createTime: "2026-10-05T00:00:00Z"
    } satisfies UserVO;
    const router = createRouter({ history: createMemoryHistory(), routes });
    const wrapper = mount(Layout, {
      global: { plugins: [ElementPlus, i18n, router], stubs: { RouterView: true } }
    });

    expect(wrapper.text()).toContain("平台管理");
  });

  it("tolerates an older user response without tenantName", () => {
    storeState.user = {
      id: "3",
      username: "legacy",
      nickname: "Legacy",
      email: null,
      role: "STAFF",
      postTemplate: null,
      permissions: [],
      dataScope: "ALL",
      tenantId: null,
      status: 1,
      lastLoginTime: null,
      createTime: "2026-10-05T00:00:00Z"
    };
    const router = createRouter({ history: createMemoryHistory(), routes });
    const wrapper = mount(Layout, {
      global: { plugins: [ElementPlus, i18n, router], stubs: { RouterView: true } }
    });

    expect(wrapper.text()).not.toContain("undefined");
    expect(wrapper.text()).not.toContain("null");
  });

  it("renders the menu and header in English", async () => {
    storeState.user = {
      id: "1",
      username: "alice",
      nickname: "Alice",
      email: null,
      role: "SUPER_ADMIN",
      postTemplate: null,
      permissions: [],
      dataScope: "ALL",
      tenantId: "1",
      tenantName: "North America",
      status: 1,
      lastLoginTime: null,
      createTime: "2026-10-05T00:00:00Z"
    } satisfies UserVO;
    useLocaleStore().setLocale("en-US");
    const router = createRouter({ history: createMemoryHistory(), routes });
    await router.push("/home");
    const wrapper = mount(Layout, {
      global: { plugins: [ElementPlus, i18n, router], stubs: { RouterView: true } }
    });
    await nextTick();
    const text = wrapper.text();
    expect(text).toContain("SparkTrack Admin");
    expect(text).toContain("Home");
    expect(text).toContain("Creators");
    expect(text).toContain("Super Admin");
    expect(document.documentElement.lang).toBe("en-US");
    await wrapper.find(".layout__user").trigger("click");
    await nextTick();
    expect(document.body.textContent).toContain("Change Password");
    expect(document.body.textContent).toContain("Sign out");
  });

  it("collapses the desktop sidebar to 64px and remembers the choice", async () => {
    storeState.user = {
      id: "1",
      username: "alice",
      nickname: "Alice",
      email: null,
      role: "SUPER_ADMIN",
      postTemplate: null,
      permissions: [],
      dataScope: "ALL",
      tenantId: "1",
      tenantName: "北美公司",
      status: 1,
      lastLoginTime: null,
      createTime: "2026-10-05T00:00:00Z"
    } satisfies UserVO;
    const router = createRouter({ history: createMemoryHistory(), routes });
    await router.push("/home");
    const wrapper = mount(Layout, {
      global: { plugins: [ElementPlus, i18n, router], stubs: { RouterView: true } }
    });
    expect(wrapper.get(".layout__aside").attributes("style")).toContain(
      "var(--tcrm-sidebar-width)"
    );
    await wrapper.get("[data-test=sidebar-toggle]").trigger("click");
    expect(wrapper.get(".layout__aside").attributes("style")).toContain(
      "var(--tcrm-sidebar-collapsed)"
    );
    expect(localStorage.getItem(SIDEBAR_STORAGE_KEY)).toBe("1");
    expect(wrapper.text()).toContain("搜索菜单");
  });

  it("does not throw when the sidebar preference cannot be saved", async () => {
    storeState.user = {
      id: "1",
      username: "alice",
      nickname: "Alice",
      email: null,
      role: "SUPER_ADMIN",
      postTemplate: null,
      permissions: [],
      dataScope: "ALL",
      tenantId: "1",
      tenantName: "北美公司",
      status: 1,
      lastLoginTime: null,
      createTime: "2026-10-05T00:00:00Z"
    } satisfies UserVO;
    vi.spyOn(Storage.prototype, "setItem").mockImplementation(() => {
      throw new Error("blocked");
    });
    const router = createRouter({ history: createMemoryHistory(), routes });
    const wrapper = mount(Layout, {
      global: { plugins: [ElementPlus, i18n, router], stubs: { RouterView: true } }
    });
    await expect(
      wrapper.get("[data-test=sidebar-toggle]").trigger("click")
    ).resolves.toBeUndefined();
    expect(wrapper.get(".layout__aside").attributes("style")).toContain(
      "var(--tcrm-sidebar-collapsed)"
    );
  });

  it("uses a drawer below 992px and leaves the desktop preference untouched", async () => {
    storeState.user = {
      id: "1",
      username: "alice",
      nickname: "Alice",
      email: null,
      role: "SUPER_ADMIN",
      postTemplate: null,
      permissions: [],
      dataScope: "ALL",
      tenantId: "1",
      tenantName: "北美公司",
      status: 1,
      lastLoginTime: null,
      createTime: "2026-10-05T00:00:00Z"
    } satisfies UserVO;
    vi.stubGlobal("matchMedia", (query: string) => ({
      matches: query.includes("max-width"),
      media: query,
      addEventListener: () => undefined,
      removeEventListener: () => undefined
    }));
    const router = createRouter({ history: createMemoryHistory(), routes });
    await router.push("/home");
    const wrapper = mount(Layout, {
      global: { plugins: [ElementPlus, i18n, router], stubs: { RouterView: true } }
    });
    expect(wrapper.find(".layout__aside").exists()).toBe(false);
    await wrapper.get("[data-test=sidebar-toggle]").trigger("click");
    await nextTick();
    expect(wrapper.text()).toContain("达人管理");
    expect(localStorage.getItem(SIDEBAR_STORAGE_KEY)).toBeNull();
    expect(wrapper.find("[data-test=fullscreen]").exists()).toBe(false);
  });

  it("opens menu search from the header button", async () => {
    storeState.user = {
      id: "1",
      username: "alice",
      nickname: "Alice",
      email: null,
      role: "SUPER_ADMIN",
      postTemplate: null,
      permissions: [],
      dataScope: "ALL",
      tenantId: "1",
      tenantName: "北美公司",
      status: 1,
      lastLoginTime: null,
      createTime: "2026-10-05T00:00:00Z"
    } satisfies UserVO;
    const router = createRouter({ history: createMemoryHistory(), routes });
    const wrapper = mount(Layout, {
      global: { plugins: [ElementPlus, i18n, router], stubs: { RouterView: true } }
    });
    await wrapper.get("[data-test=menu-search]").trigger("click");
    expect(useMenuSearchStore().visible).toBe(true);
  });
});
