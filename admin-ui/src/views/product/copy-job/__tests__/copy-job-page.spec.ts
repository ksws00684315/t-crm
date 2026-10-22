import { flushPromises, mount } from "@vue/test-utils";
import { nextTick } from "vue";
import ElementPlus, { ElMessage, ElSelect } from "element-plus";
import { beforeEach, describe, expect, it, vi } from "vitest";
import * as copyJobApi from "@/api/product/copy-job";
import type { CopyJobItemVO, CopyJobVO } from "@/api/product/copy-job";
import { i18n } from "@/locales";
import * as copyUtil from "@/utils/copy";
import { notifyError } from "@/utils/notify";
import CopyJobPage from "../index.vue";

vi.mock("@/api/product/copy-job");
vi.mock("vue-router", () => ({
  useRoute: () => ({ query: routeQuery })
}));
const routeQuery: Record<string, string | undefined> = {};

vi.mock("@/utils/notify", () => ({ notifyError: vi.fn() }));
vi.mock("@/utils/copy", () => ({
  copyText: vi.fn(async () => true),
  truncateCopy: (value: string) => value
}));

function job(overrides: Partial<CopyJobVO> = {}): CopyJobVO {
  return {
    id: "11",
    jobCode: "jobcode38xxxxxxxxxxxx",
    targetChannel: "WEIDIAN",
    status: "completed",
    totalCount: 3,
    successCount: 2,
    failCount: 1,
    errorMessage: "目标店铺未完成微店授权，请先连接微店后再重试这一条很长的原因",
    createTime: "2026-10-21T01:02:03Z",
    finishTime: "2026-10-21T02:03:04Z",
    kolId: "20",
    kolCode: "KOL38",
    kolName: "Ada",
    targetShopCode: null,
    targetShopName: "Main",
    ...overrides
  };
}

function item(overrides: Partial<CopyJobItemVO> = {}): CopyJobItemVO {
  return {
    seq: 1,
    sourceChannel: "WEIDIAN",
    sourceProductId: "10001",
    sourceShopId: "8",
    sourceShopName: "Source shop",
    sourceMerchantNo: "800",
    targetChannel: "WEIDIAN",
    targetShopId: "5",
    targetShopName: "Target shop",
    targetItemId: "90001",
    targetItemUrl: "https://weidian.com/item.html?itemID=90001",
    status: "failed",
    errorMessage: "额度不足",
    ...overrides
  };
}

async function open(
  rows: CopyJobVO[] = [job(), job({ id: "12", status: "partial_failed", errorMessage: null })]
) {
  vi.mocked(copyJobApi.listCopyJobs).mockResolvedValue({
    items: rows,
    total: String(rows.length),
    page: 1,
    size: 20
  });
  const wrapper = mount(CopyJobPage, {
    global: { plugins: [ElementPlus, i18n] },
    attachTo: document.body
  });
  await flushPromises();
  await nextTick();
  return wrapper;
}

