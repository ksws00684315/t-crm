import { mount } from "@vue/test-utils";
import ElementPlus from "element-plus";
import { nextTick } from "vue";
import { describe, expect, it } from "vitest";
import { i18n } from "@/locales";
import DataTable from "../DataTable.vue";

const columns = [
  { key: "code", label: "编号", prop: "code", mono: true },
  { key: "name", label: "名称" },
  { key: "actions", label: "操作" }
];

describe("DataTable", () => {
  it("shows the page range and an Element Plus pagination", async () => {
    const wrapper = mount(DataTable, {
      props: {
        tableId: "demo",
        rows: [{ id: "1", code: "KOL0001", name: "Ada" }],
        columns,
        total: 20,
        page: 1,
        size: 10
      },
      global: { plugins: [ElementPlus, i18n] }
    });
    await nextTick();
    await nextTick();
    expect(wrapper.text()).toContain("编号");
    expect(wrapper.text()).toContain("KOL0001");
    const pagination = wrapper.findComponent({ name: "ElPagination" });
    expect(pagination.props("total")).toBe(20);
  });

  it("shows the filtered empty state and a clear action", async () => {
    const wrapper = mount(DataTable, {
      props: {
        tableId: "empty",
        rows: [],
        columns,
        total: 0,
        filterCount: 2
      },
      global: { plugins: [ElementPlus, i18n] }
    });
    expect(wrapper.text()).toContain("没有符合条件的数据 · 已应用 2 个筛选条件");
    await wrapper.get("button").trigger("click");
    const clear = wrapper.findAll("button").find(button => button.text().includes("清除筛选"));
    expect(clear).toBeTruthy();
    await clear!.trigger("click");
    expect(wrapper.emitted("clearFilters")).toBeTruthy();
  });

  it("omits the pager when the list is not paged", () => {
    const wrapper = mount(DataTable, {
      props: {
        tableId: "shops",
        rows: [],
        columns,
        total: 3,
        paged: false
      },
      global: { plugins: [ElementPlus, i18n] }
    });
    expect(wrapper.findComponent({ name: "ElPagination" }).exists()).toBe(false);
    expect(wrapper.text()).toContain("共 3 条");
  });
});
