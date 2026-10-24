import { mount } from "@vue/test-utils";
import ElementPlus from "element-plus";
import { defineComponent, ref } from "vue";
import { beforeEach, describe, expect, it } from "vitest";
import { i18n } from "@/locales";
import ListSearch from "../ListSearch.vue";

/** The admin specs assert Chinese copy; the portal defaults to English, so pin the language here. */
beforeEach(() => {
  i18n.global.locale.value = "zh-CN";
});

const Host = defineComponent({
  components: { ListSearch },
  setup() {
    const searches = ref(0);
    const resets = ref(0);
    const removed = ref("");
    const chips = [{ key: "country", label: "国家", text: "美国" }];
    return { searches, resets, removed, chips };
  },
  template: `
    <ListSearch
      :chips="chips"
      has-more
      @search="searches += 1"
      @reset="resets += 1"
      @remove="removed = $event"
    >
      <template #primary>
        <el-input data-test="keyword" />
        <el-input data-test="country" />
      </template>
      <template #more><span data-test="more-field">more</span></template>
    </ListSearch>
  `
});

describe("ListSearch", () => {
  it("searches on a real Enter key in either of two inputs", async () => {
    const wrapper = mount(Host, { global: { plugins: [ElementPlus, i18n] } });
    expect(wrapper.text()).toContain("国家：美国");
    expect(wrapper.get("[data-test=search-more]").attributes("style") ?? "").toContain(
      "display: none"
    );

    await wrapper.get("input[data-test=keyword]").trigger("keyup.enter");
    expect(wrapper.vm.searches).toBe(1);

    await wrapper.get("input[data-test=country]").trigger("keyup.enter");
    expect(wrapper.vm.searches).toBe(2);

    await wrapper.get("[data-test=search]").trigger("click");
    expect(wrapper.vm.searches).toBe(3);

    await wrapper.get("[data-test=search-expand]").trigger("click");
    expect(wrapper.get("[data-test=search-more]").attributes("style") ?? "").not.toContain(
      "display: none"
    );

    await wrapper.get("[data-test=search-chip] .el-tag__close").trigger("click");
    expect(wrapper.vm.removed).toBe("country");

    await wrapper.get("[data-test=search-clear]").trigger("click");
    expect(wrapper.vm.resets).toBe(1);
  });

  it("does not search when Enter is pressed inside a textarea", async () => {
    const TextAreaHost = defineComponent({
      components: { ListSearch },
      setup: () => ({ searches: ref(0) }),
      template: `
        <ListSearch @search="searches += 1">
          <template #primary>
            <textarea data-test="notes" />
            <el-input data-test="keyword" />
          </template>
        </ListSearch>
      `
    });
    const wrapper = mount(TextAreaHost, { global: { plugins: [ElementPlus, i18n] } });
    await wrapper.get("[data-test=notes]").trigger("keyup.enter");
    expect(wrapper.vm.searches).toBe(0);
  });
});
