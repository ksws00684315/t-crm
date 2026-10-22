import { flushPromises, mount } from "@vue/test-utils";
import ElementPlus, { ElMessage, ElMessageBox } from "element-plus";
import { createPinia, setActivePinia } from "pinia";
import { beforeEach, describe, expect, it, vi } from "vitest";
import * as linkApi from "@/api/link/link";
import * as kolItemApi from "@/api/product/kol-item";
import * as shopApi from "@/api/product/shop";
import type { DataScope, KolItemVO, Role } from "@/api/types";
import { i18n } from "@/locales";
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
  itemName: null,
  source: "IMPORT",
  validFrom: "2026-10-05T01:02:03Z",
  validTo: null
};

function signIn(role: Role, dataScope: DataScope, permissions: string[] = []) {
  useUserStore().user = {
    id: "1",
    username: "me",
    nickname: "Me",
    email: null,
    role,
    postTemplate: role === "STAFF" ? "OPERATOR" : null,
    permissions,
    dataScope,
    tenantId: "1",
    tenantName: "默认公司",
    status: 1,
    lastLoginTime: null,
    createTime: "2026-10-01T00:00:00Z"
  };
}

async function mountPage() {
  vi.mocked(shopApi.listShops).mockResolvedValue([
    {
      id: "1",
      shopCode: "SHOP-100001",
      shopName: "旗舰店",
      channel: "WEIDIAN",
      status: 1
    }
  ]);
  vi.mocked(kolItemApi.listKolItems).mockResolvedValue({
    items: [row],
    total: "1",
    page: 1,
    size: 20
  });
  const wrapper = mount(KolItemPage, { global: { plugins: [ElementPlus, i18n] } });
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
    signIn("STAFF", "ALL", ["product:view"]);
    const wrapper = await mountPage();
    await wrapper.find('[data-test="search"]').trigger("click");
    await flushPromises();
    expect(ElMessage.warning).toHaveBeenCalledWith("请输入达人编号、原商品编码或专属商品 ID 查询");
    expect(kolItemApi.listKolItems).not.toHaveBeenCalled();
    expect(wrapper.find('[data-test="search-tip"]').exists()).toBe(true);
  });

  it("filters by source and marks mappings without an original product", async () => {
    signIn("SUPER_ADMIN", "ALL");
    const wrapper = await mountPage();
    vi.mocked(kolItemApi.listKolItems).mockResolvedValue({
      items: [
        row,
        {
          ...row,
          id: "2",
          productId: null,
          productCode: null,
          productName: null,
          itemName: "快照商品",
          source: "SHEET_UPLOAD"
        }
      ],
      total: "2",
      page: 1,
      size: 20
    });
    await wrapper.find('[data-test="kol-code"]').setValue("KOL0001");
    const sourceSelect = wrapper.findComponent('[data-test="source-filter"]');
    // The select's v-model is driven by its update:modelValue event.
    (sourceSelect as unknown as { vm: { $emit: (event: string, value: string) => void } }).vm.$emit(
      "update:modelValue",
      "SHEET_UPLOAD"
    );
    await flushPromises();
    await wrapper.find('[data-test="search"]').trigger("click");
    await flushPromises();
    expect(kolItemApi.listKolItems).toHaveBeenCalledWith(
      expect.objectContaining({ source: "SHEET_UPLOAD" })
    );
    expect(wrapper.text()).toContain("未关联原商品");
    expect(wrapper.text()).toContain("快照商品");
    expect(wrapper.text()).toContain("上传推广表");
  });

  it("includes COPY in the source filter and shows the copy label", async () => {
    signIn("SUPER_ADMIN", "ALL");
    const wrapper = await mountPage();
    vi.mocked(kolItemApi.listKolItems).mockResolvedValue({
      items: [{ ...row, id: "3", source: "COPY" }],
      total: "1",
      page: 1,
      size: 20
    });
    await wrapper.find('[data-test="kol-code"]').setValue("KOL0001");
    const sourceSelect = wrapper.findComponent('[data-test="source-filter"]');
    (sourceSelect as unknown as { vm: { $emit: (event: string, value: string) => void } }).vm.$emit(
      "update:modelValue",
      "COPY"
    );
    await flushPromises();
    await wrapper.find('[data-test="search"]').trigger("click");
    await flushPromises();
    expect(kolItemApi.listKolItems).toHaveBeenCalledWith(
      expect.objectContaining({ source: "COPY" })
    );
    expect(wrapper.text()).toContain("复制");
  });

  it("queries when a precision filter is set and shows page total", async () => {
    signIn("STAFF", "ALL", ["product:view"]);
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
      source: undefined,
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

  it("shows rebuild only to the super administrator", async () => {
    signIn("SUPER_ADMIN", "ALL");
    expect((await mountPage()).find('[data-test="rebuild-index"]').exists()).toBe(true);
  });

  it("hides rebuild from a sub-account that can edit or only view mappings", async () => {
    signIn("STAFF", "ALL", ["product:view", "product:edit"]);
    expect((await mountPage()).find('[data-test="rebuild-index"]').exists()).toBe(false);
    signIn("STAFF", "ALL", ["product:view"]);
    expect((await mountPage()).find('[data-test="rebuild-index"]').exists()).toBe(false);
  });

  it("shows mapping import for product:edit and hides it without that code", async () => {
    signIn("STAFF", "ALL", ["product:edit"]);
    expect((await mountPage()).find('[data-test="import"]').exists()).toBe(true);
    signIn("STAFF", "ALL", ["product:view"]);
    expect((await mountPage()).find('[data-test="import"]').exists()).toBe(false);
  });

  async function search(wrapper: Awaited<ReturnType<typeof mountPage>>) {
    await wrapper.find('[data-test="kol-code"]').setValue("KOL0001");
    await wrapper.find('[data-test="search"]').trigger("click");
    await flushPromises();
  }

  it("shows export after a query only when the account has data:export", async () => {
    signIn("STAFF", "ALL", ["product:view", "data:export"]);
    const allowed = await mountPage();
    expect(allowed.find('[data-test="export"]').exists()).toBe(false);
    await search(allowed);
    expect(allowed.find('[data-test="export"]').exists()).toBe(true);

    signIn("STAFF", "ALL", ["product:view"]);
    const denied = await mountPage();
    await search(denied);
    expect(denied.find('[data-test="export"]').exists()).toBe(false);
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
