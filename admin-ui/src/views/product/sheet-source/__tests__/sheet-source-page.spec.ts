import { DOMWrapper, flushPromises, mount } from "@vue/test-utils";
import ElementPlus, { ElMessage } from "element-plus";
import { createPinia, setActivePinia } from "pinia";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { nextTick } from "vue";
import * as googleApi from "@/api/integration/google";
import * as sourceApi from "@/api/product/sheet-source";
import * as shopApi from "@/api/product/shop";
import * as taskApi from "@/api/task/task";
import type { Role, SheetSourceVO } from "@/api/types";
import { i18n } from "@/locales";
import { useUserStore } from "@/stores/user";
import SheetSourcePage from "../index.vue";

vi.mock("@/api/integration/google");
vi.mock("@/api/product/sheet-source");
vi.mock("@/api/product/shop");
vi.mock("@/api/task/task", async importOriginal => ({
  ...(await importOriginal<typeof taskApi>()),
  getTask: vi.fn(),
  downloadTaskErrorFile: vi.fn()
}));

const source: SheetSourceVO = {
  id: "5",
  sourceName: "Main",
  spreadsheetId: "1utim6wvKOCcQf1Qgvzba2Y7bQ79F2T5lqguJz-ODf1E",
  sheetTitle: "ARCHIVE style",
  headerRow: 2,
  linkColumn: "E",
  nameColumn: "B",
  priceColumn: "D",
  categoryColumn: null,
  imageColumn: null,
  defaultShopId: "31",
  defaultShopName: "Main shop",
  priceCurrency: "USD",
  status: 1,
  lastReadAt: "2026-10-16T08:00:00Z",
  lastTaskId: "88",
  lastRowCount: 120,
  lastSkipped: {
    empty: 4,
    nonWeidian: 1,
    noItemId: 1,
    noShop: 0,
    samples: [
      { row: 9, reason: "NON_WEIDIAN", name: "Taobao bag" },
      { row: 12, reason: "NO_ITEM_ID", name: "Short link" }
    ]
  },
  lastError: null
};

function signIn(role: Role, permissions: string[]) {
  useUserStore().user = {
    id: "1",
    username: "me",
    nickname: "Me",
    email: null,
    role,
    postTemplate: role === "STAFF" ? "OPERATOR" : null,
    permissions,
    dataScope: "ALL",
    tenantId: "1",
    tenantName: "默认公司",
    status: 1,
    lastLoginTime: null,
    createTime: "2026-10-01T00:00:00Z"
  };
}

function field(testId: string): DOMWrapper<HTMLInputElement> {
  const element =
    document.body.querySelector<HTMLInputElement>(`[data-test="${testId}"] input`) ??
    document.body.querySelector<HTMLInputElement>(`[data-test="${testId}"]`);
  if (!element) throw new Error(`missing ${testId}`);
  return new DOMWrapper(element);
}

async function mountPage(rows: SheetSourceVO[] = [source]) {
  vi.mocked(sourceApi.listSheetSources).mockResolvedValue(rows);
  vi.mocked(shopApi.listShops).mockResolvedValue([]);
  vi.mocked(googleApi.getGoogleCredential).mockResolvedValue({
    configured: true,
    clientEmail: "reader@tcrm.iam.gserviceaccount.com",
    privateKeyId: "abc",
    updateTime: "2026-10-16T08:00:00Z"
  });
  const wrapper = mount(SheetSourcePage, {
    global: { plugins: [ElementPlus, i18n] },
    attachTo: document.body
  });
  await flushPromises();
  return wrapper;
}

