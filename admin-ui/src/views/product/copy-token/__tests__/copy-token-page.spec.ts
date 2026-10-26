import { flushPromises, mount } from "@vue/test-utils";
import { nextTick } from "vue";
import ElementPlus, { ElMessage, ElMessageBox, ElSelect } from "element-plus";
import { createPinia, setActivePinia } from "pinia";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import * as copyTokenApi from "@/api/kol/copy-token";
import * as kolApi from "@/api/kol/kol";
import * as shopApi from "@/api/product/shop";
import type { CopyChannelVO, CopyTokenVO, CreatedCopyTokenVO, Role } from "@/api/types";
import { i18n } from "@/locales";
import { useUserStore } from "@/stores/user";
import * as copyUtil from "@/utils/copy";
import CopyTokenCreateDialog from "../CopyTokenCreateDialog.vue";
import CopyTokenPage from "../index.vue";

vi.mock("@/api/kol/copy-token");
vi.mock("@/api/kol/kol");
vi.mock("@/api/product/shop");
vi.mock("@/utils/copy");

function row(overrides: Partial<CopyTokenVO> = {}): CopyTokenVO {
  return {
    id: "1",
    tokenHint: "TKN-AAAA****ZZZZ",
    kolId: "10",
    kolCode: "KOL0001",
    kolName: "Alice",
    targetChannel: "WEIDIAN",
    targetShopId: null,
    targetShopName: null,
    targetShopCode: null,
    used: 0,
    quota: 1,
    validMinutes: 1440,
    expiresAt: "2026-10-18T12:00:00Z",
    displayStatus: "ACTIVE",
    lastUsedAt: null,
    createTime: "2026-10-17T12:00:00Z",
    ...overrides
  };
}

function signIn(role: Role, permissions: string[]) {
  useUserStore().user = {
    id: "1",
    username: "me",
    nickname: "Me",
    email: null,
    role,
    postTemplate: role === "STAFF" ? "BD" : null,
    permissions,
    dataScope: role === "STAFF" ? "OWN" : "ALL",
    tenantId: "1",
    tenantName: "默认公司",
    status: 1,
    lastLoginTime: null,
    createTime: "2026-10-01T00:00:00Z"
  };
}

async function open(items: CopyTokenVO[] = [row()]) {
  vi.mocked(copyTokenApi.listCopyTokens).mockResolvedValue({
    items,
    total: String(items.length),
    page: 1,
    size: 20
  });
  const wrapper = mount(CopyTokenPage, {
    global: { plugins: [ElementPlus, i18n] },
    attachTo: document.body
  });
  await flushPromises();
  await nextTick();
  return wrapper;
}