describe("Copy job page", () => {
  beforeEach(() => {
    document.body.innerHTML = "";
    localStorage.clear();
    for (const key of Object.keys(routeQuery)) delete routeQuery[key];
    i18n.global.locale.value = "zh-CN";
    vi.mocked(copyUtil.copyText).mockResolvedValue(true);
    vi.spyOn(ElMessage, "success").mockImplementation(() => undefined as never);
    vi.spyOn(ElMessage, "error").mockImplementation(() => undefined as never);
  });

  it("shows columns, result tags and the completed-with-failures hint", async () => {
    const wrapper = await open();
    const header = wrapper.get(".el-table__header").text();
    expect(header).toContain("任务编号");
    expect(header).toContain("目标渠道");
    expect(header).toContain("状态");
    expect(header).toContain("结果");
    expect(header).toContain("错误原因");
    expect(header).toContain("创建时间");
    expect(header).toContain("完成时间");
    expect(header).not.toContain("达人编号");
    expect(wrapper.text()).toContain("已隐藏 5 列");
    expect(wrapper.text()).toContain("查看明细");
    expect(wrapper.text()).toContain("微店");
    expect(wrapper.text()).toContain("已完成");
    expect(wrapper.text()).toContain("部分失败");
    const body = wrapper.findAll(".el-table__body").find(node => node.text().includes("总 3"));
    expect(body).toBeTruthy();
    expect(body!.get('[data-test="result-total"]').text()).toContain("总 3");
    expect(body!.get('[data-test="result-success"]').text()).toContain("成功 2");
    expect(body!.get('[data-test="result-fail"]').text()).toContain("失败 1");
    expect(body!.findAll('[data-test="fail-hint"]')).toHaveLength(1);
    expect(body!.get('[data-test="fail-hint"]').text()).toBe("含失败项，请查看明细");
    expect(wrapper.text()).toContain("目标店铺未完成微店授权");
    expect(wrapper.text()).toContain("-");
    expect(wrapper.text()).not.toContain("2026-10-21T01:02:03Z");
    wrapper.unmount();
  });

  it("switches the same columns to English", async () => {
    const wrapper = await open();
    i18n.global.locale.value = "en-US";
    await nextTick();
    const header = wrapper.get(".el-table__header").text();
    expect(wrapper.get("[data-test=column-settings]").text()).toContain("5 hidden");
    expect(header).toContain("Job code");
    expect(header).toContain("Target channel");
    expect(header).toContain("Finished");
    expect(wrapper.text()).toContain("Weidian");
    expect(wrapper.text()).toContain(
      "This completed job still has failed items. Open the details."
    );
    expect(wrapper.text()).toContain("View items");
    wrapper.unmount();
  });

  it("sends job code, channel and status, then clears them on reset", async () => {
    const wrapper = await open();
    await wrapper.get('[data-test="job-code"]').setValue("ab_c");
    const selects = wrapper.findAllComponents(ElSelect);
    await selects[0].vm.$emit("update:modelValue", "WEIDIAN");
    await selects[1].vm.$emit("update:modelValue", "failed");
    await wrapper.get('[data-test="search"]').trigger("click");
    await flushPromises();
    expect(copyJobApi.listCopyJobs).toHaveBeenLastCalledWith({
      page: 1,
      size: 20,
      jobCode: "ab_c",
      targetChannel: "WEIDIAN",
      status: "failed"
    });
    await wrapper.get('[data-test="reset"]').trigger("click");
    await flushPromises();
    expect(copyJobApi.listCopyJobs).toHaveBeenLastCalledWith({
      page: 1,
      size: 20,
      jobCode: undefined,
      targetChannel: undefined,
      status: undefined
    });
    wrapper.unmount();
  });

  it("copies the old item id and opens the new item link from the detail dialog", async () => {
    localStorage.setItem(
      "tcrm.admin.table.copy-job-items",
      JSON.stringify({ v: 1, columns: [], density: "default", pageSize: 50 })
    );
    vi.mocked(copyJobApi.listCopyJobItems).mockResolvedValue({
      items: [item(), item({ seq: 2, sourceProductId: "10002" })],
      total: "2",
      page: 1,
      size: 50
    });
    const wrapper = await open([job({ failCount: 0, errorMessage: null, status: "completed" })]);
    expect(wrapper.find(".el-table__body [data-test='fail-hint']").exists()).toBe(false);
    await wrapper.get(".el-table__body [data-test='view-items']").trigger("click");
    await flushPromises();
    await nextTick();
    expect(copyJobApi.listCopyJobItems).toHaveBeenCalledWith("11", { page: 1, size: 50 });
    const detail = wrapper
      .findAllComponents({ name: "DataTable" })
      .find(table => table.props("tableId") === "copy-job-items");
    expect(detail?.props("rowKey")).toBe("seq");
    const dialog = document.body.querySelector(".el-dialog") as HTMLElement;
    expect(dialog.textContent).toContain("任务明细：jobcode38xxxxxxxxxxxx");
    expect(dialog.textContent).toContain("旧商品 ID");
    expect(dialog.textContent).toContain("来源商户号");
    expect(dialog.textContent).toContain("800");
    expect(dialog.textContent).toContain("Source shop");
    expect(dialog.textContent).toContain("8");
    expect(dialog.textContent).toContain("失败");
    expect(dialog.textContent).toContain("额度不足");
    const link = dialog.querySelector(
      ".el-table__body [data-test='open-target']"
    ) as HTMLAnchorElement;
    expect(link.getAttribute("href")).toBe("https://weidian.com/item.html?itemID=90001");
    expect(link.getAttribute("target")).toBe("_blank");
    expect(link.textContent).toContain("打开");
    const copy = dialog.querySelector(".el-table__body [data-test='copy']") as HTMLElement;
    copy.click();
    await flushPromises();
    expect(copyUtil.copyText).toHaveBeenCalledWith("10001");
    wrapper.unmount();
  });

  it("reports a failed load and clears the loading state", async () => {
    vi.mocked(copyJobApi.listCopyJobs).mockRejectedValue(new Error("down"));
    const wrapper = mount(CopyJobPage, {
      global: { plugins: [ElementPlus, i18n] },
      attachTo: document.body
    });
    await flushPromises();
    expect(notifyError).toHaveBeenCalled();
    expect(wrapper.findComponent({ name: "DataTable" }).props("loading")).toBe(false);
    wrapper.unmount();
  });

  it("prefills jobCode from the jobCode query and loads", async () => {
    routeQuery.jobCode = " from-route ";
    const wrapper = await open();
    expect((wrapper.get('[data-test="job-code"]').element as HTMLInputElement).value).toBe(
      "from-route"
    );
    expect(copyJobApi.listCopyJobs).toHaveBeenCalledWith(
      expect.objectContaining({ jobCode: "from-route" })
    );
    wrapper.unmount();
  });
});