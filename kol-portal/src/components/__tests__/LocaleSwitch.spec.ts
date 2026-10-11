import { mount } from "@vue/test-utils";
import ElementPlus, { ElSelect } from "element-plus";
import { createPinia, setActivePinia } from "pinia";
import { nextTick } from "vue";
import { beforeEach, describe, expect, it } from "vitest";
import { i18n } from "@/locales";
import { LOCALE_STORAGE_KEY } from "@/utils/locale";
import { useLocaleStore } from "@/stores/locale";
import LocaleSwitch from "../LocaleSwitch.vue";

describe("LocaleSwitch", () => {
  beforeEach(() => localStorage.clear());

  function mountSwitch() {
    const pinia = createPinia();
    setActivePinia(pinia);
    return mount(LocaleSwitch, { global: { plugins: [ElementPlus, i18n, pinia] } });
  }

  it("shows the current language and each language in its own name", async () => {
    const wrapper = mountSwitch();
    await nextTick();
    expect(wrapper.findComponent(ElSelect).props("modelValue")).toBe("en-US");
    expect(wrapper.find(".el-select__placeholder").text()).toBe("English");
    // The dropdown is teleported to body; its options carry the native names.
    expect(document.body.textContent).toContain("English");
    expect(document.body.textContent).toContain("中文");
  });

  it("switches the language when an option is picked", async () => {
    const wrapper = mountSwitch();
    await wrapper.findComponent(ElSelect).vm.$emit("update:model-value", "zh-CN");
    const store = useLocaleStore();
    expect(store.locale).toBe("zh-CN");
    expect(localStorage.getItem(LOCALE_STORAGE_KEY)).toBe("zh-CN");
    expect(document.documentElement.lang).toBe("zh-CN");
  });
});