describe("Copy token page", () => {
  beforeEach(() => {
    document.body.innerHTML = "";
    setActivePinia(createPinia());
    signIn("SUPER_ADMIN", []);
    vi.mocked(copyUtil.copyText).mockResolvedValue(true);
    vi.spyOn(ElMessage, "success").mockImplementation(() => undefined as never);
    vi.spyOn(ElMessage, "error").mockImplementation(() => undefined as never);
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it("lists tokens and reveals into the clipboard", async () => {
    const wrapper = await open();
    expect(copyTokenApi.listCopyTokens).toHaveBeenCalled();
    expect(wrapper.text()).toContain("TKN-AAAA****ZZZZ");
    expect(wrapper.text()).toContain("Alice");
    vi.mocked(copyTokenApi.revealCopyToken).mockResolvedValue({
      token: "TKN-" + "A".repeat(26)
    });
    const reveal = wrapper.find('[data-test="reveal"]');
    expect(reveal.exists()).toBe(true);
    expect(reveal.attributes("data-id")).toBe("1");
    await reveal.trigger("click");
    await flushPromises();
    await nextTick();
    expect(copyTokenApi.revealCopyToken).toHaveBeenCalled();
    expect(copyTokenApi.revealCopyToken).toHaveBeenCalledWith("1");
    expect(copyUtil.copyText).toHaveBeenCalledWith("TKN-" + "A".repeat(26));
    wrapper.unmount();
  });

  it("revokes after confirmation", async () => {
    const wrapper = await open();
    vi.spyOn(ElMessageBox, "confirm").mockResolvedValue("confirm" as never);
    vi.mocked(copyTokenApi.revokeCopyToken).mockResolvedValue(null);
    const revokeBtn = wrapper.find('[data-test="revoke"]');
    expect(revokeBtn.exists()).toBe(true);
    await revokeBtn.trigger("click");
    await flushPromises();
    await nextTick();
    expect(copyTokenApi.revokeCopyToken).toHaveBeenCalledWith("1");
    wrapper.unmount();
  });

  it("keeps Revoke visible for every status and only allows active tokens to be revoked", async () => {
    const wrapper = await open([
      row({ id: "1", displayStatus: "ACTIVE" }),
      row({ id: "2", displayStatus: "REVOKED" }),
      row({ id: "3", displayStatus: "EXHAUSTED" }),
      row({ id: "4", displayStatus: "EXPIRED" })
    ]);
    const actions = wrapper.findAll('[data-test="revoke"]');
    expect(actions).toHaveLength(4);
    expect(actions[0].attributes("disabled")).toBeUndefined();
    for (const button of actions.slice(1)) {
      expect(button.attributes("disabled")).toBeDefined();
      await button.trigger("click");
    }
    expect(copyTokenApi.revokeCopyToken).not.toHaveBeenCalled();
    expect(wrapper.find('[data-test="enable-token"]').exists()).toBe(false);
    wrapper.unmount();
  });

  it("renders the three primary filters and submits the selected creator and channel", async () => {
    const wrapper = await open();
    expect(wrapper.get('[data-test="filter-hint"]').isVisible()).toBe(true);
    expect(wrapper.get('[data-test="filter-kol"]').isVisible()).toBe(true);
    expect(wrapper.get('[data-test="filter-channel"]').isVisible()).toBe(true);
    await wrapper.get('[data-test="filter-hint"]').setValue("TKN-test");
    const selects = wrapper.findAllComponents(ElSelect);
    await selects[0].vm.$emit("update:modelValue", "10");
    await selects[1].vm.$emit("update:modelValue", "WEIDIAN");
    await wrapper.get('[data-test="search"]').trigger("click");
    await flushPromises();
    expect(copyTokenApi.listCopyTokens).toHaveBeenLastCalledWith({
      page: 1,
      size: 20,
      hint: "TKN-test",
      kolId: "10",
      targetChannel: "WEIDIAN",
      status: undefined
    });
    wrapper.unmount();
  });

  it("hides the page without kol:copy-token", async () => {
    signIn("STAFF", ["kol:view"]);
    vi.mocked(copyTokenApi.listCopyTokens).mockResolvedValue({
      items: [],
      total: "0",
      page: 1,
      size: 20
    });
    const wrapper = mount(CopyTokenPage, {
      global: { plugins: [ElementPlus, i18n] },
      attachTo: document.body
    });
    await flushPromises();
    expect(wrapper.find('[data-test="create-token"]').exists()).toBe(false);
    expect(copyTokenApi.listCopyTokens).not.toHaveBeenCalled();
    wrapper.unmount();
  });

  it("opens the create dialog", async () => {
    vi.mocked(shopApi.listShops).mockResolvedValue([]);
    vi.mocked(shopApi.listCopyChannels).mockResolvedValue([
      {
        channel: "WEIDIAN",
        remainingCapacity: 10,
        defaultQuota: 1,
        defaultValidMinutes: 1440,
        shops: [{ id: "20", shopCode: "SHOP-100020", shopName: "可用店铺", remainingCapacity: 10 }]
      }
    ]);
    vi.mocked(kolApi.listKols).mockResolvedValue({ items: [], total: "0", page: 1, size: 20 });
    const wrapper = await open();
    await wrapper.get('[data-test="create-token"]').trigger("click");
    await flushPromises();
    expect(document.body.querySelector('[data-test="copy-token-create-dialog"]')).toBeTruthy();
    wrapper.unmount();
  });

  it("loads configured defaults and omits an automatic target shop from the request", async () => {
    const channel: CopyChannelVO = {
      channel: "WEIDIAN",
      remainingCapacity: 3,
      defaultQuota: 1,
      defaultValidMinutes: 1440,
      shops: [{ id: "20", shopCode: "SHOP-100020", shopName: "可用店铺", remainingCapacity: 2 }]
    };
    vi.mocked(shopApi.listCopyChannels).mockResolvedValue([channel]);
    const token: CreatedCopyTokenVO = {
      ...row(),
      token: "TKN-SECRET"
    };
    vi.mocked(copyTokenApi.createCopyToken).mockResolvedValue(token);
    const wrapper = mount(CopyTokenCreateDialog, {
      props: { modelValue: false, presetKolId: "10", presetKolLabel: "KOL0001" },
      global: { plugins: [ElementPlus, i18n] },
      attachTo: document.body
    });
    await wrapper.setProps({ modelValue: true });
    await flushPromises();
    expect(
      (wrapper.get('[data-test="create-quota"] input').element as HTMLInputElement).value
    ).toBe("1");
    expect(
      (wrapper.get('[data-test="create-minutes"] input').element as HTMLInputElement).value
    ).toBe("1440");
    await wrapper.get('[data-test="create-submit"]').trigger("click");
    await flushPromises();
    expect(copyTokenApi.createCopyToken).toHaveBeenCalledWith({
      kolId: "10",
      targetChannel: "WEIDIAN",
      quota: 1,
      validMinutes: 1440
    });
    wrapper.unmount();
  });

  it("checks selected shop capacity before creation and passes a chosen shop", async () => {
    vi.mocked(shopApi.listCopyChannels).mockResolvedValue([
      {
        channel: "WEIDIAN",
        remainingCapacity: 3,
        defaultQuota: 1,
        defaultValidMinutes: 1440,
        shops: [{ id: "20", shopCode: "SHOP-100020", shopName: "可用店铺", remainingCapacity: 2 }]
      }
    ]);
    vi.mocked(copyTokenApi.createCopyToken).mockResolvedValue({
      ...row({ targetShopId: "20", targetShopName: "可用店铺", targetShopCode: "SHOP-100020" }),
      token: "TKN-SECRET"
    });
    const wrapper = mount(CopyTokenCreateDialog, {
      props: { modelValue: false, presetKolId: "10", presetKolLabel: "KOL0001" },
      global: { plugins: [ElementPlus, i18n] },
      attachTo: document.body
    });
    await wrapper.setProps({ modelValue: true });
    await flushPromises();
    const selects = wrapper.findAllComponents({ name: "ElSelect" });
    await selects[2]?.setValue("20");
    await wrapper.get('[data-test="create-quota"] input').setValue("3");
    await flushPromises();
    expect(wrapper.find('[data-test="capacity-error"]').exists()).toBe(true);
    await wrapper.get('[data-test="create-submit"]').trigger("click");
    await flushPromises();
    expect(copyTokenApi.createCopyToken).not.toHaveBeenCalled();

    await wrapper.get('[data-test="create-quota"] input').setValue("2");
    await wrapper.get('[data-test="create-submit"]').trigger("click");
    await flushPromises();
    expect(copyTokenApi.createCopyToken).toHaveBeenCalledWith({
      kolId: "10",
      targetChannel: "WEIDIAN",
      targetShopId: "20",
      quota: 2,
      validMinutes: 1440
    });
    wrapper.unmount();
  });
});
