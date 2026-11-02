import { flushPromises, mount } from "@vue/test-utils";
import ElementPlus from "element-plus";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { i18n } from "@/locales";
import { createChart, type ChartHandle } from "@/utils/echarts";
import ChartPanel from "../ChartPanel.vue";

// ECharts cannot render in jsdom (no canvas): the registration file is replaced (规范 11 §2).
vi.mock("@/utils/echarts", () => ({ createChart: vi.fn() }));

let handle: {
  setOption: ReturnType<typeof vi.fn>;
  resize: ReturnType<typeof vi.fn>;
  dispose: ReturnType<typeof vi.fn>;
};
const option = {
  xAxis: { type: "category", data: ["a"] },
  yAxis: [{ type: "value" }, { type: "value", splitLine: { show: false } }],
  series: [{ type: "bar", data: [1] }]
};

function mountPanel(props: Record<string, unknown> = {}) {
  return mount(ChartPanel, {
    attachTo: document.body,
    props: { option, label: "Trend chart", ...props },
    global: { plugins: [ElementPlus, i18n] }
  });
}

beforeEach(() => {
  i18n.global.locale.value = "en-US";
  handle = { setOption: vi.fn(), resize: vi.fn(), dispose: vi.fn() };
  vi.mocked(createChart).mockReturnValue(handle as unknown as ChartHandle);
  const root = document.documentElement.style;
  root.setProperty("--tcrm-color-primary", "primary-token");
  root.setProperty("--tcrm-color-success", "success-token");
  root.setProperty("--tcrm-color-border", "border-token");
  root.setProperty("--tcrm-color-text", "text-token");
  root.setProperty("--tcrm-color-text-secondary", "secondary-token");
  root.setProperty("--tcrm-color-card", "card-token");
  root.setProperty("--tcrm-font-size-body", "14px");
  root.setProperty("--tcrm-font-sans", "sans-token");
});
afterEach(() => {
  document.body.innerHTML = "";
  document.documentElement.removeAttribute("style");
});

describe("ChartPanel", () => {
  it("creates exactly one instance on the container and themes the option from tokens", async () => {
    const wrapper = mountPanel();
    await flushPromises();
    expect(createChart).toHaveBeenCalledTimes(1);
    expect(vi.mocked(createChart).mock.calls[0][0]).toBe(
      wrapper.get('[data-test="chart-canvas"]').element
    );
    const [applied, notMerge] = handle.setOption.mock.calls[0];
    expect(notMerge).toBe(true);
    expect(applied.color).toEqual(["primary-token", "success-token"]);
    expect(applied.textStyle).toMatchObject({ fontFamily: "sans-token", fontSize: 14 });
    expect(applied.tooltip).toMatchObject({
      backgroundColor: "card-token",
      borderColor: "border-token",
      textStyle: { color: "text-token", fontSize: 14 }
    });
    // Each axis (object or array entry) is themed, and the page's own flags survive.
    expect(applied.xAxis.axisLine.lineStyle.color).toBe("border-token");
    expect(applied.yAxis).toHaveLength(2);
    expect(applied.yAxis[1].splitLine.show).toBe(false);
    expect(applied.yAxis[1].splitLine.lineStyle.color).toBe("border-token");
    expect(applied.yAxis[0].axisLabel).toMatchObject({ color: "secondary-token", fontSize: 14 });
    expect(applied.series).toEqual(option.series);
    expect(wrapper.get('[data-test="chart-canvas"]').attributes("aria-label")).toBe("Trend chart");
  });

  it("applies a new option to the same instance and follows the container size", async () => {
    const resizeCallbacks: (() => void)[] = [];
    vi.stubGlobal(
      "ResizeObserver",
      class {
        constructor(callback: () => void) {
          resizeCallbacks.push(callback);
        }
        observe() {}
        disconnect() {}
      }
    );
    const wrapper = mountPanel();
    await flushPromises();
    await wrapper.setProps({ option: { ...option, series: [{ type: "bar", data: [2] }] } });
    await flushPromises();
    expect(createChart).toHaveBeenCalledTimes(1);
    expect(handle.setOption).toHaveBeenCalledTimes(2);
    resizeCallbacks[0]();
    expect(handle.resize).toHaveBeenCalled();
    vi.unstubAllGlobals();
  });

  it("disposes the instance when unmounted", async () => {
    const wrapper = mountPanel();
    await flushPromises();
    wrapper.unmount();
    expect(handle.dispose).toHaveBeenCalledTimes(1);
  });

  it("shows loading, then empty, with no instance created", async () => {
    const loading = mountPanel({ option: null, loading: true });
    expect(loading.find('[data-test="chart-loading"]').text()).toBe(i18n.global.t("chart.loading"));
    const empty = mountPanel({ empty: true, emptyText: "Nothing here" });
    expect(empty.find('[data-test="chart-empty"]').text()).toBe("Nothing here");
    const plain = mountPanel({ option: null });
    expect(plain.find('[data-test="chart-empty"]').text()).toBe(i18n.global.t("chart.empty"));
    await flushPromises();
    expect(createChart).not.toHaveBeenCalled();
  });

  it("shows the failure with a retry that asks the page to reload", async () => {
    const wrapper = mountPanel({ failed: true });
    expect(wrapper.get('[data-test="chart-failed"]').text()).toContain(
      i18n.global.t("chart.failed")
    );
    await wrapper.get('[data-test="chart-retry"]').trigger("click");
    expect(wrapper.emitted("retry")).toHaveLength(1);
    expect(createChart).not.toHaveBeenCalled();
  });

  it("keeps the chart on screen while it refreshes, and rebuilds it after a failure", async () => {
    const wrapper = mountPanel({ loading: true });
    await flushPromises();
    expect(wrapper.find('[data-test="chart-loading"]').exists()).toBe(false);
    expect(createChart).toHaveBeenCalledTimes(1);
    await wrapper.setProps({ loading: false, failed: true });
    await flushPromises();
    expect(handle.dispose).toHaveBeenCalledTimes(1);
    await wrapper.setProps({ failed: false });
    await flushPromises();
    expect(createChart).toHaveBeenCalledTimes(2);
  });
});
