import { flushPromises, mount } from "@vue/test-utils";
import ElementPlus, { ElMessage, ElMessageBox } from "element-plus";
import { createPinia, setActivePinia } from "pinia";
import { beforeEach, describe, expect, it, vi } from "vitest";
import * as linkApi from "@/api/link/link";
import * as kolItemApi from "@/api/product/kol-item";
import * as shopApi from "@/api/product/shop";
import type { DataScope, KolItemVO, Role } from "@/api/types";
import { useUserStore } from "@/stores/user";
import KolItemPage from "../index.vue";

vi.mock("@/api/link/link");
vi.mock("@/api/product/kol-item");
vi.mock("@/api/product/shop");

const row: KolItemVO = {
  id: "1",
  kolId: "11",
  kolCode: "KOL0001",
  kolName: "Alice",
  productId: "22",
  productCode: "P000001",
  productName: "鞋",
  shopId: "1",
  shopName: "旗舰店",
  weidianShopId: "100",
  weidianItemId: "123456",
  validFrom: "2026-10-05T01:02:03Z",
  validTo: null
};

function signIn(role: Role, dataScope: DataScope) {
  useUserStore().user = {
    id: "1",
    username: "me",
    nickname: "Me",
    email: null,
    role,
    dataScope,
    status: 1,
    lastLoginTime: null,
    createTime: "2026-10-01T00:00:00Z"
  };
}

async function mountPage() {
  vi.mocked(shopApi.listShops).mockResolvedValue([
    {
      id: "1",
      shopName: "旗舰店",
      weidianShopId: "100",
      status: 1,
      remark: null,
      createTime: "2026-10-01T00:00:00Z",
      updateTime: "2026-10-01T00:00:00Z"
    }
  ]);
  vi.mocked(kolItemApi.listKolItems).mockResolvedValue({
    items: [row],
    total: "1",
    page: 1,
    size: 20
  });
  const wrapper = mount(KolItemPage, { global: { plugins: [ElementPlus] } });
  await flushPromises();
  return wrapper;
}

describe("Kol item mapping page", () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    vi.mocked(kolItemApi.listKolItems).mockClear();
    vi.spyOn(ElMessage, "warning").mockImplementation(() => ({}) as never);
  });

  it("does not fetch mappings on open and shows the search tip", async () => {
    signIn("SUPER_ADMIN", "ALL");
    const wrapper = await mountPage();
    expect(kolItemApi.listKolItems).not.toHaveBeenCalled();
    expect(wrapper.find('[data-test="search-tip"]').exists()).toBe(true);
    expect(wrapper.text()).toContain("请输入达人编号、原商品编码或专属商品 ID 查询");
  });

  it("tips and skips the request when search has no precision filter", async () => {
    signIn("OPERATOR", "ALL");
    const wrapper = await mountPage();
    await wrapper.find('[data-test="search"]').trigger("click");
    await flushPromises();
    expect(ElMessage.warning).toHaveBeenCalledWith("请输入达人编号、原商品编码或专属商品 ID 查询");
    expect(kolItemApi.listKolItems).not.toHaveBeenCalled();
    expect(wrapper.find('[data-test="search-tip"]').exists()).toBe(true);
  });

  it("queries when a precision filter is set and shows page total", async () => {
    signIn("FINANCE", "ALL");
    const wrapper = await mountPage();
    await wrapper.find('[data-test="kol-code"]').setValue("KOL0001");
    await wrapper.find('[data-test="search"]').trigger("click");
    await flushPromises();
    expect(kolItemApi.listKolItems).toHaveBeenCalledWith({
      page: 1,
      size: 20,
      kolCode: "KOL0001",
      productCode: undefined,
      shopId: undefined,
      weidianItemId: undefined,
      current: true
    });
    expect(wrapper.find('[data-test="search-tip"]').exists()).toBe(false);
    expect(wrapper.text()).toContain("KOL0001");
    const pagination = wrapper.findComponent({ name: "ElPagination" });
    expect(pagination.props("total")).toBe(1);
  });

  it("returns to the tip state on reset without requesting", async () => {
    signIn("SUPER_ADMIN", "ALL");
    const wrapper = await mountPage();
    await wrapper.find('[data-test="product-code"]').setValue("P000001");
    await wrapper.find('[data-test="search"]').trigger("click");
    await flushPromises();
    expect(kolItemApi.listKolItems).toHaveBeenCalledTimes(1);
    await wrapper.find('[data-test="reset"]').trigger("click");
    await flushPromises();
    expect(kolItemApi.listKolItems).toHaveBeenCalledTimes(1);
    expect(wrapper.find('[data-test="search-tip"]').exists()).toBe(true);
  });

  it.each([
    ["SUPER_ADMIN", "ALL", true],
    ["OPERATOR", "ALL", false],
    ["FINANCE", "ALL", false]
  ] as const)("%s + %s: rebuild link index button %s", async (role, scope, visible) => {
    signIn(role, scope);
    const wrapper = await mountPage();
    expect(wrapper.find('[data-test="rebuild-index"]').exists()).toBe(visible);
  });

  it("starts a rebuild after confirmation", async () => {
    signIn("SUPER_ADMIN", "ALL");
    vi.spyOn(ElMessageBox, "confirm").mockResolvedValue("confirm" as never);
    vi.spyOn(ElMessage, "success").mockImplementation(() => ({}) as never);
    vi.mocked(linkApi.rebuildLinkIndex).mockResolvedValue(null);
    const wrapper = await mountPage();
    await wrapper.find('[data-test="rebuild-index"]').trigger("click");
    await flushPromises();
    expect(linkApi.rebuildLinkIndex).toHaveBeenCalledTimes(1);
    expect(ElMessage.success).toHaveBeenCalledWith("已开始重建");
  });

  it("does not rebuild when the confirmation is cancelled", async () => {
    signIn("SUPER_ADMIN", "ALL");
    vi.mocked(linkApi.rebuildLinkIndex).mockClear();
    vi.spyOn(ElMessageBox, "confirm").mockRejectedValue("cancel");
    const wrapper = await mountPage();
    await wrapper.find('[data-test="rebuild-index"]').trigger("click");
    await flushPromises();
    expect(linkApi.rebuildLinkIndex).not.toHaveBeenCalled();
  });
});
