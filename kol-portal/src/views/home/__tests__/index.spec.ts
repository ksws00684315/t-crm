import { flushPromises, mount, type VueWrapper } from "@vue/test-utils";
import ElementPlus from "element-plus";
import { createPinia, setActivePinia } from "pinia";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { getMe } from "@/api/auth";
import {
  getCreatorAnalytics,
  getFundSummary,
  getSalesTotal,
  type CreatorAnalyticsOverview,
  type FundSummary
} from "@/api/creator";
import { i18n } from "@/locales";
import { useSessionStore } from "@/stores/session";
import { createChart, type ChartHandle } from "@/utils/echarts";
import HomePage from "../index.vue";

vi.mock("@/api/creator", () => ({
  getCreatorAnalytics: vi.fn(),
  getFundSummary: vi.fn(),
  getSalesTotal: vi.fn()
}));
vi.mock("@/api/auth", () => ({ getMe: vi.fn() }));
vi.mock("@/utils/echarts", () => ({ createChart: vi.fn() }));
const push = vi.fn();
vi.mock("vue-router", () => ({ useRouter: () => ({ push }), useRoute: () => ({ query: {} }) }));

let wrapper: VueWrapper | undefined;
let chart: {
  setOption: ReturnType<typeof vi.fn>;
  resize: ReturnType<typeof vi.fn>;
  dispose: ReturnType<typeof vi.fn>;
};

function fund(overrides: Partial<FundSummary> = {}): FundSummary {
  return {
    totalIncomeCny: "0.00",
    commissionIncomeCny: "0.00",
    estimatedCommissionCny: "1000.00",
    totalCommissionCny: "0.00",
    withdrawnCny: "200.00",
    withdrawingCny: "100.00",
    frozenCny: "300.00",
    receivedPrepayCny: "0.00",
    availableCny: "400.00",
    prepayDeductionCny: "0.00",
    prepayOutstandingCny: "0.00",
    asOf: "2026-11-01T08:00:00Z",
    ...overrides
  };
}
function overview(daily: CreatorAnalyticsOverview["daily"] = []): CreatorAnalyticsOverview {
  return {
    asOf: "2026-11-01T08:00:00Z",
    salesCny: "0.00",
    paidOrderCount: 0,
    commissionCny: "0.00",
    withdrawnCny: "0.00",
    withdrawingCny: "0.00",
    frozenCny: "0.00",
    daily
  };
}
async function mountHome(options: { rate?: string | null; fundSummary?: FundSummary } = {}) {
  vi.mocked(getFundSummary).mockResolvedValue({ fundSummary: options.fundSummary ?? fund() });
  vi.mocked(getMe).mockResolvedValue({
    kolCode: "K1",
    name: "Creator",
    email: "c@example.test",
    country: "US",
    tenantName: "Co",
    commissionRate: options.rate === undefined ? "12" : options.rate
  });
  const pinia = createPinia();
  setActivePinia(pinia);
  useSessionStore().setSession({ token: "t", expiresAt: Date.now() + 3_600_000 });
  wrapper = mount(HomePage, {
    attachTo: document.body,
    global: { plugins: [ElementPlus, i18n, pinia] }
  });
  await flushPromises();
  return wrapper;
}
const text = (selector: string) => wrapper!.get(`[data-test="${selector}"]`).text();

beforeEach(() => {
  i18n.global.locale.value = "en-US";
  push.mockReset();
  chart = { setOption: vi.fn(), resize: vi.fn(), dispose: vi.fn() };
  vi.mocked(createChart).mockReturnValue(chart as unknown as ChartHandle);
  vi.mocked(getSalesTotal).mockResolvedValue({
    salesCny: "10000.00",
    asOf: "2026-11-01T08:00:00Z"
  });
  vi.mocked(getCreatorAnalytics).mockResolvedValue(overview());
  vi.useFakeTimers({ toFake: ["Date"] });
  vi.setSystemTime(new Date("2026-11-01T04:00:00Z"));
});
afterEach(() => {
  wrapper?.unmount();
  wrapper = undefined;
  document.body.innerHTML = "";
  vi.useRealTimers();
  vi.clearAllMocks();
});

