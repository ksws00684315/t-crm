import { DOMWrapper, flushPromises, mount, type VueWrapper } from "@vue/test-utils";
import ElementPlus, { ElMessage, ElMessageBox } from "element-plus";
import { createPinia, setActivePinia } from "pinia";
import { beforeEach, describe, expect, it, vi } from "vitest";
import * as kolApi from "@/api/kol/kol";
import * as linkApi from "@/api/link/link";
import * as kolItemApi from "@/api/product/kol-item";
import * as shopApi from "@/api/product/shop";
import type { DataScope, KolItemDetailVO, KolItemVO, KolVO, Role } from "@/api/types";
import { i18n } from "@/locales";
import { useUserStore } from "@/stores/user";
import { notifyError } from "@/utils/notify";
import KolItemPage from "../index.vue";

vi.mock("@/api/kol/kol");
vi.mock("@/api/link/link");
vi.mock("@/api/product/kol-item");
vi.mock("@/api/product/shop");
vi.mock("@/utils/notify", () => ({ notifyError: vi.fn() }));

const row: KolItemVO = {
  id: "1",
  linkCode: "KPL-ABCDEF123456",
  itemName: "A very long product name for the creator item cell",
  imageUrl: null,
  kolId: "11",
  kolCode: "KOL-TAA001",
  kolName: "Alice",
  targetShopId: "1",
  targetShopName: "旗舰店",
  targetShopCode: null,
  targetChannel: "WEIDIAN",
  targetItemId: "90010001",
  targetItemUrl: "https://weidian.com/item.html?itemID=90010001",
  sourceItemId: "80010001",
  sourceChannel: "WEIDIAN",
  currentRate: "0.1500",
  salesQuantity: "3",
  payAmount: "14.00",
  status: "ENABLED",
  cleanupStatus: "FAILED",
  createTime: "2026-10-21T00:00:00Z"
};

