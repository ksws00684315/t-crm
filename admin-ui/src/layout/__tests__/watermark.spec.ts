import { flushPromises, mount } from "@vue/test-utils";
import ElementPlus, { ElWatermark } from "element-plus";
import { createPinia, setActivePinia } from "pinia";
import { nextTick } from "vue";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { createMemoryHistory, createRouter } from "vue-router";
import type { UserVO } from "@/api/types";
import { i18n } from "@/locales";
import { routes } from "@/router/routes";
import { useUserStore } from "@/stores/user";
import { watermarkInk, watermarkText } from "@/utils/watermark";
import Layout from "../index.vue";

const userFixture: UserVO = {
  id: "1",
  username: "ada",
  nickname: "Ada",
  email: null,
  role: "SUPER_ADMIN",
  postTemplate: null,
  permissions: [],
  dataScope: "ALL",
  tenantId: "1",
  tenantName: "北美公司",
  status: 1,
  lastLoginTime: null,
  createTime: "2026-10-05T00:00:00Z"
};

function stubCanvas(): void {
  vi.spyOn(HTMLCanvasElement.prototype, "getContext").mockImplementation(() => {
    return {
      save() {
        return undefined;
      },
      translate() {
        return undefined;
      },
      rotate() {
        return undefined;
      },
      drawImage() {
        return undefined;
      },
      fillText() {
        return undefined;
      },
      measureText() {
        return {
          width: 40,
          actualBoundingBoxAscent: 8,
          actualBoundingBoxDescent: 2,
          fontBoundingBoxAscent: 10,
          fontBoundingBoxDescent: 2
        };
      },
      font: "",
      fillStyle: "",
      textAlign: "center",
      textBaseline: "middle"
    } as unknown as CanvasRenderingContext2D;
  });
  vi.spyOn(HTMLCanvasElement.prototype, "toDataURL").mockReturnValue("data:image/png;base64,aa");
}

describe("content watermark", () => {
  beforeEach(() => {
    localStorage.clear();
    sessionStorage.clear();
    document.documentElement.style.removeProperty("--tcrm-color-text");
    vi.restoreAllMocks();
    stubCanvas();
    setActivePinia(createPinia());
  });

  async function mountLayout() {
    const user = useUserStore();
    user.user = userFixture;
    const router = createRouter({ history: createMemoryHistory(), routes });
    await router.push("/home");
    const wrapper = mount(Layout, {
      global: { plugins: [ElementPlus, i18n, router], stubs: { RouterView: true } }
    });
    await flushPromises();
    return wrapper;
  }

  it("prints the nickname, username, and date on the main area only", async () => {
    const expected = watermarkText("Ada", "ada", new Date());
    const wrapper = await mountLayout();
    const mark = wrapper.get("[data-test=watermark]");
    expect(wrapper.get(".layout__main").element.contains(mark.element)).toBe(true);
    expect(wrapper.get(".layout__aside").element.contains(mark.element)).toBe(false);
    expect(wrapper.get(".layout__header").element.contains(mark.element)).toBe(false);
    const watermark = wrapper.getComponent(ElWatermark);
    expect(watermark.props("content")).toBe(expected);
    expect(watermark.props("rotate")).toBe(-20);
    expect(mark.find("button").exists()).toBe(false);
  });

  it("restores the layer when it is removed and does not offer a way to turn it off", async () => {
    const wrapper = await mountLayout();
    const root = wrapper.get("[data-test=watermark]").element;
    const overlay = root.querySelector("div");
    expect(overlay).toBeInstanceOf(HTMLDivElement);
    expect(overlay?.getAttribute("style")).toContain("pointer-events: none");
    overlay?.remove();
    await nextTick();
    await flushPromises();
    const restored = root.querySelector("div");
    expect(restored).toBeInstanceOf(HTMLDivElement);
    expect(restored?.getAttribute("style")).toContain("pointer-events: none");
    expect(wrapper.find("[data-test=watermark-close]").exists()).toBe(false);
  });

  it("uses the fixed ink color (no theme switch since task 32)", async () => {
    const wrapper = await mountLayout();
    expect(wrapper.getComponent(ElWatermark).props("font")).toMatchObject({
      color: watermarkInk("#15181d"),
      fontSize: 14
    });
  });
});
