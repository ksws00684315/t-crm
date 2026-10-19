import { flushPromises, mount } from "@vue/test-utils";
import ElementPlus, { ElMessage, ElSelect } from "element-plus";
import { beforeEach, describe, expect, it, vi } from "vitest";
import * as promotionApi from "@/api/kol/promotion";
import type { CleanupItemVO } from "@/api/kol/promotion";
import { i18n } from "@/locales";
import CleanupRecordsPage from "../CleanupRecordsPage.vue";

vi.mock("@/api/kol/promotion");

const failed: CleanupItemVO = {
  id: "9007199254740993",
  kolCode: "KOL-ASD001",
  shopId: "SHOP-01",
  weidianItemId: "WD-123",
  status: "FAILED",
  trigger: "MANUAL",
  reason: "客户要求移除",
  failReason: "微店接口暂不可用",
  cleanupTime: "2026-10-17T02:03:04Z",
  finishTime: "2026-10-17T02:04:04Z"
};

async function openPage(canWrite = true) {
  const wrapper = mount(CleanupRecordsPage, {
    props: { canWrite },
    global: { plugins: [ElementPlus, i18n] },
    attachTo: document.body
  });
  await flushPromises();
  return wrapper;
}

describe("cleanup records page", () => {
  beforeEach(() => {
    document.body.innerHTML = "";
    vi.mocked(promotionApi.listCleanupItems).mockReset();
    vi.mocked(promotionApi.retryCleanupItem).mockReset();
    vi.mocked(promotionApi.retryCleanupItem).mockResolvedValue(null);
    vi.mocked(promotionApi.listCleanupItems).mockResolvedValue({
      items: [failed],
      total: "1",
      page: 1,
      size: 20
    });
  });

  it("shows cleanup details and offers retry only for failed records", async () => {
    const wrapper = await openPage();
    const text = wrapper.text();
    expect(promotionApi.listCleanupItems).toHaveBeenCalledWith({
      kolCode: undefined,
      status: undefined,
      page: 1,
      size: 20
    });
    for (const value of ["KOL-ASD001", "SHOP-01", "WD-123", "微店接口暂不可用", "客户要求移除"])
      expect(text).toContain(value);
    expect(wrapper.find('[data-test="cleanup-retry"]').exists()).toBe(true);
    wrapper.unmount();

    vi.mocked(promotionApi.listCleanupItems).mockResolvedValue({
      items: [{ ...failed, status: "SUCCESS" }],
      total: "1",
      page: 1,
      size: 20
    });
    const successful = await openPage();
    expect(successful.find('[data-test="cleanup-retry"]').exists()).toBe(false);
    successful.unmount();
  });

  it("retries a failed record and refreshes the list", async () => {
    const success = vi.spyOn(ElMessage, "success").mockImplementation(() => ({}) as never);
    const wrapper = await openPage();
    await wrapper.get('[data-test="cleanup-retry"]').trigger("click");
    await flushPromises();
    expect(promotionApi.retryCleanupItem).toHaveBeenCalledWith(failed.id);
    expect(promotionApi.listCleanupItems).toHaveBeenCalledTimes(2);
    expect(success).toHaveBeenCalledWith("已提交重试");
    wrapper.unmount();
  });

  it("hides retry from view-only users", async () => {
    const wrapper = await openPage(false);
    expect(wrapper.find('[data-test="cleanup-retry"]').exists()).toBe(false);
    wrapper.unmount();
  });

  it("filters cleanup records by creator code and status", async () => {
    const wrapper = await openPage();
    await wrapper.get('[data-test="cleanup-code"]').setValue("KOL-ASD001");
    wrapper.findComponent(ElSelect).vm.$emit("update:model-value", "FAILED");
    await wrapper.get('[data-test="search"]').trigger("click");
    await flushPromises();
    expect(promotionApi.listCleanupItems).toHaveBeenLastCalledWith({
      kolCode: "KOL-ASD001",
      status: "FAILED",
      page: 1,
      size: 20
    });
    wrapper.unmount();
  });
});
