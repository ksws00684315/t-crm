import { flushPromises, mount } from "@vue/test-utils";
import ElementPlus, { ElMessage } from "element-plus";
import { createPinia, setActivePinia } from "pinia";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import * as shopApi from "@/api/product/shop";
import * as orderApi from "@/api/order/order";
import type { Role, ShopVO } from "@/api/types";
import { i18n } from "@/locales";
import { useUserStore } from "@/stores/user";
import * as notify from "@/utils/notify";
import ShopPage from "../index.vue";

vi.mock("@/api/product/shop");
vi.mock("@/api/order/order");
vi.mock("@/utils/notify");

const row: ShopVO = {
  id: "1",
  shopCode: "SHOP-100001",
  shopName: "旗舰店",
  channel: "WEIDIAN",
  weidianShopId: "100",
  status: 1,
  isSource: false,
  copyPoolStatus: "EXITED",
  maxItems: 20000,
  currentItems: 1,
  itemsRefreshedAt: null,
  authStatus: "UNAUTHORIZED",
  authExpiresAt: null,
  apiStatus: {
    product: { ok: null, checkedAt: null, error: null },
    order: { ok: null, checkedAt: null, error: null },
    refund: { ok: null, checkedAt: null, error: null }
  },
  copyEligible: false,
  copyIneligibleReason: "UNAUTHORIZED",
  remark: null,
  createTime: "2026-10-01T00:00:00Z",
  updateTime: "2026-10-01T00:00:00Z"
};

function signIn(role: Role, permissions: string[]) {
  useUserStore().user = {
    id: "1",
    username: "me",
    nickname: "Me",
    email: null,
    role,
    postTemplate: role === "STAFF" ? "OPERATOR" : null,
    permissions,
    dataScope: "ALL",
    tenantId: "1",
    tenantName: "默认公司",
    status: 1,
    lastLoginTime: null,
    createTime: "2026-10-01T00:00:00Z"
  };
}

async function mountPage() {
  vi.mocked(shopApi.pageShops).mockResolvedValue({ items: [row], total: "1", page: 1, size: 20 });
  const wrapper = mount(ShopPage, {
    global: { plugins: [ElementPlus, i18n] },
    attachTo: document.body
  });
  await flushPromises();
  return wrapper;
}

describe("shop list buttons", () => {
  beforeEach(() => {
    document.body.innerHTML = "";
    setActivePinia(createPinia());
    i18n.global.locale.value = "zh-CN";
    vi.clearAllMocks();
    vi.spyOn(ElMessage, "success").mockImplementation(() => undefined as never);
  });

  afterEach(() => {
    document.body.innerHTML = "";
    vi.restoreAllMocks();
  });

  it("shows write actions only to the super administrator", async () => {
    signIn("SUPER_ADMIN", []);
    const admin = await mountPage();
    expect(admin.find('[data-test="create"]').exists()).toBe(true);
    expect(admin.find('[data-test="authorize-1"]').exists()).toBe(true);
    await admin.find('[data-test="more-actions"]').trigger("click");
    await flushPromises();
    expect(document.body.querySelector('[data-test="edit-1"]')).not.toBeNull();
    expect(document.body.querySelector('[data-test="status-1"]')).not.toBeNull();
    admin.unmount();
  });

  it("keeps order sync visible to staff while hiding shop maintenance", async () => {
    signIn("STAFF", ["product:view", "product:edit", "product:status", "order:manage"]);
    const staff = await mountPage();
    expect(staff.find('[data-test="create"]').exists()).toBe(false);
    expect(staff.find('[data-test="sync-order-1"]').exists()).toBe(true);
    expect(staff.find('[data-test="more-actions"]').exists()).toBe(false);
    staff.unmount();
  });

  it("submits the shop filters and the requested page", async () => {
    signIn("SUPER_ADMIN", []);
    const wrapper = await mountPage();
    await wrapper.get('[data-test="search-shop-code"]').setValue("SHOP-10");
    await wrapper.get('[data-test="search-shop-name"]').setValue("旗舰");
    const channelFilter = wrapper
      .findAllComponents({ name: "ElSelect" })
      .find(select => select.attributes("data-test") === "search-channel");
    await channelFilter?.setValue("WEIDIAN");
    await flushPromises();
    await wrapper.get('[data-test="search"]').trigger("click");
    await flushPromises();
    expect(shopApi.pageShops).toHaveBeenLastCalledWith({
      shopCode: "SHOP-10",
      shopName: "旗舰",
      channel: "WEIDIAN",
      page: 1,
      size: 20
    });
    wrapper.unmount();
  });

  it("sends order and refund sync types for the selected shop", async () => {
    signIn("SUPER_ADMIN", []);
    vi.mocked(orderApi.triggerOrderSync).mockResolvedValue(null);
    const wrapper = await mountPage();
    await wrapper.get('[data-test="sync-order-1"]').trigger("click");
    await wrapper.get('[data-test="sync-refund-1"]').trigger("click");
    await flushPromises();
    expect(orderApi.triggerOrderSync).toHaveBeenNthCalledWith(1, "1", "ORDER");
    expect(orderApi.triggerOrderSync).toHaveBeenNthCalledWith(2, "1", "REFUND");
    wrapper.unmount();
  });

  it("disables pool enrollment for a source shop and explains why", async () => {
    signIn("SUPER_ADMIN", []);
    const sourceRow = {
      ...row,
      isSource: true,
      copyPoolStatus: "NOT_APPLICABLE" as const
    };
    vi.mocked(shopApi.pageShops).mockResolvedValue({
      items: [sourceRow],
      total: "1",
      page: 1,
      size: 20
    });
    const wrapper = mount(ShopPage, {
      global: { plugins: [ElementPlus, i18n] },
      attachTo: document.body
    });
    await flushPromises();
    await wrapper.get('[data-test="more-actions"]').trigger("click");
    await flushPromises();
    const poolAction = document.body.querySelector<HTMLElement>('[data-test="copy-pool-1"]');
    expect(poolAction).not.toBeNull();
    expect(poolAction?.getAttribute("aria-disabled")).toBe("true");
    expect(poolAction?.getAttribute("title")).toContain("源店铺");
    wrapper.unmount();
  });

  it("reports a capacity refresh failure and stops loading", async () => {
    signIn("SUPER_ADMIN", []);
    const failure = new Error("refresh failed");
    vi.mocked(shopApi.refreshShopCapacity).mockRejectedValue(failure);
    const wrapper = await mountPage();
    await wrapper.get('[data-test="refresh-capacity-1"]').trigger("click");
    await flushPromises();
    expect(shopApi.refreshShopCapacity).toHaveBeenCalledWith("1");
    expect(notify.notifyError).toHaveBeenCalledWith(failure);
    wrapper.unmount();
  });
});