const detail: KolItemDetailVO = {
  ...row,
  sourceItemUrl: "https://weidian.com/item.html?itemID=80010001",
  visitCount: "1",
  totalPayAmount: "14.00",
  refundAmount: "3.50",
  cleanupTime: "2026-10-20T00:00:00Z",
  cleanupReason: "closed",
  cleanupFailReason: "rejected"
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

function dataRow(wrapper: VueWrapper, text: string) {
  const found = wrapper.findAll("tr").find(item => item.text().includes(text));
  if (!found) throw new Error(`missing row ${text}`);
  return found;
}

function findTest(wrapper: VueWrapper, testId: string) {
  const local = wrapper.find(`[data-test="${testId}"]`);
  if (local.exists()) return local;
  const element = document.body.querySelector(`[data-test="${testId}"]`);
  if (!element) throw new Error(`missing ${testId}`);
  return new DOMWrapper(element);
}

async function mountPage(items: KolItemVO[] = [row]) {
  vi.mocked(shopApi.listShops).mockResolvedValue([
    {
      id: "1",
      shopName: "旗舰店",
      weidianShopId: "100",
      status: 1,
      copyTarget: false,
      remark: null,
      createTime: "2026-10-01T00:00:00Z",
      updateTime: "2026-10-01T00:00:00Z"
    }
  ]);
  vi.mocked(kolItemApi.listKolItems).mockResolvedValue({
    items,
    total: String(items.length),
    page: 1,
    size: 20
  });
  const wrapper = mount(KolItemPage, { global: { plugins: [ElementPlus, i18n] } });
  await flushPromises();
  return wrapper;
}

describe("Kol item page", () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    i18n.global.locale.value = "zh-CN";
    vi.clearAllMocks();
  });

  it("loads the list on open and shows the task columns", async () => {
    signIn("SUPER_ADMIN", "ALL");
    const wrapper = await mountPage();
    expect(kolItemApi.listKolItems).toHaveBeenCalledWith({
      page: 1,
      size: 20,
      linkCode: undefined,
      kolKeyword: undefined,
      shopId: undefined
    });
    expect(wrapper.text()).toContain("链接编码");
    expect(wrapper.text()).toContain("商品");
    expect(wrapper.text()).toContain("支付金额");
    expect(wrapper.text()).toContain("清理状态");
    expect(wrapper.text()).toContain("KPL-ABCDEF123456");
    expect(wrapper.text()).toContain("Alice（KOL-TAA001）");
    expect(wrapper.text()).toContain("14.00");
    expect(wrapper.text()).toContain("清理失败");
    expect(wrapper.find('[data-test="image-placeholder"]').exists()).toBe(true);
    expect(wrapper.find('[data-test="copy"]').exists()).toBe(true);
  });

  it("shows English headers", async () => {
    signIn("SUPER_ADMIN", "ALL");
    i18n.global.locale.value = "en-US";
    const wrapper = await mountPage();
    expect(wrapper.text()).toContain("Link Code");
    expect(wrapper.text()).toContain("Pay Amount");
    expect(wrapper.text()).toContain("Cleanup failed");
    expect(wrapper.text()).toContain("Weidian");
  });

  it("sends the link code, selected creator code and shop", async () => {
    signIn("STAFF", "ALL", ["product:view"]);
    vi.mocked(kolApi.listKols).mockResolvedValue({
      items: [{ id: "11", name: "Alice", kolCode: "KOL-TAA001" } as KolVO],
      total: "1",
      page: 1,
      size: 20
    });
    const wrapper = await mountPage();
    await wrapper.find('[data-test="link-code"]').setValue("kpl-abcdef123456");
    const [kolSelect, shopSelect] = wrapper.findAllComponents({ name: "ElSelect" });
    await kolSelect.props("remoteMethod")("alice");
    await flushPromises();
    await kolSelect.vm.$emit("update:modelValue", "11");
    await shopSelect.vm.$emit("update:modelValue", "1");
    await wrapper.find('[data-test="search"]').trigger("click");
    await flushPromises();
    expect(kolItemApi.listKolItems).toHaveBeenLastCalledWith(
      expect.objectContaining({
        page: 1,
        linkCode: "kpl-abcdef123456",
        kolKeyword: "KOL-TAA001",
        shopId: "1"
      })
    );
  });

  it("keeps the selected creator after the remote list is cleared", async () => {
    signIn("STAFF", "ALL", ["product:view"]);
    vi.mocked(kolApi.listKols).mockResolvedValue({
      items: [{ id: "11", name: "Alice", kolCode: "KOL-TAA001" } as KolVO],
      total: "1",
      page: 1,
      size: 20
    });
    const wrapper = await mountPage();
    const [kolSelect] = wrapper.findAllComponents({ name: "ElSelect" });
    await kolSelect.props("remoteMethod")("alice");
    await flushPromises();
    await kolSelect.vm.$emit("update:modelValue", "11");
    await kolSelect.props("remoteMethod")("");
    await flushPromises();
    await wrapper.find('[data-test="search"]').trigger("click");
    await flushPromises();
    expect(kolItemApi.listKolItems).toHaveBeenLastCalledWith(
      expect.objectContaining({ page: 1, kolKeyword: "KOL-TAA001" })
    );
  });

  it("reloads the first page on reset", async () => {
    signIn("SUPER_ADMIN", "ALL");
    const wrapper = await mountPage();
    await wrapper.find('[data-test="link-code"]').setValue("KPL-ABCDEF123456");
    await wrapper.find('[data-test="search"]').trigger("click");
    await flushPromises();
    await wrapper.find('[data-test="reset"]').trigger("click");
    await flushPromises();
    expect(kolItemApi.listKolItems).toHaveBeenLastCalledWith(
      expect.objectContaining({ page: 1, linkCode: undefined, kolKeyword: undefined })
    );
  });

  it("opens the new item link and the detail drawer", async () => {
    signIn("SUPER_ADMIN", "ALL");
    vi.mocked(kolItemApi.getKolItem).mockResolvedValue(detail);
    const open = vi.spyOn(window, "open").mockImplementation(() => null);
    const wrapper = await mountPage();
    const line = dataRow(wrapper, row.linkCode);
    await line.get('[data-test="open-target"]').trigger("click");
    expect(open).toHaveBeenCalledWith(row.targetItemUrl, "_blank", "noopener");
    await line.get('[data-test="view"]').trigger("click");
    await flushPromises();
    expect(kolItemApi.getKolItem).toHaveBeenCalledWith("1");
    const drawer = findTest(wrapper, "detail-drawer");
    expect(drawer.find('[data-test="detail-image"]').exists()).toBe(true);
    expect(drawer.text()).toContain("访问次数");
    expect(drawer.text()).toContain("累计支付金额");
    expect(drawer.text()).toContain("累计退款");
    expect(drawer.text()).toContain("3.50");
    expect(drawer.text()).toContain("closed");
    expect(drawer.text()).toContain("rejected");
    await findTest(wrapper, "open-source").trigger("click");
    expect(open).toHaveBeenCalledWith(detail.sourceItemUrl, "_blank", "noopener");
    open.mockRestore();
    wrapper.unmount();
  });

  it("reports a list failure and closes the drawer when detail fails", async () => {
    signIn("STAFF", "ALL", ["product:view"]);
    vi.mocked(shopApi.listShops).mockResolvedValue([]);
    vi.mocked(kolItemApi.listKolItems).mockRejectedValue(new Error("down"));
    mount(KolItemPage, { global: { plugins: [ElementPlus, i18n] } });
    await flushPromises();
    expect(notifyError).toHaveBeenCalled();

    vi.mocked(kolItemApi.listKolItems).mockResolvedValue({
      items: [row],
      total: "1",
      page: 1,
      size: 20
    });
    vi.mocked(kolItemApi.getKolItem).mockRejectedValue(new Error("missing"));
    const wrapper = await mountPage();
    await dataRow(wrapper, row.linkCode).get('[data-test="view"]').trigger("click");
    await flushPromises();
    expect(wrapper.find('[data-test="detail-drawer"]').exists()).toBe(false);
    expect(document.body.querySelector('[data-test="detail-drawer"]')).toBeNull();
    wrapper.unmount();
  });

  it("shows the shop code when the API provides one", async () => {
    signIn("SUPER_ADMIN", "ALL");
    const wrapper = await mountPage([{ ...row, targetShopCode: "S001" }]);
    expect(wrapper.text()).toContain("旗舰店（S001）");
  });

  it("keeps view for a BD and hides import, commission and rebuild", async () => {
    signIn("STAFF", "OWN", ["product:view"]);
    const wrapper = await mountPage();
    expect(kolItemApi.listKolItems).toHaveBeenCalled();
    expect(wrapper.find('[data-test="view"]').exists()).toBe(true);
    expect(wrapper.find('[data-test="links"]').exists()).toBe(true);
    expect(wrapper.find('[data-test="commission"]').exists()).toBe(false);
    expect(wrapper.find('[data-test="import"]').exists()).toBe(false);
    expect(wrapper.find('[data-test="rebuild-index"]').exists()).toBe(false);
    expect(wrapper.find('[data-test="upload-promo-sheet"]').exists()).toBe(false);
  });

  it("shows rebuild only to the super administrator", async () => {
    signIn("SUPER_ADMIN", "ALL");
    expect((await mountPage()).find('[data-test="rebuild-index"]').exists()).toBe(true);
  });

  it("shows mapping import for product:edit and hides it without that code", async () => {
    signIn("STAFF", "ALL", ["product:edit"]);
    expect((await mountPage()).find('[data-test="import"]').exists()).toBe(true);
    signIn("STAFF", "ALL", ["product:view"]);
    expect((await mountPage()).find('[data-test="import"]').exists()).toBe(false);
  });

  it("shows export on load only when the account has data:export", async () => {
    signIn("STAFF", "ALL", ["product:view", "data:export"]);
    expect((await mountPage()).find('[data-test="export"]').exists()).toBe(true);
    signIn("STAFF", "ALL", ["product:view"]);
    expect((await mountPage()).find('[data-test="export"]').exists()).toBe(false);
  });

  it("shows the promo sheet upload for an account that can edit creators", async () => {
    signIn("STAFF", "ALL", ["product:view", "kol:edit"]);
    const wrapper = await mountPage();
    expect(wrapper.find('[data-test="upload-promo-sheet"]').exists()).toBe(true);
    expect(wrapper.find('[data-test="commission"]').exists()).toBe(true);
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
    vi.spyOn(ElMessageBox, "confirm").mockRejectedValue("cancel");
    const wrapper = await mountPage();
    await wrapper.find('[data-test="rebuild-index"]').trigger("click");
    await flushPromises();
    expect(linkApi.rebuildLinkIndex).not.toHaveBeenCalled();
  });
});