describe("workspace section 1: total income", () => {
  it("shows the estimated total as the sum of commission income and advances received", async () => {
    await mountHome({
      fundSummary: fund({
        receivedPrepayCny: "500.00",
        prepayDeductionCny: "200.00",
        prepayOutstandingCny: "300.00",
        availableCny: "800.00"
      })
    });
    // commission income 1000 - 200 - 300 = 500; total 500 + 500 = 1000
    expect(text("income-commission")).toBe("¥500.00");
    expect(text("income-prepay")).toBe("¥500.00");
    expect(text("income-total")).toBe("¥1,000.00");
    expect(text("available-amount")).toBe("¥800.00");
  });

  it("keeps advances received at ¥0.00 when there is no advance", async () => {
    await mountHome();
    expect(text("income-prepay")).toBe("¥0.00");
    expect(text("income-commission")).toBe("¥1,000.00");
    expect(text("income-total")).toBe("¥1,000.00");
  });

  it("shows a negative commission income in red when the commission cannot cover the advance", async () => {
    await mountHome({
      fundSummary: fund({
        estimatedCommissionCny: "100.00",
        availableCny: "-50.00",
        frozenCny: "0.00",
        withdrawingCny: "0.00",
        withdrawnCny: "0.00",
        receivedPrepayCny: "400.00",
        prepayDeductionCny: "150.00",
        prepayOutstandingCny: "250.00"
      })
    });
    expect(text("income-commission")).toBe("-¥300.00");
    expect(wrapper!.get('[data-test="income-commission"]').classes()).toContain("negative");
    // Withdrawable amount is negative too.
    expect(text("available-amount")).toBe("-¥50.00");
    expect(wrapper!.get('[data-test="available-amount"]').classes()).toContain("negative");
    expect(text("income-total")).toBe("¥100.00");
  });

  it("opens the withdrawal dialog of the funds page from the withdraw button", async () => {
    await mountHome();
    await wrapper!.get('[data-test="withdraw"]').trigger("click");
    expect(push).toHaveBeenCalledWith({ path: "/funds", query: { apply: "1" } });
  });

  it("states the as-of time", async () => {
    await mountHome();
    expect(text("as-of")).toContain("As of");
  });
});

describe("workspace section 2: commission details", () => {
  it("adds up the four cards to the estimated commission and settles before the advance", async () => {
    await mountHome({
      fundSummary: fund({
        estimatedCommissionCny: "1000.00",
        availableCny: "400.00",
        prepayDeductionCny: "100.00",
        prepayOutstandingCny: "50.00",
        receivedPrepayCny: "150.00",
        frozenCny: "200.00",
        withdrawingCny: "100.00",
        withdrawnCny: "200.00"
      })
    });
    // settled = available 400 + deducted 100 = 500; 500 + 200 + 100 + 200 = 1000
    expect(text("card-settled-amount")).toBe("¥500.00");
    expect(text("card-frozen-amount")).toBe("¥200.00");
    expect(text("card-occupied-amount")).toBe("¥100.00");
    expect(text("card-commissionPaid-amount")).toBe("¥200.00");
    expect(text("estimated-amount")).toBe("¥1,000.00");
    // The withdrawable amount is the settled commission minus the repaid advance.
    expect(text("available-amount")).toBe("¥400.00");
  });

  it("shows the cumulative sales and the account's commission rate", async () => {
    await mountHome({ rate: "12" });
    expect(text("sales-amount")).toBe("¥10,000.00");
    expect(text("commission-rate")).toContain("Current commission rate 12%");
  });

  it("uses the stored rate when refreshing the profile fails", async () => {
    vi.mocked(getMe).mockRejectedValue(new Error("offline"));
    const pinia = createPinia();
    setActivePinia(pinia);
    useSessionStore().setSession({
      token: "t",
      expiresAt: Date.now() + 3_600_000,
      kol: {
        kolCode: "K1",
        name: "Creator",
        email: "c@example.test",
        country: "US",
        tenantName: "Co",
        commissionRate: "8"
      }
    });
    vi.mocked(getFundSummary).mockResolvedValue({ fundSummary: fund() });
    wrapper = mount(HomePage, {
      attachTo: document.body,
      global: { plugins: [ElementPlus, i18n, pinia] }
    });
    await flushPromises();
    expect(text("commission-rate")).toContain("8%");
  });

  it("shows the existing pending text when no rate is configured, never a derived rate", async () => {
    await mountHome({ rate: null });
    expect(text("commission-rate")).toContain(i18n.global.t("account.ratePending"));
    expect(text("commission-rate")).not.toContain("%");
  });

  it.each([
    ["settled", "/orders"],
    ["frozen", "/orders"],
    ["occupied", "/funds"],
    ["commissionPaid", "/funds"]
  ])("sends the %s card's bottom button to %s", async (key, target) => {
    await mountHome();
    await wrapper!.get(`[data-test="card-${key}-action"]`).trigger("click");
    expect(push).toHaveBeenCalledWith(target);
  });

  it("uses the owner's wording for the four definitions and the footnote", async () => {
    await mountHome();
    const zh = (key: string) => i18n.global.t(key, {}, { locale: "zh-CN" });
    expect(zh("home.settledCaption")).toBe("已结算且未申请提现的佣金，未抵扣预付款");
    expect(zh("home.frozenCaption")).toBe("未结算订单的佣金金额");
    expect(zh("home.withdrawingCaption")).toBe("已提交提现申请待打款的金额");
    expect(zh("home.withdrawnCaption")).toBe("已完成打款");
    expect(zh("home.detailsFootnote")).toBe(
      "以上四项合计等于预估总佣金。历史订单按下单时的佣金比例计算。"
    );
    expect(wrapper!.text()).toContain(i18n.global.t("home.detailsFootnote"));
  });
});

