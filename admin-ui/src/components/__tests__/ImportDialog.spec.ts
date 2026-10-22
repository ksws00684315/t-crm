import { flushPromises, mount } from "@vue/test-utils";
import ElementPlus, { ElDatePicker } from "element-plus";
import { beforeEach, describe, expect, it, vi } from "vitest";
import type { TaskType } from "@/api/types";
import { i18n } from "@/locales";
import ImportDialog from "../ImportDialog.vue";

vi.mock("@/api/task/task", () => ({
  getTask: vi.fn().mockResolvedValue({
    id: "9901",
    taskType: "ORDER_IMPORT",
    status: "SUCCESS",
    total: 1,
    done: 1,
    failed: 0,
    message: null,
    hasErrorFile: false,
    createTime: "2026-10-21T00:00:00Z",
    startedAt: null,
    finishedAt: "2026-10-21T00:00:01Z"
  }),
  isFinished: vi.fn((status: string) => ["SUCCESS", "PARTIAL", "FAILED"].includes(status)),
  downloadTaskErrorFile: vi.fn()
}));

/**
 * Task 40 §3.5: order and refund imports require the data cutoff time — when the uploaded
 * file's data ends. It defaults to "now", may not be in the future, and travels with the
 * upload. Dialogs without the flag keep the old single-argument flow. The dialog body and
 * footer teleport to the document body, so the spec reads the rendered document.
 */
const baseProps = {
  modelValue: true,
  title: "导入订单",
  limitHint: "20MB",
  templateFileName: "order-import-template.xlsx",
  taskType: "ORDER_IMPORT" as TaskType,
  downloadTemplate: vi.fn()
};

function pickCsv() {
  const input = document.body.querySelector<HTMLInputElement>('input[data-test="file"]');
  if (!input) throw new Error("missing file input");
  Object.defineProperty(input, "files", {
    value: [
      new File(["order no"], "t40-orders.xlsx", {
        type: "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
      })
    ]
  });
  input.dispatchEvent(new Event("change"));
}

function clickUpload() {
  document.body.querySelector<HTMLButtonElement>('[data-test="upload"]')?.click();
}

async function mountDialog(requireDataCutoff: boolean) {
  const upload = vi.fn().mockResolvedValue({ taskId: "9901" });
  const wrapper = mount(ImportDialog, {
    props: { ...baseProps, upload, requireDataCutoff },
    global: { plugins: [ElementPlus, i18n] },
    attachTo: document.body
  });
  await flushPromises();
  return { wrapper, upload };
}

describe("ImportDialog data cutoff time (task 40)", () => {
  beforeEach(() => {
    document.body.innerHTML = "";
  });

  it("shows the picker with a default of now and sends the ISO instant with the upload", async () => {
    // Snapshot before mounting: the default is stamped when the dialog opens.
    const before = Date.now();
    const { wrapper, upload } = await mountDialog(true);
    expect(document.body.querySelector('[data-test="data-cutoff"]')).not.toBeNull();
    pickCsv();
    await flushPromises();
    clickUpload();
    await flushPromises();
    expect(upload).toHaveBeenCalledTimes(1);
    const [, cutoff] = vi.mocked(upload).mock.calls[0];
    const parsed = new Date(String(cutoff));
    expect(parsed.getTime()).toBeGreaterThanOrEqual(before - 1000);
    expect(parsed.getTime()).toBeLessThanOrEqual(Date.now() + 1000);
    wrapper.unmount();
  });

  it("refuses to upload without or with a future cutoff time", async () => {
    const { wrapper, upload } = await mountDialog(true);
    pickCsv();
    await flushPromises();
    const picker = wrapper.findComponent(ElDatePicker);
    picker.vm.$emit("update:modelValue", null);
    await flushPromises();
    clickUpload();
    await flushPromises();
    picker.vm.$emit("update:modelValue", new Date(Date.now() + 60 * 60 * 1000));
    await flushPromises();
    clickUpload();
    await flushPromises();
    expect(upload).not.toHaveBeenCalled();
    wrapper.unmount();
  });

  it("keeps the old flow for dialogs without the flag", async () => {
    const { wrapper, upload } = await mountDialog(false);
    expect(document.body.querySelector('[data-test="data-cutoff"]')).toBeNull();
    pickCsv();
    await flushPromises();
    clickUpload();
    await flushPromises();
    expect(upload).toHaveBeenCalledTimes(1);
    const [, cutoff] = vi.mocked(upload).mock.calls[0];
    expect(cutoff).toBeUndefined();
    wrapper.unmount();
  });
});
