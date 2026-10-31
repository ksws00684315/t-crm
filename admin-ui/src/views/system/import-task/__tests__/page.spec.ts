import { flushPromises, mount, type VueWrapper } from "@vue/test-utils";
import ElementPlus, { ElMessage } from "element-plus";
import { createPinia, setActivePinia } from "pinia";
import { nextTick, reactive } from "vue";
import { beforeEach, describe, expect, it, vi } from "vitest";
import {
  downloadOrderImportTemplate,
  downloadRefundImportTemplate,
  uploadOrderImport,
  uploadRefundImport
} from "@/api/order/order";
import { downloadKolItemTemplate, uploadKolItemImport } from "@/api/product/kol-item";
import { downloadProductTemplate, uploadProductImport } from "@/api/product/product";
import * as taskApi from "@/api/task/task";
import type { Role, DataScope } from "@/api/types";
import ImportDialog from "@/components/ImportDialog.vue";
import { i18n } from "@/locales";
import { useUserStore } from "@/stores/user";
import { saveBlob } from "@/utils/download";
import ImportTaskPage from "../index.vue";

vi.mock("@/api/task/task");
vi.mock("@/utils/download", () => ({ saveBlob: vi.fn() }));
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
    expect(info).toHaveBeenCalledWith("该任务不在当前页，已按其类型与状态筛选。");
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

  it("saves the error workbook when the row action is clicked", async () => {
    const file = { blob: new Blob(["xlsx"]), fileName: "task-9007199254740993-errors.xlsx" };
    vi.mocked(taskApi.downloadTaskErrorFile).mockResolvedValue(file);
    const wrapper = await mountPage();
    await wrapper.get('[data-test="download-error"]').trigger("click");
    await flushPromises();
    expect(taskApi.downloadTaskErrorFile).toHaveBeenCalledWith(task.id);
    expect(saveBlob).toHaveBeenCalledWith(file.blob, file.fileName);
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

/**
 * Upload entry on the same page (task 77). Options follow productAbilities / orderAbilities.
 */
function signIn(role: Role, permissions: string[], dataScope: DataScope | null = "ALL") {
  useUserStore().user = {
    id: "1",
    username: "t77_me",
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

function option(type: string): Element | null {
  return document.body.querySelector(`[data-test="upload-type-${type}"]`);
}

async function openPicker(wrapper: VueWrapper) {
  await wrapper.get('[data-test="upload-entry"]').trigger("click");
  await nextTick();
}

async function choose(wrapper: VueWrapper, type: string) {
  await openPicker(wrapper);
  expect(option(type)).not.toBeNull();
  wrapper.getComponent({ name: "ElRadioGroup" }).vm.$emit("update:modelValue", type);
  await nextTick();
  document.body.querySelector<HTMLButtonElement>('[data-test="upload-type-confirm"]')?.click();
  await flushPromises();
}

describe("upload entry (task 77)", () => {
  beforeEach(() => {
    document.body.innerHTML = "";
    localStorage.removeItem("tcrm.admin.table.import-tasks");
    for (const key of Object.keys(routeQuery)) delete routeQuery[key];
    setActivePinia(createPinia());
  });

  it("hides the button when the account cannot import anything", async () => {
    const anonymous = await mountPage();
    expect(anonymous.find('[data-test="upload-entry"]').exists()).toBe(false);
    anonymous.unmount();

    signIn("STAFF", ["product:view"]);
    const viewOnly = await mountPage();
    expect(viewOnly.find('[data-test="upload-entry"]').exists()).toBe(false);
    viewOnly.unmount();

    signIn("PLATFORM_ADMIN", [], null);
    const platform = await mountPage();
    expect(platform.find('[data-test="upload-entry"]').exists()).toBe(false);
    platform.unmount();
  });

  it("offers product and mapping imports to product:edit, and not creator import", async () => {
    signIn("STAFF", ["product:edit"]);
    const wrapper = await mountPage();
    await openPicker(wrapper);
    expect(option("PRODUCT_IMPORT")).not.toBeNull();
    expect(option("MAPPING_IMPORT")).not.toBeNull();
    expect(option("ORDER_IMPORT")).toBeNull();
    expect(option("REFUND_IMPORT")).toBeNull();
    expect(option("KOL_IMPORT")).toBeNull();
    expect(document.body.textContent).toContain("商品导入");
    expect(document.body.textContent).toContain("对应关系导入");
    wrapper.unmount();
  });

  it("offers only order and refund imports to order:manage", async () => {
    signIn("STAFF", ["order:manage"], "OWN");
    const wrapper = await mountPage();
    await openPicker(wrapper);
    expect(option("ORDER_IMPORT")).not.toBeNull();
    expect(option("REFUND_IMPORT")).not.toBeNull();
    expect(option("PRODUCT_IMPORT")).toBeNull();
    expect(option("MAPPING_IMPORT")).toBeNull();
    wrapper.unmount();
  });

  it("passes each business page's dialog parameters and reloads after finished", async () => {
    signIn("SUPER_ADMIN", []);
    const wrapper = await mountPage();
    await openPicker(wrapper);
    for (const type of ["PRODUCT_IMPORT", "MAPPING_IMPORT", "ORDER_IMPORT", "REFUND_IMPORT"]) {
      expect(option(type)).not.toBeNull();
    }
    expect(option("KOL_IMPORT")).toBeNull();

    await choose(wrapper, "PRODUCT_IMPORT");
    let dialog = wrapper.getComponent(ImportDialog);
    expect(dialog.props()).toMatchObject({
      taskType: "PRODUCT_IMPORT",
      acceptCsv: false,
      requireDataCutoff: false,
      templateFileName: "product-import-template.xlsx",
      title: "批量导入原商品",
      limitHint: "单个文件不超过 20MB、20000 行",
      downloadTemplate: downloadProductTemplate,
      upload: uploadProductImport
    });

    await choose(wrapper, "MAPPING_IMPORT");
    dialog = wrapper.getComponent(ImportDialog);
    expect(dialog.props()).toMatchObject({
      taskType: "MAPPING_IMPORT",
      acceptCsv: false,
      requireDataCutoff: false,
      templateFileName: "kol-item-import-template.xlsx",
      title: "批量导入对应关系",
      downloadTemplate: downloadKolItemTemplate,
      upload: uploadKolItemImport
    });

    await choose(wrapper, "ORDER_IMPORT");
    dialog = wrapper.getComponent(ImportDialog);
    expect(dialog.props()).toMatchObject({
      taskType: "ORDER_IMPORT",
      acceptCsv: true,
      requireDataCutoff: true,
      templateFileName: "订单导入模板.xlsx",
      title: "导入订单",
      limitHint: "单个文件不超过 20MB、20000 行",
      downloadTemplate: downloadOrderImportTemplate,
      upload: uploadOrderImport
    });

    await choose(wrapper, "REFUND_IMPORT");
    dialog = wrapper.getComponent(ImportDialog);
    expect(dialog.props()).toMatchObject({
      taskType: "REFUND_IMPORT",
      acceptCsv: true,
      requireDataCutoff: true,
      templateFileName: "退款导入模板.xlsx",
      title: "导入退款",
      downloadTemplate: downloadRefundImportTemplate,
      upload: uploadRefundImport
    });

    const before = vi.mocked(taskApi.listImportTasks).mock.calls.length;
    dialog.vm.$emit("finished");
    await flushPromises();
    expect(vi.mocked(taskApi.listImportTasks).mock.calls.length).toBe(before + 1);
    wrapper.unmount();
  });

  it("switches the button and the order template name with the language", async () => {
    signIn("SUPER_ADMIN", []);
    const wrapper = await mountPage();
    i18n.global.locale.value = "en-US";
    await nextTick();
    expect(wrapper.get('[data-test="upload-entry"]').text()).toContain("Upload file");
    await openPicker(wrapper);
    expect(document.body.textContent).toContain("Product import");
    expect(document.body.textContent).toContain("Choose import type");
    await choose(wrapper, "ORDER_IMPORT");
    expect(wrapper.getComponent(ImportDialog).props("templateFileName")).toBe(
      "order-import-template.xlsx"
    );
    wrapper.unmount();
  });
});