describe("workspace load failure", () => {
  it("shows an error with a retry and no zero amounts when the figures fail", async () => {
    vi.mocked(getFundSummary).mockRejectedValue(new Error("down"));
    vi.mocked(getMe).mockResolvedValue({} as never);
    const pinia = createPinia();
    setActivePinia(pinia);
    wrapper = mount(HomePage, {
      attachTo: document.body,
      global: { plugins: [ElementPlus, i18n, pinia] }
    });
    await flushPromises();
    expect(wrapper.find('[data-test="load-failed"]').exists()).toBe(true);
    expect(wrapper.find('[data-test="income-total"]').exists()).toBe(false);
    expect(wrapper.find('[data-test="estimated-amount"]').exists()).toBe(false);
    vi.mocked(getFundSummary).mockResolvedValue({ fundSummary: fund() });
    await wrapper.get('[data-test="retry"]').trigger("click");
    await flushPromises();
    expect(wrapper.find('[data-test="load-failed"]').exists()).toBe(false);
    expect(text("income-total")).toBe("¥1,000.00");
  });

  it("fails the cumulative sales together with the figures instead of showing ¥0.00", async () => {
    vi.mocked(getSalesTotal).mockRejectedValue(new Error("down"));
    await mountHome();
    expect(wrapper!.find('[data-test="load-failed"]').exists()).toBe(true);
    expect(wrapper!.find('[data-test="sales-amount"]').exists()).toBe(false);
  });
});

