import { flushPromises, mount } from "@vue/test-utils";
import ElementPlus from "element-plus";
import { createPinia, setActivePinia } from "pinia";
import { nextTick } from "vue";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { createMemoryHistory, createRouter } from "vue-router";
import type { UserVO } from "@/api/types";
import { i18n } from "@/locales";
import { routes } from "@/router/routes";
import { useLockStore } from "@/stores/lock";
import { useMenuSearchStore } from "@/stores/menu-search";
import Layout from "../index.vue";
import MenuSearch from "../MenuSearch.vue";

const storeState = vi.hoisted(() => ({ user: null as UserVO | null }));

vi.mock("@/stores/user", () => ({
  useUserStore: () => ({
    user: storeState.user,
    roles: storeState.user ? [storeState.user.role] : [],
    refreshProfile: vi.fn().mockResolvedValue(undefined),
    logout: vi.fn(),
    login: vi.fn(),
    loginReplacingToken: vi.fn()
  })
}));

vi.mock("@/api/auth", () => ({
  login: vi.fn(),
  createCaptcha: vi.fn().mockResolvedValue({
    enabled: true,
    captchaId: "c1",
    image: "data:image/png;base64,AA==",
    expiresIn: 180
  }),
  logout: vi.fn(),
  fetchCurrentUser: vi.fn()
}));

const me: UserVO = {
  id: "1",
  username: "root",
  nickname: "Root",
  email: null,
  role: "SUPER_ADMIN",
  dataScope: "ALL",
  tenantId: "1",
  tenantName: "Default",
  status: 1,
  lastLoginTime: null,
  createTime: "2026-10-05T00:00:00Z"
};

function pressK(ctrl = true): void {
  window.dispatchEvent(
    new KeyboardEvent("keydown", {
      key: "k",
      bubbles: true,
      cancelable: true,
      ctrlKey: ctrl,
      metaKey: !ctrl
    })
  );
}

describe("lock keyboard isolation", () => {
  beforeEach(() => {
    localStorage.clear();
    document.body.innerHTML = "";
    storeState.user = me;
    setActivePinia(createPinia());
  });

  it("sets inert on the layout root while locked so covered controls cannot receive focus", async () => {
    const router = createRouter({ history: createMemoryHistory(), routes });
    await router.push("/home");
    await router.isReady();
    const wrapper = mount(Layout, {
      global: { plugins: [ElementPlus, i18n, router], stubs: { RouterView: true } },
      attachTo: document.body
    });
    await flushPromises();
    const root = wrapper.get("[data-test=layout-root]");
    expect(root.attributes("inert")).toBeUndefined();

    useLockStore().lock();
    await nextTick();
    expect(root.attributes("inert")).toBeDefined();

    const searchBtn = wrapper.get("[data-test=menu-search]").element as HTMLButtonElement;
    expect(searchBtn.closest("[inert], [data-test=layout-root][inert]")).not.toBeNull();
    // Try focusing a control under the overlay; inert ancestors keep it out of the tab order.
    searchBtn.focus();
    expect(searchBtn.closest("[inert]") != null || document.activeElement !== searchBtn).toBe(true);

    useLockStore().unlock();
    await nextTick();
    expect(root.attributes("inert")).toBeUndefined();
    wrapper.unmount();
  });

  it("does not open menu search on Ctrl/⌘+K while locked", async () => {
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [
        {
          path: "/home",
          component: { template: "<div />" },
          meta: { titleKey: "route.home", roles: ["SUPER_ADMIN"] }
        }
      ]
    });
    await router.push("/home");
    await router.isReady();
    const wrapper = mount(MenuSearch, {
      global: { plugins: [ElementPlus, i18n, router] },
      attachTo: document.body
    });
    await flushPromises();

    pressK(true);
    expect(useMenuSearchStore().visible).toBe(true);
    useMenuSearchStore().close();
    await nextTick();

    useLockStore().lock();
    await nextTick();
    expect(useMenuSearchStore().visible).toBe(false);
    pressK(true);
    pressK(false);
    expect(useMenuSearchStore().visible).toBe(false);
    wrapper.unmount();
  });
});
