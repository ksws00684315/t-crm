import { DOMWrapper, flushPromises, mount } from "@vue/test-utils";
import ElementPlus from "element-plus";
import { createPinia, setActivePinia } from "pinia";
import { beforeEach, describe, expect, it } from "vitest";
import { createMemoryHistory, createRouter, type Router } from "vue-router";
import type { UserVO } from "@/api/types";
import { i18n } from "@/locales";
import { useMenuSearchStore } from "@/stores/menu-search";
import { saveSession } from "@/utils/session";
import { rememberRecentMenu } from "@/utils/menu-search";
import MenuSearch from "../MenuSearch.vue";

function signIn(role: UserVO["role"], id = "1", permissions: string[] = []): void {
  const user: UserVO = {
    id,
    username: "me",
    nickname: "Me",
    email: null,
    role,
    postTemplate: role === "STAFF" ? "BD" : null,
    permissions,
    dataScope: "ALL",
    tenantId: "1",
    tenantName: "默认公司",
    status: 1,
    lastLoginTime: null,
    createTime: "2026-10-01T00:00:00Z"
  };
  saveSession({ token: "token", expiresAt: Date.now() + 60_000, user });
}

function press(key: string, modifiers: { ctrlKey?: boolean; metaKey?: boolean } = {}): void {
  window.dispatchEvent(
    new KeyboardEvent("keydown", { key, bubbles: true, cancelable: true, ...modifiers })
  );
}

async function typeQuery(value: string): Promise<void> {
  const host = document.body.querySelector("[data-test=menu-search-input]");
  const field = host instanceof HTMLInputElement ? host : host?.querySelector("input");
  if (!(field instanceof HTMLInputElement)) throw new Error("menu search input missing");
  await new DOMWrapper(field).setValue(value);
}

describe("menu search dialog", () => {
  beforeEach(() => {
    localStorage.clear();
    setActivePinia(createPinia());
  });

  async function mountDialog() {
    const router: Router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: "/home", component: { template: "<div />" } },
        { path: "/kol/kols", component: { template: "<div />" } },
        { path: "/product/shops", component: { template: "<div />" } },
        { path: "/product/products", component: { template: "<div />" } },
        { path: "/product/kol-items", component: { template: "<div />" } },
        { path: "/system/users", component: { template: "<div />" } },
        { path: "/system/oper-logs", component: { template: "<div />" } },
        { path: "/platform/tenants", component: { template: "<div />" } }
      ]
    });
    await router.push("/home");
    await router.isReady();
    const wrapper = mount(MenuSearch, {
      global: { plugins: [ElementPlus, i18n, router] },
      attachTo: document.body
    });
    return { wrapper, router };
  }

  it("opens from Ctrl+K and from Command+K", async () => {
    signIn("SUPER_ADMIN");
    setActivePinia(createPinia());
    const { wrapper } = await mountDialog();
    press("k", { ctrlKey: true });
    expect(useMenuSearchStore().visible).toBe(true);
    useMenuSearchStore().close();
    press("k", { metaKey: true });
    expect(useMenuSearchStore().visible).toBe(true);
    wrapper.unmount();
  });

  it("lists only menus the signed-in user may open", async () => {
    signIn("STAFF", "1", ["kol:view"]);
    setActivePinia(createPinia());
    const { wrapper } = await mountDialog();
    useMenuSearchStore().open();
    await flushPromises();
    await typeQuery("用户");
    expect(document.body.textContent).toContain("没有匹配的菜单");
    expect(document.body.textContent).not.toContain("用户管理");

    wrapper.unmount();
    localStorage.clear();
    signIn("SUPER_ADMIN");
    setActivePinia(createPinia());
    const again = await mountDialog();
    useMenuSearchStore().open();
    await flushPromises();
    await typeQuery("用户");
    expect(document.body.textContent).toContain("用户管理");
    again.wrapper.unmount();
  });

  it("shows at most the recent menus for this user, then opens the selected one", async () => {
    signIn("SUPER_ADMIN", "user-a");
    rememberRecentMenu("user-b", "/system/users");
    rememberRecentMenu("user-a", "/kol/kols");
    rememberRecentMenu("user-a", "/product/shops");
    setActivePinia(createPinia());
    const { wrapper, router } = await mountDialog();
    useMenuSearchStore().open();
    await flushPromises();
    const items = document.body.querySelectorAll("[data-test=menu-search-item]");
    expect(items).toHaveLength(2);
    expect(items[0].textContent).toContain("店铺管理");
    expect(document.body.textContent).not.toContain("用户管理");

    press("ArrowDown");
    press("Enter");
    await flushPromises();
    expect(router.currentRoute.value.path).toBe("/kol/kols");
    expect(useMenuSearchStore().visible).toBe(false);

    useMenuSearchStore().open();
    await flushPromises();
    press("Escape");
    expect(useMenuSearchStore().visible).toBe(false);
    wrapper.unmount();
  });
});