describe("workspace section 3: sales trend", () => {
  it("defaults to the last 30 days in Beijing time and says so", async () => {
    await mountHome();
    // 2026-11-01 04:00 UTC is 12:00 on 11-01 in Beijing; 30 days including today start on 10-03.
    expect(getCreatorAnalytics).toHaveBeenCalledWith({
      from: "2026-10-03T00:00:00+08:00",
      to: "2026-11-02T00:00:00+08:00"
    });
    expect(text("trend-zone")).toBe("Beijing time");
  });

  it("draws GMV bars and commission line and totals the period in the legend", async () => {
    vi.mocked(getCreatorAnalytics).mockResolvedValue(
      overview([
        { date: "2026-10-30", salesCny: "1000.10", commissionCny: "120.01", orderCount: 3 },
        { date: "2026-11-01", salesCny: "0.20", commissionCny: "0.02", orderCount: 1 }
      ])
    );
    await mountHome();
    expect(text("legend-gmv")).toBe("¥1,000.30");
    expect(text("legend-commission")).toBe("¥120.03");
    const applied = chart.setOption.mock.calls.at(-1)![0];
    expect(applied.series.map((series: { type: string }) => series.type)).toEqual(["bar", "line"]);
    // 30 days, zero filled: two days with orders carry their amounts.
    expect(applied.series[0].data).toHaveLength(30);
    expect(applied.series[0].data.at(-1)).toBe(0.2);
    expect(applied.series[0].data.at(-3)).toBe(1000.1);
    expect(applied.series[1].yAxisIndex).toBe(1);
  });

  it("shows the empty state when the period has no order", async () => {
    await mountHome();
    expect(wrapper!.find('[data-test="chart-empty"]').text()).toBe(i18n.global.t("home.noTrend"));
    expect(createChart).not.toHaveBeenCalled();
  });

  it("shows a retry when the trend fails and loads it again", async () => {
    vi.mocked(getCreatorAnalytics).mockRejectedValueOnce(new Error("down"));
    await mountHome();
    expect(wrapper!.find('[data-test="chart-failed"]').exists()).toBe(true);
    expect(wrapper!.find('[data-test="trend-legend"]').exists()).toBe(false);
    await wrapper!.get('[data-test="chart-retry"]').trigger("click");
    await flushPromises();
    expect(wrapper!.find('[data-test="chart-failed"]').exists()).toBe(false);
    expect(getCreatorAnalytics).toHaveBeenCalledTimes(2);
  });

  it("requests the last 7 days when the choice changes", async () => {
    await mountHome();
    wrapper!.findComponent({ name: "ElRadioGroup" }).vm.$emit("change", "last7");
    await flushPromises();
    expect(getCreatorAnalytics).toHaveBeenLastCalledWith({
      from: "2026-10-26T00:00:00+08:00",
      to: "2026-11-02T00:00:00+08:00"
    });
  });

  it("requests a natural month per day with the month's Beijing boundaries", async () => {
    await mountHome();
    const group = wrapper!.findComponent({ name: "ElRadioGroup" });
    group.vm.$emit("change", "month");
    await flushPromises();
    // The current month stops at today.
    expect(getCreatorAnalytics).toHaveBeenLastCalledWith({
      from: "2026-11-01T00:00:00+08:00",
      to: "2026-11-02T00:00:00+08:00"
    });
    const picker = wrapper!.findComponent({ name: "ElDatePicker" });
    picker.vm.$emit("update:modelValue", "2026-10");
    await flushPromises();
    expect(getCreatorAnalytics).toHaveBeenLastCalledWith({
      from: "2026-10-01T00:00:00+08:00",
      to: "2026-11-01T00:00:00+08:00"
    });
  });

  it("groups a range longer than 30 days by month and blocks one longer than 366 days", async () => {
    vi.mocked(getCreatorAnalytics).mockResolvedValue(
      overview([{ date: "2026-10-15", salesCny: "50.00", commissionCny: "5.00", orderCount: 1 }])
    );
    await mountHome();
    wrapper!.findComponent({ name: "ElRadioGroup" }).vm.$emit("change", "range");
    await flushPromises();
    const picker = () => wrapper!.findComponent({ name: "ElDatePicker" });
    picker().vm.$emit("update:modelValue", ["2026-09-01", "2026-10-31"]);
    await flushPromises();
    const applied = chart.setOption.mock.calls.at(-1)![0];
    expect(applied.xAxis.axisLabel).toBeDefined();
    expect(applied.xAxis.data).toEqual(["2026-09", "2026-10"]);

    vi.mocked(getCreatorAnalytics).mockClear();
    picker().vm.$emit("update:modelValue", ["2025-10-30", "2026-10-31"]);
    await flushPromises();
    expect(getCreatorAnalytics).not.toHaveBeenCalled();
    expect(wrapper!.get('[data-test="trend-error"]').text()).toBe(
      i18n.global.t("home.rangeTooLong")
    );
  });

  it("reloads every section from the refresh button", async () => {
    await mountHome();
    await wrapper!.get('[data-test="refresh"]').trigger("click");
    await flushPromises();
    expect(getFundSummary).toHaveBeenCalledTimes(2);
    expect(getSalesTotal).toHaveBeenCalledTimes(2);
    expect(getCreatorAnalytics).toHaveBeenCalledTimes(2);
  });
});
