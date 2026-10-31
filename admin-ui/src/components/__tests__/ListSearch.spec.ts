import { mount } from "@vue/test-utils";
import ElementPlus from "element-plus";
import { defineComponent, ref } from "vue";
import { describe, expect, it } from "vitest";
import { i18n } from "@/locales";
import ListSearch from "../ListSearch.vue";

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
  it("places the title and page actions inside the search card header", () => {
    const wrapper = mount(ListSearch, {
      props: { title: "达人列表" },
      slots: { actions: '<button data-test="page-action">新增</button>' },
      global: { plugins: [ElementPlus, i18n] }
    });
    expect(wrapper.get(".list-search__header h1").text()).toBe("达人列表");
    expect(wrapper.find(".list-search__header [data-test=page-action]").exists()).toBe(true);
    expect(wrapper.find(".list-search__actions [data-test=page-action]").exists()).toBe(false);
    wrapper.unmount();
  });

  it("can preserve a separately approved list without a new title", () => {
    const wrapper = mount(ListSearch, {
      props: { title: "订单列表", hideTitle: true },
      slots: { actions: '<button data-test="page-action">导入</button>' },
      global: { plugins: [ElementPlus, i18n] }
    });
    expect(wrapper.find(".list-search__header").exists()).toBe(false);
    expect(wrapper.find(".list-search__actions [data-test=page-action]").exists()).toBe(true);
    wrapper.unmount();
  });

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
