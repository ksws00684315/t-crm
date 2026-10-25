import { flushPromises, mount } from "@vue/test-utils";
import ElementPlus from "element-plus";
import { createPinia } from "pinia";
import { afterEach, describe, expect, it, vi } from "vitest";
import { getCreatorAnalytics, getFundSummary } from "@/api/creator";
import { i18n } from "@/locales";
import HomePage from "../index.vue";

vi.mock("@/api/creator", () => ({ getCreatorAnalytics: vi.fn(), getFundSummary: vi.fn() }));
afterEach(() => vi.useRealTimers());

describe("workspace analytics interval", () => {
  it("requests seven complete calendar dates including today", async () => {
    vi.useFakeTimers({ toFake: ["Date"] });
    vi.setSystemTime(new Date(2026, 9, 26, 11, 30));
    vi.mocked(getCreatorAnalytics).mockResolvedValue({
      asOf: "2026-10-26T03:30:00Z",
      salesCny: "0.00",
      paidOrderCount: 0,
      commissionCny: "0.00",
      withdrawnCny: "0.00",
      withdrawingCny: "0.00",
      frozenCny: "0.00",
      daily: []
    });
    vi.mocked(getFundSummary).mockRejectedValue(new Error("t60-test"));
    const wrapper = mount(HomePage, {
      global: { plugins: [ElementPlus, i18n, createPinia()], stubs: { RouterLink: true } }
    });
    await flushPromises();
    expect(getCreatorAnalytics).toHaveBeenCalledWith({
      from: "2026-10-20T00:00:00+08:00",
      to: "2026-10-27T00:00:00+08:00"
    });
    wrapper.unmount();
  });
});
