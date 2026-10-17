import { DOMWrapper, flushPromises, mount } from "@vue/test-utils";
import ElementPlus, { ElMessage } from "element-plus";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import * as transferApi from "@/api/kol/bd-transfer";
import type { TransferTarget } from "@/api/kol/bd-transfer";
import * as bdApi from "@/api/system/bd-user";
import { i18n } from "@/locales";
import { ApiError } from "@/utils/http/error";
import BdTransferDialog from "../BdTransferDialog.vue";

vi.mock("@/api/kol/bd-transfer");
vi.mock("@/api/system/bd-user");

const UNASSIGNED = "__unassigned__";

async function openDialog(target: TransferTarget) {
  vi.mocked(bdApi.listBdUsers).mockResolvedValue([
    { id: "5", username: "bd5", nickname: "BD Five" },
    { id: "9", username: "bd9", nickname: "BD Nine" }
  ]);
  const wrapper = mount(BdTransferDialog, {
    props: { modelValue: false, target },
    global: { plugins: [ElementPlus, i18n] },
    attachTo: document.body
  });
  await wrapper.setProps({ modelValue: true });
  await flushPromises();
  return wrapper;
}

function selectBd(wrapper: Awaited<ReturnType<typeof openDialog>>, value: string) {
  wrapper.findComponent({ name: "ElSelect" }).vm.$emit("update:modelValue", value);
}

function pickTime(wrapper: Awaited<ReturnType<typeof openDialog>>, when: Date) {
  wrapper.findComponent({ name: "ElDatePicker" }).vm.$emit("update:modelValue", when);
}

function switchToScheduled(wrapper: Awaited<ReturnType<typeof openDialog>>) {
  wrapper.findComponent({ name: "ElRadioGroup" }).vm.$emit("update:modelValue", true);
}

async function submit() {
  const button = document.body.querySelector('[data-test="transfer-submit"]');
  if (!button) throw new Error("missing transfer-submit");
  await new DOMWrapper(button).trigger("click");
  await flushPromises();
}

