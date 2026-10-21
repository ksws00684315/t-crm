import { flushPromises, mount } from "@vue/test-utils";
import ElementPlus from "element-plus";
import { createPinia, setActivePinia } from "pinia";
import { beforeEach, describe, expect, it, vi } from "vitest";
import * as shopApi from "@/api/product/shop";
import type { Role, ShopVO } from "@/api/types";
import { i18n } from "@/locales";
import { useUserStore } from "@/stores/user";
import ShopPage from "../index.vue";

vi.mock("@/api/product/shop");

const row: ShopVO = {
  id: "1",
  shopName: "旗舰店",
  weidianShopId: "100",
  copyTarget: false,
  status: 1,
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
  vi.mocked(shopApi.listShops).mockResolvedValue([row]);
  const wrapper = mount(ShopPage, { global: { plugins: [ElementPlus, i18n] } });
  await flushPromises();
  return wrapper;
}

describe("shop list buttons", () => {
  beforeEach(() => setActivePinia(createPinia()));

  it("shows create, edit and status only to the super administrator", async () => {
    signIn("SUPER_ADMIN", []);
    const admin = await mountPage();
    expect(admin.find('[data-test="create"]').exists()).toBe(true);
    expect(admin.find('[data-test="edit"]').exists()).toBe(true);
    // The status toggle folds into the row "more" dropdown, which teleports to body.
    expect(document.body.querySelector('[data-test="status-1"]')).not.toBeNull();

    signIn("STAFF", ["product:view", "product:edit", "product:status"]);
    const staff = await mountPage();
    expect(staff.find('[data-test="create"]').exists()).toBe(false);
    expect(staff.find('[data-test="edit"]').exists()).toBe(false);
    expect(staff.find('[data-test="status"]').exists()).toBe(false);
  });
});
