import { flushPromises, mount } from "@vue/test-utils";
import { nextTick } from "vue";
import ElementPlus, { ElMessage, ElMessageBox } from "element-plus";
import { createPinia, setActivePinia } from "pinia";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import * as copyTokenApi from "@/api/kol/copy-token";
import * as kolApi from "@/api/kol/kol";
import * as shopApi from "@/api/product/shop";
import type { CopyTokenVO, Role } from "@/api/types";
import { i18n } from "@/locales";
import { useUserStore } from "@/stores/user";
import * as copyUtil from "@/utils/copy";
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
    used: 0,
    quota: 1000,
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

async function open() {
  vi.mocked(copyTokenApi.listCopyTokens).mockResolvedValue({
    items: [row()],
    total: "1",
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
    vi.mocked(kolApi.listKols).mockResolvedValue({ items: [], total: "0", page: 1, size: 20 });
    const wrapper = await open();
    await wrapper.get('[data-test="create-token"]').trigger("click");
    await flushPromises();
    expect(document.body.querySelector('[data-test="copy-token-create-dialog"]')).toBeTruthy();
    wrapper.unmount();
  });
});
