import { mount, flushPromises } from "@vue/test-utils";
import ElementPlus, { ElMessage } from "element-plus";
import { createPinia, setActivePinia } from "pinia";
import { nextTick } from "vue";
import { createMemoryHistory, createRouter } from "vue-router";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { i18n } from "@/locales";
import NotificationBell from "../NotificationBell.vue";

const api = vi.hoisted(() => ({
  unreadNotificationCount: vi.fn(),
  listNotifications: vi.fn(),
  markNotificationRead: vi.fn(),
  markAllNotificationsRead: vi.fn()
}));

vi.mock("@/api/notification/notification", () => api);

const storeState = vi.hoisted(() => ({
  user: {
    role: "STAFF",
    dataScope: "OWN",
    permissions: ["kol:view"],
    postTemplate: "BD"
  } as { role: string; dataScope: string; permissions: string[]; postTemplate: string | null }
}));

vi.mock("@/stores/user", () => ({
  useUserStore: () => ({ user: storeState.user })
}));

const payout = {
  id: "77",
  type: "PAYOUT_ACCOUNT_SUBMITTED",
  params: {
    kolCode: "KOL0010",
    accountType: "PAYPAL",
    shopName: null,
    reason: null,
    bdName: null,
    count: null
  },
  bizType: "PAYOUT_ACCOUNT",
  bizId: "77",
  readTime: null,
  createTime: "2026-10-17T11:30:00Z"
};

const mounted: Array<{ unmount: () => void }> = [];

async function mountBell() {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: "/", component: { template: "<div />" } },
      { path: "/fund/payout-accounts", component: { template: "<div />" } },
      { path: "/kol/kols", component: { template: "<div />" } },
      { path: "/notifications", component: { template: "<div />" } }
    ]
  });
  await router.push("/");
  await router.isReady();
  const wrapper = mount(NotificationBell, {
    global: { plugins: [ElementPlus, i18n, router, createPinia()] }
  });
  await flushPromises();
  mounted.push(wrapper);
  return { wrapper, router };
}

describe("notification bell", () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    api.unreadNotificationCount.mockReset();
    api.listNotifications.mockReset();
    api.markNotificationRead.mockReset();
    api.markAllNotificationsRead.mockReset();
    payout.readTime = null;
    api.unreadNotificationCount.mockResolvedValue(3);
    api.listNotifications.mockImplementation(async () => ({
      items: [{ ...payout, params: { ...payout.params } }],
      total: "1",
      page: 1,
      size: 10
    }));
    api.markNotificationRead.mockResolvedValue(undefined);
    storeState.user = {
      role: "STAFF",
      dataScope: "OWN",
      permissions: ["kol:view"],
      postTemplate: "BD"
    };
    vi.spyOn(ElMessage, "warning").mockImplementation(() => ({ close() {} }) as never);
  });

  afterEach(() => {
    for (const wrapper of mounted) wrapper.unmount();
    mounted.length = 0;
    vi.useRealTimers();
    vi.restoreAllMocks();
  });

  it("shows 99+ when the unread count is over 99", async () => {
    api.unreadNotificationCount.mockResolvedValue(120);
    const { wrapper } = await mountBell();
    expect(wrapper.get("[data-test=notification-badge]").text()).toContain("99+");
  });

  it("pauses polling while the tab is hidden and refreshes when it returns", async () => {
    vi.useFakeTimers({ toFake: ["setInterval", "clearInterval"] });
    let calls = 0;
    api.unreadNotificationCount.mockImplementation(async () => {
      calls += 1;
      return 1;
    });
    let state: DocumentVisibilityState = "visible";
    vi.spyOn(document, "visibilityState", "get").mockImplementation(() => state);
    await mountBell();
    expect(calls).toBe(1);
    await vi.advanceTimersByTimeAsync(60_000);
    expect(calls).toBe(2);
    state = "hidden";
    document.dispatchEvent(new Event("visibilitychange"));
    await vi.advanceTimersByTimeAsync(120_000);
    expect(calls).toBe(2);
    state = "visible";
    document.dispatchEvent(new Event("visibilitychange"));
    await flushPromises();
    expect(calls).toBe(3);
  });

  it("marks a message read and does not navigate without permission", async () => {
    const { wrapper, router } = await mountBell();
    const push = vi.spyOn(router, "push");
    await wrapper.get("[data-test=notification-bell]").trigger("click");
    await flushPromises();
    await nextTick();
    const denied = document.querySelector<HTMLElement>("[data-test=notification-item]");
    expect(denied).toBeTruthy();
    denied?.click();
    await flushPromises();
    expect(api.markNotificationRead).toHaveBeenCalledWith("77");
    expect(ElMessage.warning).toHaveBeenCalledWith("你没有查看该页面的权限");
    expect(push).not.toHaveBeenCalled();
  });

  it("navigates after marking an allowed message read", async () => {
    storeState.user = {
      role: "SUPER_ADMIN",
      dataScope: "ALL",
      permissions: [],
      postTemplate: null
    };
    const { wrapper, router } = await mountBell();
    const push = vi.spyOn(router, "push");
    await wrapper.get("[data-test=notification-bell]").trigger("click");
    await flushPromises();
    const allowed = document.querySelector<HTMLElement>("[data-test=notification-item]");
    expect(allowed).toBeTruthy();
    allowed?.click();
    await flushPromises();
    expect(api.markNotificationRead).toHaveBeenCalledWith("77");
    expect(push).toHaveBeenCalledWith("/fund/payouts?id=77");
  });
});
