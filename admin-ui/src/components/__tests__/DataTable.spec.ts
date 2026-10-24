import { mount } from "@vue/test-utils";
import ElementPlus from "element-plus";
import { nextTick } from "vue";
import { describe, expect, it } from "vitest";
import { i18n } from "@/locales";
import DataTable from "../DataTable.vue";

const columns = [
  { key: "code", label: "编号", prop: "code" },
  { key: "name", label: "名称" },
  { key: "actions", label: "操作" }
];

describe("DataTable", () => {
  it("places custom tabs in the toolbar while keeping refresh and pagination", async () => {
    const wrapper = mount(DataTable, {
      props: { tableId: "toolbar-tabs", rows: [], columns, total: 0 },
      slots: { toolbar: '<nav data-test="list-tabs">t47_tabs</nav>' },
      global: { plugins: [ElementPlus, i18n] }
    });
    expect(wrapper.get(".data-table__toolbar [data-test=list-tabs]").text()).toBe("t47_tabs");
    await wrapper
      .get('button[aria-label="' + i18n.global.t("table.refresh") + '"]')
      .trigger("click");
    expect(wrapper.emitted("refresh")).toHaveLength(1);
    expect(wrapper.findComponent({ name: "ElPagination" }).exists()).toBe(true);
    wrapper.unmount();
  });

  it("keeps the action column fixed while data columns can fill a wide card", async () => {
    const wrapper = mount(DataTable, {
      props: {
        tableId: "flex-columns",
        rows: [{ id: "1", code: "KOL0001", name: "Ada" }],
        columns: [
          { key: "code", label: "编号", width: 120 },
          { key: "name", label: "名称", minWidth: 100 },
          { key: "actions", label: "操作", width: 80 }
        ],
        total: 1
      },
      global: { plugins: [ElementPlus, i18n] }
    });
    await nextTick();
    const rendered = wrapper.findAllComponents({ name: "ElTableColumn" });
    expect(rendered.map(column => [column.props("width"), column.props("minWidth")])).toEqual([
      ["", 120],
      ["", 100],
      [80, ""]
    ]);
    wrapper.unmount();
  });

  it("caps a mixed-height list at the last complete row", async () => {
    const host = document.createElement("div");
    document.body.append(host);
    const wrapper = mount(DataTable, {
      attachTo: host,
      props: {
        tableId: "mixed-rows",
        rows: Array.from({ length: 6 }, (_, index) => ({ id: String(index), code: String(index) })),
        columns,
        total: 6
      },
      global: { plugins: [ElementPlus, i18n] }
    });
    try {
      const parent = wrapper.element.parentElement as HTMLElement;
      Object.defineProperty(parent, "clientHeight", { value: 360, configurable: true });
      const toolbar = wrapper.get(".data-table__toolbar").element;
      const pager = wrapper.get(".data-table__pager").element;
      Object.defineProperty(toolbar, "offsetHeight", { value: 40, configurable: true });
      Object.defineProperty(pager, "offsetHeight", { value: 40, configurable: true });
      const header = wrapper.get(".el-table__header-wrapper").element;
      Object.defineProperty(header, "offsetHeight", { value: 40, configurable: true });
      const rows = wrapper.findAll(".el-table__body tbody tr");
      expect(rows).toHaveLength(6);
      [50, 80, 50, 80, 50, 80].forEach((height, index) =>
        Object.defineProperty(rows[index].element, "offsetHeight", {
          value: height,
          configurable: true
        })
      );
      window.dispatchEvent(new Event("resize"));
      await new Promise(resolve => requestAnimationFrame(resolve));
      await nextTick();
      expect(wrapper.findComponent({ name: "ElTable" }).props("maxHeight")).toBe(220);
    } finally {
      wrapper.unmount();
      host.remove();
    }
  });

  it("reserves space for filters when capping a long list", async () => {
    const host = document.createElement("div");
    const search = document.createElement("div");
    document.body.append(host);
    Object.defineProperty(search, "offsetHeight", { value: 100, configurable: true });

    const wrapper = mount(DataTable, {
      attachTo: host,
      props: { tableId: "measured", rows: [], columns, total: 0 },
      global: { plugins: [ElementPlus, i18n] }
    });
    try {
      const parent = wrapper.element.parentElement as HTMLElement;
      parent.prepend(search);
      Object.defineProperty(parent, "clientHeight", { value: 600, configurable: true });
      const toolbar = wrapper.get(".data-table__toolbar").element;
      const pager = wrapper.get(".data-table__pager").element;
      Object.defineProperty(toolbar, "offsetHeight", { value: 40, configurable: true });
      Object.defineProperty(pager, "offsetHeight", { value: 40, configurable: true });
      window.dispatchEvent(new Event("resize"));
      await new Promise(resolve => requestAnimationFrame(resolve));
      await nextTick();
      expect(wrapper.findComponent({ name: "ElTable" }).props("maxHeight")).toBe(420);
    } finally {
      wrapper.unmount();
      host.remove();
    }
  });

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

describe("DataTable export and selection", () => {
  const rows = [
    { id: "1", code: "KOL0001", name: "Ada" },
    { id: "2", code: "KOL0002", name: "Bob" }
  ];

  it("emits export from the toolbar button and hides it unless exportable", async () => {
    const wrapper = mount(DataTable, {
      props: { tableId: "exp", rows, columns, total: 2, exportable: true },
      global: { plugins: [ElementPlus, i18n] }
    });
    await wrapper.get("[data-test=export]").trigger("click");
    expect(wrapper.emitted("export")).toHaveLength(1);
    await wrapper.setProps({ exportable: false });
    expect(wrapper.find("[data-test=export]").exists()).toBe(false);
  });

  it("shows the batch bar for a current-page selection and clears it on new rows", async () => {
    const wrapper = mount(DataTable, {
      props: { tableId: "sel", rows, columns, total: 2, selectable: true },
      slots: { batch: '<button data-test="batch-action">go</button>' },
      global: { plugins: [ElementPlus, i18n] }
    });
    await nextTick();
    expect(wrapper.find("[data-test=batch-bar]").exists()).toBe(false);
    const table = wrapper.findComponent({ name: "ElTable" });
    (
      table.vm as unknown as { toggleRowSelection: (row: object, on: boolean) => void }
    ).toggleRowSelection(rows[0], true);
    await nextTick();
    expect(wrapper.get("[data-test=batch-bar]").text()).toContain("已选 1 项");
    expect(wrapper.text()).toContain("仅限当前页");
    expect(wrapper.find("[data-test=batch-action]").exists()).toBe(true);
    const emitted = wrapper.emitted("selectionChange")!;
    expect(emitted[emitted.length - 1][0]).toHaveLength(1);

    await wrapper.setProps({ rows: [{ id: "3", code: "KOL0003", name: "Cy" }] });
    await nextTick();
    expect(wrapper.find("[data-test=batch-bar]").exists()).toBe(false);
    const last = wrapper.emitted("selectionChange")!;
    expect(last[last.length - 1][0]).toEqual([]);
  });

  it("has no selection column unless selectable", () => {
    const wrapper = mount(DataTable, {
      props: { tableId: "nosel", rows, columns, total: 2 },
      global: { plugins: [ElementPlus, i18n] }
    });
    expect(wrapper.find(".el-table-column--selection").exists()).toBe(false);
  });
});

describe("DataTable expand rows", () => {
  const rows = [
    { id: "1", code: "KOL0001", name: "Ada" },
    { id: "2", code: "KOL0002", name: "Bob" }
  ];

  it("renders the expand slot of a row after opening it", async () => {
    const wrapper = mount(DataTable, {
      props: { tableId: "exp", rows, columns, total: 2, expandable: true },
      slots: { expand: '<p data-test="detail">lines</p>' },
      global: { plugins: [ElementPlus, i18n] }
    });
    await nextTick();
    await nextTick();
    expect(wrapper.find(".el-table__expand-column").exists()).toBe(true);
    expect(wrapper.find("[data-test=detail]").exists()).toBe(false);
    await wrapper.find(".el-table__expand-icon").trigger("click");
    await nextTick();
    await nextTick();
    expect(wrapper.find("[data-test=detail]").exists()).toBe(true);
    expect(wrapper.text()).toContain("lines");
  });

  it("opens every row without clicking when defaultExpandAll is set", async () => {
    const wrapper = mount(DataTable, {
      props: {
        tableId: "exp-all",
        rows,
        columns,
        total: 2,
        expandable: true,
        defaultExpandAll: true
      },
      slots: { expand: '<p data-test="detail">lines</p>' },
      global: { plugins: [ElementPlus, i18n] }
    });
    await nextTick();
    await nextTick();
    expect(wrapper.findAll("[data-test=detail]")).toHaveLength(2);
  });

  it("has no expand column unless expandable", () => {
    const wrapper = mount(DataTable, {
      props: { tableId: "noexp", rows, columns, total: 2 },
      global: { plugins: [ElementPlus, i18n] }
    });
    expect(wrapper.find(".el-table__expand-column").exists()).toBe(false);
  });

  it("applies rowClassName to body rows so a navigation can mark its record (task 59 R-02)", async () => {
    const wrapper = mount(DataTable, {
      props: {
        tableId: "row-class",
        rows,
        columns,
        total: 2,
        rowClassName: ({ row }: { row: object; rowIndex: number }) =>
          (row as { id: string }).id === "1" ? "row--located" : ""
      },
      global: { plugins: [ElementPlus, i18n] }
    });
    await nextTick();
    await nextTick();
    const located = wrapper.findAll("tr.row--located");
    expect(located).toHaveLength(1);
    expect(located[0].text()).toContain("KOL0001");
    wrapper.unmount();
  });
});
