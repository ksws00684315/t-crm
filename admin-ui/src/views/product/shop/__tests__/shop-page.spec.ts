import { flushPromises, mount } from "@vue/test-utils";
import ElementPlus, { ElMessage } from "element-plus";
import { createPinia, setActivePinia } from "pinia";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import * as shopApi from "@/api/product/shop";
import * as orderApi from "@/api/order/order";
import * as weidianApi from "@/api/integration/weidian";
import type { Role, ShopVO } from "@/api/types";
import { i18n } from "@/locales";
import { useUserStore } from "@/stores/user";
import * as notify from "@/utils/notify";
import { formatInstantToMinute } from "@/utils/format";
import { ApiError } from "@/utils/http/error";
import ShopPage from "../index.vue";

vi.mock("@/api/product/shop");
vi.mock("@/api/order/order");
vi.mock("@/api/integration/weidian");
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
    expect(admin.find('[data-test="weidian-app-config"]').exists()).toBe(true);
    expect(admin.find('[data-test="authorize-1"]').exists()).toBe(true);
    expect(admin.find('[data-test="edit-1"]').exists()).toBe(true);
    expect(admin.find('[data-test="status-1"]').exists()).toBe(true);
    expect(admin.find('[data-test="more-actions"]').exists()).toBe(false);
    admin.unmount();
  });

  it("keeps order sync visible to staff while hiding shop maintenance", async () => {
    signIn("STAFF", ["product:view", "product:edit", "product:status", "order:manage"]);
    const staff = await mountPage();
    expect(staff.find('[data-test="create"]').exists()).toBe(false);
    expect(staff.find('[data-test="weidian-app-config"]').exists()).toBe(false);
    expect(staff.find('[data-test="sync-order-1"]').exists()).toBe(true);
    expect(staff.find('[data-test="authorize-1"]').exists()).toBe(false);
    expect(staff.find('[data-test="edit-1"]').exists()).toBe(false);
    expect(staff.find('[data-test="status-1"]').exists()).toBe(false);
    expect(staff.find('[data-test="more-actions"]').exists()).toBe(false);
    staff.unmount();
  });

  it("loads the company app status and never displays the configured secret", async () => {
    signIn("SUPER_ADMIN", []);
    vi.mocked(weidianApi.getWeidianApp).mockResolvedValue({
      appKey: "t52_fake_appkey",
      secretConfigured: true,
      updateTime: "2026-10-23T01:02:00Z",
      callbackUrl: "https://admin.example.com/admin-api/integration/weidian/oauth/callback"
    });
    const wrapper = await mountPage();
    await wrapper.get('[data-test="weidian-app-config"]').trigger("click");
    await flushPromises();

    expect(weidianApi.getWeidianApp).toHaveBeenCalledOnce();
    expect(
      (wrapper.get('input[data-test="weidian-app-key"]').element as HTMLInputElement).value
    ).toBe("t52_fake_appkey");
    const secretInput = wrapper.get('input[data-test="weidian-app-secret"]');
    expect((secretInput.element as HTMLInputElement).value).toBe("");
    expect((secretInput.element as HTMLInputElement).placeholder).toBe("已配置，保存时需重新填写");
    expect(wrapper.text()).toContain("已配置");
    expect(wrapper.text()).toContain(formatInstantToMinute("2026-10-23T01:02:00Z"));
    expect(wrapper.text()).toContain(
      "https://admin.example.com/admin-api/integration/weidian/oauth/callback"
    );
    wrapper.unmount();
  });

  it("shows callback warnings for non-https and invalid domains", async () => {
    signIn("SUPER_ADMIN", []);
    vi.mocked(weidianApi.getWeidianApp)
      .mockResolvedValueOnce({
        appKey: null,
        secretConfigured: false,
        updateTime: null,
        callbackUrl: "http://localhost/callback"
      })
      .mockResolvedValueOnce({
        appKey: null,
        secretConfigured: false,
        updateTime: null,
        callbackUrl: "https://crm.invalid/callback"
      })
      .mockResolvedValueOnce({
        appKey: null,
        secretConfigured: false,
        updateTime: null,
        callbackUrl: "https://crm.example.com/callback"
      });
    const wrapper = await mountPage();

    await wrapper.get('[data-test="weidian-app-config"]').trigger("click");
    await flushPromises();
    expect(wrapper.find('[data-test="callback-invalid"]').exists()).toBe(true);
    await wrapper.get('[data-test="weidian-app-cancel"]').trigger("click");
    await wrapper.get('[data-test="weidian-app-config"]').trigger("click");
    await flushPromises();
    expect(wrapper.find('[data-test="callback-invalid"]').exists()).toBe(true);
    await wrapper.get('[data-test="weidian-app-cancel"]').trigger("click");
    await wrapper.get('[data-test="weidian-app-config"]').trigger("click");
    await flushPromises();
    expect(wrapper.find('[data-test="callback-invalid"]').exists()).toBe(false);
    wrapper.unmount();
  });

  it("validates required credentials and sends the exact app configuration body", async () => {
    signIn("SUPER_ADMIN", []);
    vi.mocked(weidianApi.getWeidianApp).mockResolvedValue({
      appKey: null,
      secretConfigured: false,
      updateTime: null,
      callbackUrl: "https://admin.example.com/admin-api/integration/weidian/oauth/callback"
    });
    vi.mocked(weidianApi.saveWeidianApp).mockResolvedValue(undefined);
    const wrapper = await mountPage();
    await wrapper.get('[data-test="weidian-app-config"]').trigger("click");
    await flushPromises();
    expect(wrapper.get('input[data-test="weidian-app-key"]').attributes("maxlength")).toBe("64");
    await wrapper.get('[data-test="weidian-app-save"]').trigger("click");
    await flushPromises();
    expect(weidianApi.saveWeidianApp).not.toHaveBeenCalled();

    await wrapper.get('input[data-test="weidian-app-key"]').setValue("a".repeat(65));
    await wrapper.get('input[data-test="weidian-app-secret"]').setValue("t52_random_fake_secret");
    await wrapper.get('[data-test="weidian-app-save"]').trigger("click");
    await flushPromises();
    expect(weidianApi.saveWeidianApp).not.toHaveBeenCalled();
    await wrapper.get('input[data-test="weidian-app-key"]').setValue("t52_fake_appkey");
    await wrapper.get('[data-test="weidian-app-save"]').trigger("click");
    await flushPromises();
    expect(weidianApi.saveWeidianApp).toHaveBeenCalledWith({
      appKey: "t52_fake_appkey",
      appSecret: "t52_random_fake_secret"
    });
    expect(ElMessage.success).toHaveBeenCalledWith("微店应用配置已保存");
    expect(shopApi.pageShops).toHaveBeenCalledTimes(2);
    wrapper.unmount();
  });

  it("reports a configuration load failure and leaves the dialog usable", async () => {
    signIn("SUPER_ADMIN", []);
    const failure = new Error("app config unavailable");
    vi.mocked(weidianApi.getWeidianApp).mockRejectedValue(failure);
    const wrapper = await mountPage();
    await wrapper.get('[data-test="weidian-app-config"]').trigger("click");
    await flushPromises();

    expect(notify.notifyError).toHaveBeenCalledWith(failure);
    expect(wrapper.find('[data-test="weidian-app-save"]').attributes("disabled")).toBeUndefined();
    wrapper.unmount();
  });

  it("closes the persistent app-not-configured notice when the config dialog opens (L-005)", async () => {
    signIn("SUPER_ADMIN", []);
    vi.mocked(weidianApi.startWeidianAuthorization).mockRejectedValue(
      new ApiError({ status: 409, errorCode: "WEIDIAN_APP_NOT_CONFIGURED", traceId: "t59" })
    );
    const wrapper = await mountPage();
    await wrapper.get('[data-test="authorize-1"]').trigger("click");
    await flushPromises();

    // duration: 0 keeps the notice on screen; it must disappear once the config dialog opens.
    expect(document.body.textContent).toContain("尚未配置微店服务型应用");
    await wrapper.get('[data-test="weidian-app-config"]').trigger("click");
    await flushPromises();
    expect(document.body.textContent).not.toContain("尚未配置微店服务型应用");
    wrapper.unmount();
  });

  it("reports a configuration save failure without closing the dialog", async () => {
    signIn("SUPER_ADMIN", []);
    vi.mocked(weidianApi.getWeidianApp).mockResolvedValue({
      appKey: null,
      secretConfigured: false,
      updateTime: null,
      callbackUrl: "https://admin.example.com/callback"
    });
    const failure = new Error("app config save failed");
    vi.mocked(weidianApi.saveWeidianApp).mockRejectedValue(failure);
    const wrapper = await mountPage();
    await wrapper.get('[data-test="weidian-app-config"]').trigger("click");
    await flushPromises();
    await wrapper.get('input[data-test="weidian-app-key"]').setValue("t52_fake_appkey");
    await wrapper.get('input[data-test="weidian-app-secret"]').setValue("t52_random_fake_secret");
    await wrapper.get('[data-test="weidian-app-save"]').trigger("click");
    await flushPromises();

    expect(notify.notifyError).toHaveBeenCalledWith(failure);
    expect(wrapper.find('[data-test="weidian-app-dialog"]').exists()).toBe(true);
    expect(
      (wrapper.get('input[data-test="weidian-app-secret"]').element as HTMLInputElement).value
    ).toBe("t52_random_fake_secret");
    wrapper.unmount();
  });

  it("updates app configuration labels when the language changes", async () => {
    signIn("SUPER_ADMIN", []);
    vi.mocked(weidianApi.getWeidianApp).mockResolvedValue({
      appKey: "t52_fake_appkey",
      secretConfigured: true,
      updateTime: null,
      callbackUrl: "https://admin.example.com/callback"
    });
    const wrapper = await mountPage();
    await wrapper.get('[data-test="weidian-app-config"]').trigger("click");
    await flushPromises();
    i18n.global.locale.value = "en-US";
    await flushPromises();
    expect(wrapper.text()).toContain("Weidian Service App Configuration");
    expect(wrapper.text()).toContain("OAuth Callback URL");
    wrapper.unmount();
  });

  it("offers app configuration when authorization reports no service app", async () => {
    signIn("SUPER_ADMIN", []);
    vi.mocked(weidianApi.getWeidianApp).mockResolvedValue({
      appKey: null,
      secretConfigured: false,
      updateTime: null,
      callbackUrl: "https://admin.example.com/callback"
    });
    vi.mocked(weidianApi.startWeidianAuthorization).mockRejectedValue(
      new ApiError({ status: 422, errorCode: "WEIDIAN_APP_NOT_CONFIGURED", traceId: "test" })
    );
    const wrapper = await mountPage();
    await wrapper.get('[data-test="authorize-1"]').trigger("click");
    await flushPromises();
    expect(weidianApi.startWeidianAuthorization).toHaveBeenCalledWith("1");
    const configureButton = document.body.querySelector('[data-test="weidian-go-config"]');
    expect(configureButton).not.toBeNull();
    configureButton?.dispatchEvent(new MouseEvent("click", { bubbles: true }));
    await flushPromises();
    expect(weidianApi.getWeidianApp).toHaveBeenCalledOnce();
    wrapper.unmount();
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
    const poolAction = wrapper.get('[data-test="copy-pool-1"]');
    expect(poolAction.attributes("disabled")).toBeDefined();
    expect(poolAction.classes()).toContain("is-disabled");
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
