import { flushPromises, mount } from "@vue/test-utils";
import ElementPlus, { ElMessage } from "element-plus";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import * as kolApi from "@/api/kol/kol";
import * as taskApi from "@/api/task/task";
import type { TaskVO } from "@/api/types";
import KolImportDialog from "../KolImportDialog.vue";

vi.mock("@/api/kol/kol", async importOriginal => ({
  ...(await importOriginal<typeof kolApi>()),
  uploadKolImport: vi.fn(),
  downloadKolTemplate: vi.fn()
}));
vi.mock("@/api/task/task", async importOriginal => ({
  ...(await importOriginal<typeof taskApi>()),
  getTask: vi.fn(),
  downloadTaskErrorFile: vi.fn()
}));

const task = (patch: Partial<TaskVO>): TaskVO => ({
  id: "77",
  taskType: "KOL_IMPORT",
  status: "RUNNING",
  total: 4,
  done: 0,
  failed: 0,
  message: null,
  hasErrorFile: false,
  createTime: "2026-10-05T00:00:00Z",
  startedAt: null,
  finishedAt: null,
  ...patch
});

async function mountDialog() {
  const wrapper = mount(KolImportDialog, {
    props: { modelValue: false },
    global: { plugins: [ElementPlus] },
    attachTo: document.body
  });
  await wrapper.setProps({ modelValue: true });
  await flushPromises();
  return wrapper;
}

async function choose(file: File) {
  const input = document.body.querySelector<HTMLInputElement>('[data-test="file"]')!;
  Object.defineProperty(input, "files", { value: [file], configurable: true });
  input.dispatchEvent(new Event("change"));
  await flushPromises();
  document.body.querySelector<HTMLButtonElement>('[data-test="upload"]')!.click();
  await flushPromises();
}

describe("KOL import dialog", () => {
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => {
    vi.useRealTimers();
    document.body.innerHTML = "";
  });

  it("polls every second until the task finishes, then offers the error file", async () => {
    vi.mocked(kolApi.uploadKolImport).mockResolvedValue({ taskId: "77" });
    vi.mocked(taskApi.getTask)
      .mockResolvedValueOnce(task({ status: "WAITING" }))
      .mockResolvedValueOnce(task({ done: 2 }))
      .mockResolvedValueOnce(task({ status: "PARTIAL", done: 4, failed: 1, hasErrorFile: true }));
    const wrapper = await mountDialog();

    await choose(new File(["PK"], "kols.xlsx"));
    expect(kolApi.uploadKolImport).toHaveBeenCalledTimes(1);
    expect(taskApi.getTask).not.toHaveBeenCalled();

    await vi.advanceTimersByTimeAsync(1000);
    expect(document.body.textContent).toContain("排队中");
    await vi.advanceTimersByTimeAsync(1000);
    expect(document.body.textContent).toContain("已处理 2 / 4 行");
    await vi.advanceTimersByTimeAsync(1000);
    expect(document.body.textContent).toContain("成功 3 行，失败 1 行");
    expect(document.body.querySelector('[data-test="error-file"]')).not.toBeNull();

    await vi.advanceTimersByTimeAsync(5000);
    expect(taskApi.getTask).toHaveBeenCalledTimes(3);

    await wrapper.setProps({ modelValue: false });
    expect(wrapper.emitted("finished")).toHaveLength(1);
  });

  it("stops polling when closed before the task finishes and does not ask for a reload", async () => {
    vi.mocked(kolApi.uploadKolImport).mockResolvedValue({ taskId: "77" });
    vi.mocked(taskApi.getTask).mockResolvedValue(task({ done: 1 }));
    const wrapper = await mountDialog();
    await choose(new File(["PK"], "kols.xlsx"));
    await vi.advanceTimersByTimeAsync(1000);
    await wrapper.setProps({ modelValue: false });
    await vi.advanceTimersByTimeAsync(5000);
    expect(taskApi.getTask).toHaveBeenCalledTimes(1);
    expect(wrapper.emitted("finished")).toBeUndefined();
  });

  it("rejects oversized or non-xlsx files before uploading", async () => {
    const error = vi.spyOn(ElMessage, "error");
    await mountDialog();
    const big = new File(["x"], "kols.xlsx");
    Object.defineProperty(big, "size", { value: kolApi.IMPORT_MAX_BYTES + 1 });
    await choose(big);
    expect(error).toHaveBeenCalledWith("文件超过 20MB，请拆分后再上传");
    await choose(new File(["a,b"], "kols.csv"));
    expect(error).toHaveBeenCalledWith("请上传 .xlsx 格式的 Excel 文件");
    expect(kolApi.uploadKolImport).not.toHaveBeenCalled();
  });

  it("explains a task that failed without an error file", async () => {
    vi.mocked(kolApi.uploadKolImport).mockResolvedValue({ taskId: "77" });
    vi.mocked(taskApi.getTask).mockResolvedValue(
      task({ status: "FAILED", message: "Interrupted by restart" })
    );
    const wrapper = await mountDialog();
    await choose(new File(["PK"], "kols.xlsx"));
    await vi.advanceTimersByTimeAsync(1000);
    expect(document.body.textContent).toContain("文件无法处理或任务被中断");
    expect(document.body.textContent).not.toContain("Interrupted by restart");
    expect(wrapper.exists()).toBe(true);
  });
});
