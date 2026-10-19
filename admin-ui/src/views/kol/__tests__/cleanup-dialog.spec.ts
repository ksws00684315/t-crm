import { DOMWrapper, flushPromises, mount } from "@vue/test-utils";
import ElementPlus, { ElMessage } from "element-plus";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import * as promotionApi from "@/api/kol/promotion";
import type { CleanupPreviewVO } from "@/api/kol/promotion";
import { i18n } from "@/locales";
import CleanupConfigDialog from "../CleanupConfigDialog.vue";

vi.mock("@/api/kol/promotion");

const previewRow: CleanupPreviewVO = {
  id: "9007199254740993",
  kolCode: "KOL-ASD001",
  name: "Alice",
  bdUserName: "BD Five",
  lastOrderAt: "2026-04-01T00:00:00Z",
  idleSince: "2026-04-01T00:00:00Z"
};

function element(testId: string): DOMWrapper<Element> {
  const el = document.body.querySelector(`[data-test="${testId}"]`);
  if (!el) throw new Error(`missing ${testId}`);
  return new DOMWrapper(el);
}

async function openDialog() {
  const wrapper = mount(CleanupConfigDialog, {
    props: { modelValue: false },
    global: { plugins: [ElementPlus, i18n] },
    attachTo: document.body
  });
  await wrapper.setProps({ modelValue: true });
  await flushPromises();
  return wrapper;
}

describe("cleanup config dialog", () => {
  beforeEach(() => {
    document.body.innerHTML = "";
    vi.mocked(promotionApi.getCleanupConfig).mockReset();
    vi.mocked(promotionApi.saveCleanupConfig).mockReset();
    vi.mocked(promotionApi.listCleanupPreview).mockReset();
    vi.mocked(promotionApi.getCleanupConfig).mockResolvedValue({ enabled: false, idleDays: 30 });
    vi.mocked(promotionApi.saveCleanupConfig).mockResolvedValue(null);
    vi.mocked(promotionApi.listCleanupPreview).mockResolvedValue({
      items: [previewRow],
      total: "1",
      page: 1,
      size: 10
    });
  });

  afterEach(() => {
    document.body.innerHTML = "";
    vi.restoreAllMocks();
  });

  it("loads the saved configuration and describes what cleanup does", async () => {
    const wrapper = await openDialog();
    expect(promotionApi.getCleanupConfig).toHaveBeenCalledTimes(1);
    const text = document.body.textContent ?? "";
    expect(text).toContain("逐条删除其微店专属商品");
    expect(text).toContain("订单与佣金历史保留");
    expect(element("cleanup-days").find("input").element).toBeDefined();
    wrapper.unmount();
  });

  it("keeps idle days inside 7-365: the input clamps and only ranged values are saved", async () => {
    const success = vi.spyOn(ElMessage, "success").mockImplementation(() => ({}) as never);
    const wrapper = await openDialog();
    const input = element("cleanup-days").find("input");
    await input.setValue("5");
    await element("cleanup-save").trigger("click");
    await flushPromises();
    // The number input clamps to its min; nothing outside 7-365 is ever sent.
    expect(promotionApi.saveCleanupConfig).toHaveBeenCalledWith(
      expect.objectContaining({ idleDays: expect.anything() })
    );
    const saved = vi.mocked(promotionApi.saveCleanupConfig).mock.calls[0][0];
    expect(saved.idleDays).toBeGreaterThanOrEqual(7);
    expect(saved.idleDays).toBeLessThanOrEqual(365);
    expect(success).toHaveBeenCalledTimes(1);
    wrapper.unmount();
  });

  it("saves the switch state and idle days", async () => {
    const success = vi.spyOn(ElMessage, "success").mockImplementation(() => ({}) as never);
    const wrapper = await openDialog();
    await element("cleanup-days").find("input").setValue("60");
    await element("cleanup-save").trigger("click");
    await flushPromises();
    expect(promotionApi.saveCleanupConfig).toHaveBeenCalledWith({ enabled: false, idleDays: 60 });
    expect(success).toHaveBeenCalledWith("清理配置已保存");
    expect(wrapper.emitted("update:modelValue")?.at(-1)).toEqual([false]);
    wrapper.unmount();
  });

  it("previews the creators the next run would clean up", async () => {
    const wrapper = await openDialog();
    await element("cleanup-preview").trigger("click");
    await flushPromises();
    expect(promotionApi.listCleanupPreview).toHaveBeenCalledWith({ page: 1, size: 10 });
    const text = document.body.textContent ?? "";
    expect(text).toContain("KOL-ASD001");
    expect(text).toContain("BD Five");
    expect(text).toContain("下次执行将清理以下达人");
    wrapper.unmount();
  });
});
