import { flushPromises, mount } from "@vue/test-utils";
import ElementPlus from "element-plus";
import { createPinia, setActivePinia } from "pinia";
import { createMemoryHistory, createRouter } from "vue-router";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { getKolItems } from "@/api/items";
import { enUS, i18n } from "@/locales";
import { routes } from "@/router/routes";
import { useLocaleStore } from "@/stores/locale";
import type { LocaleId } from "@/utils/locale";
import ItemsPage from "../index.vue";

vi.mock("@/api/items", () => ({ getKolItems: vi.fn() }));

const item = {
  weidianItemId: "1111111111",
  itemName: "Silver Ring",
  imageUrl: "https://img.example.com/ring.png",
  transitUrl: "https://t.example.com/i/1111111111",
  createTime: "2026-10-18T00:00:00Z"
};

async function mountPage(locale: LocaleId = "en-US") {
  const pinia = createPinia();
  setActivePinia(pinia);
  useLocaleStore().setLocale(locale);
  const router = createRouter({ history: createMemoryHistory(), routes });
  await router.push("/items");
  const wrapper = mount(ItemsPage, {
    attachTo: document.body,
    global: { plugins: [ElementPlus, i18n, pinia, router] }
  });
  await flushPromises();
  return wrapper;
}

beforeEach(() => {
  localStorage.clear();
  vi.resetAllMocks();
  vi.mocked(getKolItems).mockResolvedValue({ items: [], total: 0, page: 1, size: 20 });
});

describe("items page", () => {
  it("lists the creator's items with the transit link copy button", async () => {
    const writeText = vi.fn().mockResolvedValue(undefined);
    Object.defineProperty(navigator, "clipboard", { value: { writeText }, configurable: true });
    vi.mocked(getKolItems).mockResolvedValue({ items: [item], total: 1, page: 1, size: 20 });
    const wrapper = await mountPage();
    expect(getKolItems).toHaveBeenCalledWith({ page: 1, size: 20 });
    expect(wrapper.text()).toContain("Silver Ring");
    expect(wrapper.text()).toContain("1111111111");
    const cards = wrapper.findAll('[data-test="item-card"]');
    expect(cards).toHaveLength(1);
    await cards[0].find('[data-test="item-copy"]').trigger("click");
    await flushPromises();
    expect(writeText).toHaveBeenCalledWith("https://t.example.com/i/1111111111");
    expect(wrapper.text()).toContain("Copied");
  });

  it("searches by keyword from the first page", async () => {
    vi.mocked(getKolItems).mockResolvedValue({ items: [], total: 0, page: 1, size: 20 });
    const wrapper = await mountPage();
    await wrapper.find('[data-test="items-keyword"]').setValue("Golden");
    await wrapper.find('[data-test="search"]').trigger("click");
    await flushPromises();
    expect(getKolItems).toHaveBeenLastCalledWith({ keyword: "Golden", page: 1, size: 20 });
  });

  it("shows the English empty state explaining the pending setup", async () => {
    const wrapper = await mountPage();
    expect(wrapper.find('[data-test="items-empty"]').exists()).toBe(true);
    expect(wrapper.text()).toContain(
      "Your items appear here once your manager finishes the promotion setup."
    );
  });

  it("shows the Chinese empty state in Chinese", async () => {
    const wrapper = await mountPage("zh-CN");
    expect(wrapper.text()).toContain("商务完成推广配置后，你的商品会显示在这里。");
  });

  it("keeps a visible empty state for the phone card list", async () => {
    const wrapper = await mountPage();
    // The table's empty slot disappears together with the table on phones, so the card list
    // carries its own copy (spec 10 §3: the empty state must stay visible inside the card).
    const mobileEmpty = wrapper.find('[data-test="items-empty-mobile"]');
    expect(mobileEmpty.exists()).toBe(true);
    expect(mobileEmpty.text()).toContain(enUS.items.empty);
    expect(wrapper.find(".item-list").exists()).toBe(false);
  });

  it("does not show the phone empty state when loading fails", async () => {
    vi.mocked(getKolItems).mockRejectedValue(new Error("network"));
    const wrapper = await mountPage();
    // A failed request is not an empty list (spec 13 §4.3): only the error alert remains.
    expect(wrapper.text()).toContain(enUS.items.loadFailed);
    expect(wrapper.find('[data-test="items-empty-mobile"]').exists()).toBe(false);
  });

  it("falls back to the snapshot name when the product has none", async () => {
    vi.mocked(getKolItems).mockResolvedValue({
      items: [
        {
          weidianItemId: "3333333333",
          itemName: "Golden Ring",
          imageUrl: null,
          transitUrl: "https://t.example.com/i/3333333333",
          createTime: "2026-10-18T00:00:00Z"
        }
      ],
      total: 1,
      page: 1,
      size: 20
    });
    const wrapper = await mountPage();
    expect(wrapper.text()).toContain("Golden Ring");
    // No image: the card renders the initial instead of a broken image.
    expect(wrapper.find(".item-card__image--empty").exists()).toBe(true);
  });

  it("lists the item table columns with the copy action in the actions column", async () => {
    vi.mocked(getKolItems).mockResolvedValue({ items: [item], total: 1, page: 1, size: 20 });
    const wrapper = await mountPage();
    const table = wrapper.find(".items-table").text();
    expect(table).toContain(enUS.items.colItem);
    expect(table).toContain(enUS.items.colId);
    expect(table).toContain(enUS.items.colCreated);
    expect(table).toContain(enUS.common.actions);
    expect(wrapper.find('[data-test="row-actions"] [data-test="item-copy"]').exists()).toBe(true);
    wrapper.unmount();
  });
});
