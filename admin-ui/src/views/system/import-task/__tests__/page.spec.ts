import { flushPromises, mount } from "@vue/test-utils";
import ElementPlus, { ElMessage } from "element-plus";
import { createPinia, setActivePinia } from "pinia";
import { reactive } from "vue";
import { beforeEach, describe, expect, it, vi } from "vitest";
import * as taskApi from "@/api/task/task";
import { i18n } from "@/locales";
import ImportTaskPage from "../index.vue";

vi.mock("@/api/task/task");
vi.mock("vue-router", () => ({
  useRoute: () => ({ query: routeQuery })
}));
/** Reactive so that mutating it drives the page's route.query watcher (task 59 R-02). */
const routeQuery = reactive<Record<string, string | undefined>>({});

/**
 * 上传文件 (task 32 §2.3, contract 33 §3.5): the import task list with type and status
 * filters, the row counts and the error-file download.
 */
const task: taskApi.ImportTaskVO = {
  id: "9007199254740993",
  type: "PRODUCT_IMPORT",
  fileName: "products-2026-10-20.xlsx",
  status: "PARTIAL",
  totalRows: "120",
  successRows: "118",
  failedRows: "2",
  createTime: "2026-10-20T01:02:03Z",
  finishTime: "2026-10-20T01:03:03Z",
  operatorName: "Alice",
  hasErrorFile: true
};

async function mountPage(total = "1", items: taskApi.ImportTaskVO[] = [task]) {
  vi.mocked(taskApi.listImportTasks).mockResolvedValue({ items, total, page: 1, size: 20 });
  const wrapper = mount(ImportTaskPage, {
    global: { plugins: [ElementPlus, i18n] },
    attachTo: document.body
  });
  await flushPromises();
  return wrapper;
}

describe("import task page (task 32 §2.3)", () => {
  beforeEach(() => {
    document.body.innerHTML = "";
    localStorage.removeItem("tcrm.admin.table.import-tasks");
    vi.mocked(taskApi.downloadTaskErrorFile).mockReset();
    for (const key of Object.keys(routeQuery)) delete routeQuery[key];
    setActivePinia(createPinia());
  });

  it("locates the taskId from the route: filters by its type and status, highlights the row (R-02)", async () => {
    vi.mocked(taskApi.getTask).mockResolvedValue({
      id: "9007199254740999",
      taskType: "ORDER_IMPORT",
      status: "FAILED",
      total: 20,
      done: 20,
      failed: 3,
      message: null,
      hasErrorFile: false,
      createTime: "2026-10-23T01:00:00Z",
      startedAt: null,
      finishedAt: null
    });
    routeQuery.taskId = " 9007199254740999 ";
    const wrapper = await mountPage("1", [
      { ...task, id: "9007199254740999", type: "ORDER_IMPORT", status: "FAILED" }
    ]);
    expect(taskApi.getTask).toHaveBeenCalledWith("9007199254740999");
    expect(taskApi.listImportTasks).toHaveBeenLastCalledWith(
      1,
      20,
      expect.objectContaining({ type: "ORDER_IMPORT", status: "FAILED" })
    );
    expect(wrapper.findAll("tr.import-task-row--located")).toHaveLength(1);
    wrapper.unmount();
  });

  it("tells the user when the located task is not on the current page (R-02)", async () => {
    vi.mocked(taskApi.getTask).mockResolvedValue({
      id: "9007199254740999",
      taskType: "ORDER_IMPORT",
      status: "FAILED",
      total: 20,
      done: 20,
      failed: 3,
      message: null,
      hasErrorFile: false,
      createTime: "2026-10-23T01:00:00Z",
      startedAt: null,
      finishedAt: null
    });
    routeQuery.taskId = "9007199254740999";
    const info = vi.spyOn(ElMessage, "info").mockImplementation(() => undefined as never);
    const wrapper = await mountPage();
    expect(info).toHaveBeenCalledWith("该任务不在当前页，已按其类型与状态筛选");
    info.mockRestore();
    wrapper.unmount();
  });

  it("lists the file, counts, submitter and the finish time", async () => {
    const wrapper = await mountPage();
    const text = wrapper.text();
    expect(text).toContain("products-2026-10-20.xlsx");
    expect(text).toContain("商品导入");
    expect(text).toContain("部分失败");
    expect(text).toContain("120");
    expect(text).toContain("118");
    expect(text).toContain("2");
    expect(text).toContain("Alice");
    wrapper.unmount();
  });

  it("offers the error file download only when there is one", async () => {
    const clean = { ...task, hasErrorFile: false, status: "SUCCESS" } as taskApi.ImportTaskVO;
    const wrapper = await mountPage("2", [task, clean]);
    const downloads = document.body.querySelectorAll('[data-test="download-error"]');
    expect(downloads.length).toBe(1);
    wrapper.unmount();
  });

  it("passes the type and status filters to the list endpoint", async () => {
    const wrapper = await mountPage("0", []);
    const select = wrapper.getComponent({ name: "ElSelect" });
    select.vm.$emit("update:model-value", "ORDER_IMPORT");
    await flushPromises();
    wrapper.getComponent({ name: "ListSearch" }).vm.$emit("search");
    await flushPromises();
    expect(taskApi.listImportTasks).toHaveBeenLastCalledWith(
      1,
      20,
      expect.objectContaining({ type: "ORDER_IMPORT" })
    );
    wrapper.unmount();
  });
});