describe("source sheet page", () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    document.body.innerHTML = "";
    vi.clearAllMocks();
  });

  afterEach(() => {
    document.body.innerHTML = "";
  });

  it("shows the service account, configuration and reading to the super administrator", async () => {
    signIn("SUPER_ADMIN", []);
    const wrapper = await mountPage();
    expect(wrapper.find('[data-test="google-card"]').text()).toContain(
      "reader@tcrm.iam.gserviceaccount.com"
    );
    expect(wrapper.find('[data-test="create"]').exists()).toBe(true);
    expect(document.body.querySelector('[data-test="edit-5"]')).not.toBeNull();
    expect(wrapper.find('[data-test="read"]').exists()).toBe(true);
    expect(wrapper.text()).toContain("链接 E · 名称 B · 价格 D");
    expect(wrapper.find('[data-test="skipped-link"]').text()).toContain("跳过 2 行");
    wrapper.unmount();
  });

  it("lets an operator read and see missing products but not configure", async () => {
    signIn("STAFF", ["product:view", "product:edit"]);
    const wrapper = await mountPage();
    expect(wrapper.find('[data-test="google-card"]').exists()).toBe(false);
    expect(wrapper.find('[data-test="create"]').exists()).toBe(false);
    expect(wrapper.find('[data-test="edit"]').exists()).toBe(false);
    expect(wrapper.find('[data-test="read"]').exists()).toBe(true);
    expect(wrapper.find('[data-test="missing"]').exists()).toBe(true);
    expect(googleApi.getGoogleCredential).not.toHaveBeenCalled();
    wrapper.unmount();
  });

  it("shows the list only to a viewer", async () => {
    signIn("STAFF", ["product:view"]);
    const wrapper = await mountPage();
    expect(wrapper.find('[data-test="read"]').exists()).toBe(false);
    expect(wrapper.find('[data-test="missing"]').exists()).toBe(false);
    wrapper.unmount();
  });

  it("shows the last read error in words", async () => {
    signIn("SUPER_ADMIN", []);
    const wrapper = await mountPage([{ ...source, lastError: "GOOGLE_SHEET_FORBIDDEN" }]);
    expect(wrapper.find('[data-test="last-error"]').text()).toContain("表格没有分享给服务账号");
    wrapper.unmount();
  });

  it("uploads the key file content and shows the saved account", async () => {
    signIn("SUPER_ADMIN", []);
    vi.spyOn(ElMessage, "success").mockImplementation(() => ({}) as never);
    vi.mocked(googleApi.saveGoogleCredential).mockResolvedValue({
      configured: true,
      clientEmail: "new@tcrm.iam.gserviceaccount.com",
      privateKeyId: "def",
      updateTime: "2026-10-16T09:00:00Z"
    });
    const wrapper = await mountPage();
    const input = wrapper.get('[data-test="google-file"]').element as HTMLInputElement;
    const file = new File(['{"type":"service_account"}'], "key.json", { type: "application/json" });
    Object.defineProperty(input, "files", { value: [file] });
    await wrapper.get('[data-test="google-file"]').trigger("change");
    await flushPromises();
    expect(googleApi.saveGoogleCredential).toHaveBeenCalledWith('{"type":"service_account"}');
    expect(wrapper.find('[data-test="google-card"]').text()).toContain(
      "new@tcrm.iam.gserviceaccount.com"
    );
    wrapper.unmount();
  });

  it("rejects a file that is not a JSON key without calling the backend", async () => {
    signIn("SUPER_ADMIN", []);
    const error = vi.spyOn(ElMessage, "error").mockImplementation(() => ({}) as never);
    const wrapper = await mountPage();
    const input = wrapper.get('[data-test="google-file"]').element as HTMLInputElement;
    Object.defineProperty(input, "files", { value: [new File(["x"], "key.txt")] });
    await wrapper.get('[data-test="google-file"]').trigger("change");
    await flushPromises();
    expect(googleApi.saveGoogleCredential).not.toHaveBeenCalled();
    expect(error).toHaveBeenCalled();
    wrapper.unmount();
  });

  it("reads now and follows the import task to the end", async () => {
    signIn("SUPER_ADMIN", []);
    vi.mocked(sourceApi.readSheetSource).mockResolvedValue({
      taskId: "99",
      rowCount: 120,
      skipped: source.lastSkipped!
    });
    vi.mocked(taskApi.getTask).mockResolvedValue({
      id: "99",
      taskType: "PRODUCT_IMPORT",
      status: "PARTIAL",
      total: 120,
      done: 120,
      failed: 2,
      message: null,
      hasErrorFile: true,
      createTime: "2026-10-16T08:00:00Z",
      startedAt: null,
      finishedAt: null
    } as never);
    const wrapper = await mountPage();
    // The table also renders the actions slot once outside the data rows; click the row's button.
    await wrapper.get('.el-table__row [data-test="read"]').trigger("click");
    await flushPromises();
    await nextTick();
    expect(sourceApi.readSheetSource).toHaveBeenCalledWith("5");
    expect(document.body.querySelector('[data-test="read-rows"]')?.textContent).toContain("120");
    expect(document.body.querySelector('[data-test="read-errors"]')).not.toBeNull();
    expect(document.body.querySelector('[data-test="skipped"]')?.textContent).toContain(
      "非微店商品"
    );
    wrapper.unmount();
  });

  it("validates columns before saving", async () => {
    signIn("SUPER_ADMIN", []);
    const wrapper = await mountPage([]);
    await wrapper.get('[data-test="create"]').trigger("click");
    await nextTick();
    await field("source-name").setValue("Main");
    await field("source-sheet").setValue(source.spreadsheetId);
    await field("source-tab").setValue("ARCHIVE style");
    await field("col-link").setValue("E");
    await field("col-name").setValue("e");
    await field("col-price").setValue("D");
    document.body.querySelector<HTMLButtonElement>('[data-test="save"]')?.click();
    await flushPromises();
    expect(document.body.querySelector('[data-test="form-error"]')?.textContent).toContain(
      "链接、名称、价格不能是同一列"
    );
    expect(sourceApi.createSheetSource).not.toHaveBeenCalled();

    await field("col-name").setValue("B");
    vi.spyOn(ElMessage, "success").mockImplementation(() => ({}) as never);
    vi.mocked(sourceApi.createSheetSource).mockResolvedValue(source);
    document.body.querySelector<HTMLButtonElement>('[data-test="save"]')?.click();
    await flushPromises();
    expect(sourceApi.createSheetSource).toHaveBeenCalledWith(
      expect.objectContaining({
        linkColumn: "E",
        nameColumn: "B",
        priceColumn: "D",
        categoryColumn: null,
        priceCurrency: "USD"
      })
    );
    wrapper.unmount();
  });
});
