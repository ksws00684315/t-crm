import { flushPromises, mount } from "@vue/test-utils";
import ElementPlus from "element-plus";
import { createPinia, setActivePinia } from "pinia";
import { beforeEach, describe, expect, it, vi } from "vitest";
import * as productApi from "@/api/product/product";
import * as shopApi from "@/api/product/shop";
import type { ProductVO, Role } from "@/api/types";
import { i18n } from "@/locales";
import { useUserStore } from "@/stores/user";
import ProductPage from "../index.vue";

vi.mock("@/api/product/product");
vi.mock("@/api/product/shop");

const row: ProductVO = {
  id: "1",
  productCode: "P000001",
  shopId: "1",
  shopName: "旗舰店",
  weidianShopId: "100",
  weidianItemId: "123456",
  name: "鞋",
  category: null,
  imageUrl: null,
  price: "10.00",
  rate: null,
  status: 1,
  createTime: "2026-10-05T00:00:00Z",
  updateTime: "2026-10-05T00:00:00Z"
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
  vi.mocked(shopApi.listShops).mockResolvedValue([]);
  vi.mocked(productApi.listProducts).mockResolvedValue({
    items: [row],
    total: "1",
    page: 1,
    size: 20
  });
  const wrapper = mount(ProductPage, { global: { plugins: [ElementPlus, i18n] } });
  await flushPromises();
  return wrapper;
}

describe("product list buttons", () => {
  beforeEach(() => setActivePinia(createPinia()));

  it("shows edit, import, shelf and export to the super administrator", async () => {
    signIn("SUPER_ADMIN", []);
    const wrapper = await mountPage();
    expect(wrapper.find('[data-test="import"]').exists()).toBe(true);
    expect(wrapper.find('[data-test="edit"]').exists()).toBe(true);
    expect(wrapper.find('[data-test="status"]').exists()).toBe(true);
    expect(wrapper.find(".el-table-column--selection").exists()).toBe(true);
    expect(wrapper.find('[data-test="export"]').exists()).toBe(true);
  });

  it("shows edit and import for product:edit and hides shelf", async () => {
    signIn("STAFF", ["product:edit"]);
    const wrapper = await mountPage();
    expect(wrapper.find('[data-test="import"]').exists()).toBe(true);
    expect(wrapper.find('[data-test="edit"]').exists()).toBe(true);
    expect(wrapper.find('[data-test="status"]').exists()).toBe(false);
    expect(wrapper.find(".el-table-column--selection").exists()).toBe(false);
    expect(wrapper.find('[data-test="export"]').exists()).toBe(false);
  });

  it("shows shelf for product:status and hides edit and import", async () => {
    signIn("STAFF", ["product:status"]);
    const wrapper = await mountPage();
    expect(wrapper.find('[data-test="import"]').exists()).toBe(false);
    expect(wrapper.find('[data-test="edit"]').exists()).toBe(false);
    expect(wrapper.find('[data-test="status"]').exists()).toBe(true);
    expect(wrapper.find(".el-table-column--selection").exists()).toBe(true);
  });

  it("shows export only with data:export", async () => {
    signIn("STAFF", ["product:view", "data:export"]);
    const wrapper = await mountPage();
    expect(wrapper.find('[data-test="edit"]').exists()).toBe(false);
    expect(wrapper.find('[data-test="status"]').exists()).toBe(false);
    expect(wrapper.find('[data-test="export"]').exists()).toBe(true);
  });
});