describe("BD transfer dialog", () => {
  beforeEach(() => {
    document.body.innerHTML = "";
    vi.mocked(transferApi.createBdTransfer).mockReset();
    vi.spyOn(ElMessage, "success").mockImplementation(() => ({}) as never);
    vi.spyOn(ElMessage, "warning").mockImplementation(() => ({}) as never);
    vi.spyOn(ElMessage, "error").mockImplementation(() => ({}) as never);
  });

  afterEach(() => {
    document.body.innerHTML = "";
  });

  it("sends kolIds right now without effectiveAt and reports all three counts", async () => {
    vi.mocked(transferApi.createBdTransfer).mockResolvedValue({
      immediate: 2,
      scheduled: 0,
      skipped: 1
    });
    const wrapper = await openDialog({ kolIds: ["11", "12"] });
    expect(document.body.textContent).toContain("已选 2 位达人");
    selectBd(wrapper, "5");
    await submit();
    expect(transferApi.createBdTransfer).toHaveBeenCalledWith({
      kolIds: ["11", "12"],
      toBdUserId: "5"
    });
    expect(ElMessage.success).toHaveBeenCalledWith(
      "立即完成 2 位，预约 0 位，跳过 1 位（已属于新商务）"
    );
    expect(wrapper.props("modelValue")).toBe(true);
    expect(wrapper.emitted("saved")).toHaveLength(1);
    expect(wrapper.emitted("update:modelValue")?.at(-1)).toEqual([false]);
    wrapper.unmount();
  });

  it("sends fromBdUserId with toBdUserId null when transferring a whole BD to the company", async () => {
    vi.mocked(transferApi.createBdTransfer).mockResolvedValue({
      immediate: 3,
      scheduled: 0,
      skipped: 0
    });
    const wrapper = await openDialog({ fromBdUserId: "9", fromBdName: "BD Nine", count: 3 });
    expect(document.body.textContent).toContain("商务 BD Nine 名下全部达人（当前 3 位）");
    selectBd(wrapper, UNASSIGNED);
    await submit();
    expect(transferApi.createBdTransfer).toHaveBeenCalledWith({
      fromBdUserId: "9",
      toBdUserId: null
    });
    wrapper.unmount();
  });

  it("excludes the source BD from the target options for a whole-BD transfer", async () => {
    const wrapper = await openDialog({ fromBdUserId: "9", fromBdName: "BD Nine", count: 3 });
    const options = wrapper
      .findComponent({ name: "ElSelect" })
      .findAllComponents({ name: "ElOption" });
    expect(options.map(option => option.props("value"))).toEqual(["5", UNASSIGNED]);
    wrapper.unmount();
  });

  it("converts a picked local time to an ISO UTC instant", async () => {
    vi.mocked(transferApi.createBdTransfer).mockResolvedValue({
      immediate: 0,
      scheduled: 1,
      skipped: 0
    });
    const wrapper = await openDialog({ kolIds: ["11"] });
    selectBd(wrapper, "5");
    switchToScheduled(wrapper);
    await flushPromises();
    // A future local instant within one year; toISOString mirrors the UTC conversion on submit.
    const local = new Date(Date.now() + 30 * 24 * 3600 * 1000);
    pickTime(wrapper, local);
    await wrapper.find("textarea").setValue("handover");
    await submit();
    expect(transferApi.createBdTransfer).toHaveBeenCalledWith({
      kolIds: ["11"],
      toBdUserId: "5",
      effectiveAt: local.toISOString(),
      reason: "handover"
    });
    wrapper.unmount();
  });

  it("blocks a time beyond one year, in the past, or missing", async () => {
    const wrapper = await openDialog({ kolIds: ["11"] });
    selectBd(wrapper, "5");
    switchToScheduled(wrapper);
    await flushPromises();
    await submit();
    expect(ElMessage.warning).toHaveBeenCalledWith("请选择生效时间");

    pickTime(wrapper, new Date(Date.now() - 2000));
    await submit();
    expect(ElMessage.warning).toHaveBeenCalledWith("生效时间必须晚于当前时间");

    const far = new Date();
    far.setFullYear(far.getFullYear() + 2);
    pickTime(wrapper, far);
    await submit();
    expect(ElMessage.warning).toHaveBeenCalledWith("生效时间需在一年以内");
    expect(transferApi.createBdTransfer).not.toHaveBeenCalled();
    wrapper.unmount();
  });

  it("blocks a batch larger than 500 creators", async () => {
    const wrapper = await openDialog({ kolIds: Array.from({ length: 501 }, (_, i) => String(i)) });
    selectBd(wrapper, "5");
    await submit();
    expect(ElMessage.warning).toHaveBeenCalledWith("一次最多转移 500 位达人");
    expect(transferApi.createBdTransfer).not.toHaveBeenCalled();
    wrapper.unmount();
  });

  it("requires a target BD before submitting", async () => {
    const wrapper = await openDialog({ kolIds: ["11"] });
    await submit();
    expect(ElMessage.warning).toHaveBeenCalledWith("请选择新商务");
    expect(transferApi.createBdTransfer).not.toHaveBeenCalled();
    wrapper.unmount();
  });

  it("lists the conflicting creator codes on 409 KOL_TRANSFER_EXISTS and stays open", async () => {
    vi.mocked(transferApi.createBdTransfer).mockRejectedValue(
      new ApiError({
        status: 409,
        errorCode: "KOL_TRANSFER_EXISTS",
        traceId: "trace-1",
        payload: ["KOL0001", "KOL0002"]
      })
    );
    const wrapper = await openDialog({ kolIds: ["11"] });
    selectBd(wrapper, "5");
    await submit();
    const alert = document.body.querySelector('[data-test="transfer-conflicts"]');
    expect(alert?.textContent).toContain("以下达人已有预约转移，请先取消：");
    expect(alert?.textContent).toContain("KOL0001、KOL0002");
    expect(wrapper.props("modelValue")).toBe(true);
    wrapper.unmount();
  });

  it("shows the mapped text for 422 KOL_INVALID_BD_USER", async () => {
    vi.mocked(transferApi.createBdTransfer).mockRejectedValue(
      new ApiError({ status: 422, errorCode: "KOL_INVALID_BD_USER", traceId: "trace-2" })
    );
    const wrapper = await openDialog({ kolIds: ["11"] });
    selectBd(wrapper, "5");
    await submit();
    expect(ElMessage.error).toHaveBeenCalledWith(
      "负责商务必须是已启用的子账号（仅本人数据，且有达人编辑权限）"
    );
    wrapper.unmount();
  });
});
