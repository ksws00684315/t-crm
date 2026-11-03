import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { flushPromises, mount } from "@vue/test-utils";
import ElementPlus from "element-plus";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import * as authApi from "@/api/auth";
import { i18n } from "@/locales";
import CaptchaInput from "../CaptchaInput.vue";

vi.mock("@/api/auth");

describe("CaptchaInput", () => {
  beforeEach(() => {
    vi.useFakeTimers();
    vi.mocked(authApi.createCaptcha).mockReset();
    let seq = 0;
    vi.mocked(authApi.createCaptcha).mockImplementation(async () => ({
      enabled: true,
      captchaId: `c${++seq}`,
      image: `data:image/png;base64,${seq}`,
      expiresIn: 20
    }));
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  function mountCaptcha() {
    const wrapper = mount(CaptchaInput, {
      props: {
        modelValue: "",
        "onUpdate:modelValue": (value: string) => wrapper.setProps({ modelValue: value })
      },
      global: { plugins: [ElementPlus, i18n] }
    });
    return wrapper;
  }

  it("loads a captcha on mount and refreshes about 10s before expiry", async () => {
    const wrapper = mountCaptcha();
    await flushPromises();
    expect(authApi.createCaptcha).toHaveBeenCalledTimes(1);
    expect(wrapper.get("[data-test=captcha-image]").attributes("src")).toContain("base64,1");

    await wrapper.get("input[data-test=captcha-input]").setValue("ab7k");
    // expiresIn=20 → refresh at 10s (20 - 10).
    await vi.advanceTimersByTimeAsync(9_999);
    expect(authApi.createCaptcha).toHaveBeenCalledTimes(1);
    await vi.advanceTimersByTimeAsync(1);
    await flushPromises();
    expect(authApi.createCaptcha).toHaveBeenCalledTimes(2);
    expect(wrapper.get("[data-test=captcha-image]").attributes("src")).toContain("base64,2");
    expect((wrapper.get("input[data-test=captcha-input]").element as HTMLInputElement).value).toBe(
      ""
    );
    wrapper.unmount();
  });

  it("click refresh still works and resets the auto-refresh timer", async () => {
    const wrapper = mountCaptcha();
    await flushPromises();
    await vi.advanceTimersByTimeAsync(5_000);
    await wrapper.get("[data-test=captcha-refresh]").trigger("click");
    await flushPromises();
    expect(authApi.createCaptcha).toHaveBeenCalledTimes(2);

    // The previous 10s deadline must not fire; the new one is again at +10s from now.
    await vi.advanceTimersByTimeAsync(9_999);
    expect(authApi.createCaptcha).toHaveBeenCalledTimes(2);
    await vi.advanceTimersByTimeAsync(1);
    await flushPromises();
    expect(authApi.createCaptcha).toHaveBeenCalledTimes(3);
    wrapper.unmount();
  });

  it("clears the timer on unmount so a closed dialog does not keep refreshing", async () => {
    const wrapper = mountCaptcha();
    await flushPromises();
    expect(authApi.createCaptcha).toHaveBeenCalledTimes(1);
    wrapper.unmount();
    await vi.advanceTimersByTimeAsync(60_000);
    await flushPromises();
    expect(authApi.createCaptcha).toHaveBeenCalledTimes(1);
  });

  it("does not schedule a timer when captchas are disabled", async () => {
    vi.mocked(authApi.createCaptcha).mockResolvedValue({
      enabled: false,
      captchaId: null,
      image: null,
      expiresIn: 0
    });
    const wrapper = mountCaptcha();
    await flushPromises();
    expect(wrapper.find("[data-test=captcha]").exists()).toBe(false);
    await vi.advanceTimersByTimeAsync(60_000);
    expect(authApi.createCaptcha).toHaveBeenCalledTimes(1);
    wrapper.unmount();
  });

  it("keeps the failure message whole and retries from the full-width box", async () => {
    vi.mocked(authApi.createCaptcha).mockRejectedValueOnce(new Error("offline"));
    const wrapper = mountCaptcha();
    await flushPromises();

    const retry = wrapper.get("[data-test=captcha-refresh]");
    const failed = wrapper.get("[data-test=captcha-failed]");
    expect(retry.classes()).toContain("is-failed");
    expect(failed.text()).toBe(i18n.global.t("captcha.loadFailed"));
    expect(failed.element.closest("button")).toBe(retry.element);
    expect(wrapper.find("[data-test=captcha-image]").exists()).toBe(false);

    await retry.trigger("click");
    await flushPromises();
    expect(wrapper.find("[data-test=captcha-failed]").exists()).toBe(false);
    expect(wrapper.get("[data-test=captcha-image]").attributes("src")).toContain("base64,1");
    wrapper.unmount();
  });
});

describe("CaptchaInput failure layout", () => {
  const source = readFileSync(resolve(__dirname, "../CaptchaInput.vue"), "utf8");
  const scoped = source.slice(
    source.indexOf("<style scoped>") + "<style scoped>".length,
    source.indexOf("</style>")
  );

  function declarationsFor(selector: string): string {
    const index = scoped.indexOf(selector);
    expect(index, `${selector} must stay in the captcha stylesheet`).toBeGreaterThanOrEqual(0);
    return scoped.slice(index, scoped.indexOf("}", index));
  }

  it("wraps the failed box onto its own full-width row so the text is not clipped", () => {
    expect(declarationsFor(".captcha {")).toContain("flex-wrap: wrap");
    const failed = declarationsFor(".captcha__image.is-failed");
    expect(failed).toContain("flex: 1 0 100%");
    expect(failed).toContain("overflow: visible");
    expect(failed).toContain("white-space: nowrap");
  });

  it("keeps the failure text at the body size and on one line", () => {
    const text = declarationsFor(".captcha__failed");
    expect(text).toContain("font-size: var(--tcrm-font-size-body)");
    expect(text).toContain("white-space: nowrap");
  });
});
