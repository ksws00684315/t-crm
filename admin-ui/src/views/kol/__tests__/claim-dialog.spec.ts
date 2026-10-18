import { DOMWrapper, flushPromises, mount } from "@vue/test-utils";
import ElementPlus, { ElMessage } from "element-plus";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import * as promotionApi from "@/api/kol/promotion";
import { i18n } from "@/locales";
import { ApiError } from "@/utils/http/error";
import ClaimKolDialog from "../ClaimKolDialog.vue";

vi.mock("@/api/kol/promotion");

const found = {
  id: "9007199254740993",
  kolCode: "KOL-ASD001",
  name: "Alice",
  maskedEmail: "a***@example.com",
  displayStatus: "PENDING_REVIEW" as const
};

function element(testId: string): DOMWrapper<Element> {
  const el = document.body.querySelector(`[data-test="${testId}"]`);
  if (!el) throw new Error(`missing ${testId}`);
  return new DOMWrapper(el);
}

async function openDialog() {
  const wrapper = mount(ClaimKolDialog, {
    props: { modelValue: false },
    global: { plugins: [ElementPlus, i18n] },
    attachTo: document.body
  });
  await wrapper.setProps({ modelValue: true });
  await flushPromises();
  return wrapper;
}

describe("claim creator dialog", () => {
  beforeEach(() => {
    document.body.innerHTML = "";
    vi.mocked(promotionApi.findClaimableKol).mockReset();
    vi.mocked(promotionApi.claimKol).mockReset();
    vi.mocked(promotionApi.claimKol).mockResolvedValue(null);
  });

  afterEach(() => {
    document.body.innerHTML = "";
    vi.restoreAllMocks();
  });

  it("requires a code or email and checks the code format", async () => {
    const wrapper = await openDialog();
    await element("claim-search").trigger("click");
    await flushPromises();
    expect(element("claim-error").text()).toContain("请输入达人编号或邮箱");
    expect(promotionApi.findClaimableKol).not.toHaveBeenCalled();

    await element("claim-keyword").setValue("KOL0001");
    await element("claim-search").trigger("click");
    await flushPromises();
    expect(element("claim-error").text()).toContain("KOL-ASD001");
    expect(promotionApi.findClaimableKol).not.toHaveBeenCalled();
    wrapper.unmount();
  });

  it("searches by trimmed keyword and shows the found creator", async () => {
    vi.mocked(promotionApi.findClaimableKol).mockResolvedValue(found);
    const wrapper = await openDialog();
    await element("claim-keyword").setValue(" kol-asd001 ");
    await element("claim-search").trigger("click");
    await flushPromises();
    // Codes are upper-cased before the exact search; the backend matches either way.
    expect(promotionApi.findClaimableKol).toHaveBeenCalledWith("KOL-ASD001");
    const text = document.body.textContent ?? "";
    expect(text).toContain("KOL-ASD001");
    expect(text).toContain("Alice");
    expect(text).toContain("a***@example.com");
    expect(text).toContain("待审核");
    expect(element("claim-confirm").exists()).toBe(true);
    wrapper.unmount();
  });

  it("explains when nothing claimable matches", async () => {
    vi.mocked(promotionApi.findClaimableKol).mockResolvedValue(null);
    const info = vi.spyOn(ElMessage, "info").mockImplementation(() => ({}) as never);
    const wrapper = await openDialog();
    await element("claim-keyword").setValue("KOL-ZZZ001");
    await element("claim-search").trigger("click");
    await flushPromises();
    expect(info).toHaveBeenCalledWith("没有找到可认领的达人，请核对编号或邮箱");
    expect(document.body.querySelector('[data-test="claim-result"]')).toBeNull();
    expect(document.body.querySelector('[data-test="claim-confirm"]')).toBeNull();
    wrapper.unmount();
  });

  it("claims the creator and reloads the list", async () => {
    vi.mocked(promotionApi.findClaimableKol).mockResolvedValue(found);
    const success = vi.spyOn(ElMessage, "success").mockImplementation(() => ({}) as never);
    const wrapper = await openDialog();
    await element("claim-keyword").setValue("KOL-ASD001");
    await element("claim-search").trigger("click");
    await flushPromises();
    await element("claim-confirm").trigger("click");
    await flushPromises();
    expect(promotionApi.claimKol).toHaveBeenCalledWith(found.id);
    expect(success).toHaveBeenCalledWith("认领成功，该达人已归入你名下");
    expect(wrapper.emitted("claimed")).toHaveLength(1);
    expect(wrapper.emitted("update:modelValue")?.at(-1)).toEqual([false]);
    wrapper.unmount();
  });

  it("surfaces the 409 with its own message and keeps the result", async () => {
    vi.mocked(promotionApi.findClaimableKol).mockResolvedValue(found);
    vi.mocked(promotionApi.claimKol).mockRejectedValue(
      new ApiError({ status: 409, errorCode: "KOL_ALREADY_CLAIMED", traceId: "t-4" })
    );
    const error = vi.spyOn(ElMessage, "error").mockImplementation(() => ({}) as never);
    const wrapper = await openDialog();
    await element("claim-keyword").setValue("ada@example.com");
    await element("claim-search").trigger("click");
    await flushPromises();
    await element("claim-confirm").trigger("click");
    await flushPromises();
    expect(error).toHaveBeenCalledWith("该达人刚被其他商务认领，请刷新后重试");
    expect(document.querySelector('[data-test="claim-result"]')).not.toBeNull();
    wrapper.unmount();
  });
});
