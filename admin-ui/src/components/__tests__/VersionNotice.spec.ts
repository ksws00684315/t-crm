import { mount } from "@vue/test-utils";
import ElementPlus from "element-plus";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { i18n } from "@/locales";
import { VERSION_SNOOZE_KEY, VERSION_SNOOZE_MS } from "@/utils/version";
import VersionNotice from "../VersionNotice.vue";

const harness = vi.hoisted(() => ({
  prompt: null as null | (() => void)
}));

vi.mock("@/utils/version", async () => {
  const actual = await vi.importActual<Record<string, unknown>>("@/utils/version");
  return {
    ...actual,
    monitorVersion: (options: { onPrompt: () => void }) => {
      harness.prompt = options.onPrompt;
      return () => undefined;
    }
  };
});

describe("VersionNotice", () => {
  beforeEach(() => {
    localStorage.clear();
    harness.prompt = null;
    vi.restoreAllMocks();
  });

  function mountNotice() {
    return mount(VersionNotice, { global: { plugins: [ElementPlus, i18n] } });
  }

  it("shows the update card when a new version is published", async () => {
    const wrapper = mountNotice();
    expect(wrapper.find("[data-test=version-notice]").exists()).toBe(false);
    harness.prompt?.();
    await wrapper.vm.$nextTick();
    expect(wrapper.get("[data-test=version-notice]").text()).toContain(
      "系统已更新，刷新页面以使用新版本。已打开的标签会保留。"
    );
  });

  it("snoozes the card for 30 minutes", async () => {
    vi.spyOn(Date, "now").mockReturnValue(1_700_000_000_000);
    const wrapper = mountNotice();
    harness.prompt?.();
    await wrapper.vm.$nextTick();
    await wrapper.get("[data-test=version-later]").trigger("click");
    expect(wrapper.find("[data-test=version-notice]").exists()).toBe(false);
    expect(localStorage.getItem(VERSION_SNOOZE_KEY)).toBe(
      String(1_700_000_000_000 + VERSION_SNOOZE_MS)
    );
  });

  it("reloads the page from the refresh button", async () => {
    const reload = vi.fn();
    vi.stubGlobal("location", { reload });
    const wrapper = mountNotice();
    harness.prompt?.();
    await wrapper.vm.$nextTick();
    await wrapper.get("[data-test=version-refresh]").trigger("click");
    expect(reload).toHaveBeenCalledOnce();
    vi.unstubAllGlobals();
  });
});
