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
import Layout from "../index.vue";

const storeState = vi.hoisted(() => ({ user: null as Record<string, unknown> | null }));

vi.mock("@/stores/user", () => ({
  useUserStore: () => ({
    user: storeState.user,
    roles: storeState.user ? [storeState.user.role] : [],
    refreshProfile: vi.fn().mockResolvedValue(undefined),
    logout: vi.fn()
  })
}));

describe("layout tenant label", () => {
  beforeEach(() => {
    localStorage.clear();
    setActivePinia(createPinia());
  });

  it("shows the tenant name next to a company user", async () => {
    storeState.user = {
      id: "1",
      username: "alice",
      nickname: "Alice",
      email: null,
      role: "SUPER_ADMIN",
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
      role: "OPERATOR",
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
    expect(text).toContain("T-CRM Admin");
    expect(text).toContain("Home");
    expect(text).toContain("Creators");
    expect(text).toContain("Super Admin");
    expect(document.documentElement.lang).toBe("en-US");
    await wrapper.find(".layout__user").trigger("click");
    await nextTick();
    expect(document.body.textContent).toContain("Change Password");
    expect(document.body.textContent).toContain("Sign out");
  });
});
