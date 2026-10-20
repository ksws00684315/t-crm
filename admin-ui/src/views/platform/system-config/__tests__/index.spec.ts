import { flushPromises, mount } from "@vue/test-utils";
import ElementPlus, { ElMessageBox } from "element-plus";
import { beforeEach, describe, expect, it, vi } from "vitest";
import * as api from "@/api/system/site-config";
import { i18n } from "@/locales";
import { loadSiteConfig } from "@/stores/site-config";
import SystemConfigPage from "../index.vue";

vi.mock("@/api/system/site-config");
vi.mock("@/stores/site-config", () => ({
  loadSiteConfig: vi.fn(),
  siteName: { value: "SparkTrack" },
  uploadBaseUrl: { value: "" }
}));

const items: api.SystemConfigGroup[] = [
  {
    group: "url",
    items: [
      {
        key: "url.admin",
        group: "url",
        type: "url",
        value: "https://admin.example.com",
        defaultValue: "https://admin.example.com",
        overridden: false,
        min: null,
        max: null,
        nameKey: "systemConfig.items.url_admin"
      }
    ]
  }
];

const options = { global: { plugins: [ElementPlus, i18n], stubs: { teleport: true } } };

describe("platform system configuration", () => {
  beforeEach(() => {
    i18n.global.locale.value = "zh-CN";
    vi.clearAllMocks();
    vi.mocked(api.getSystemConfigs).mockResolvedValue(items);
  });

  it("renders grouped values, modified state, and default actions", async () => {
    const wrapper = mount(SystemConfigPage, options);
    await flushPromises();

    expect(wrapper.text()).toContain("地址");
    expect(wrapper.text()).toContain("管理端地址");
    expect(wrapper.text()).toContain("https://admin.example.com");
    expect(wrapper.text()).toContain("恢复默认");
  });

  it("confirms the Weidian callback warning before saving admin URL changes", async () => {
    vi.spyOn(ElMessageBox, "confirm").mockResolvedValue("confirm" as never);
    vi.spyOn(ElMessageBox, "prompt").mockResolvedValue({ value: "http://localhost:8831" } as never);
    vi.mocked(api.updateSystemConfig).mockResolvedValue(undefined);
    const wrapper = mount(SystemConfigPage, options);
    await flushPromises();

    await wrapper.find(".config-card .config-card__actions button").trigger("click");
    await flushPromises();

    expect(ElMessageBox.confirm).toHaveBeenCalledWith(
      "微店开放平台登记的回调域名需同步修改。",
      "确认修改管理端地址",
      { type: "warning" }
    );
    expect(api.updateSystemConfig).toHaveBeenCalledWith("url.admin", "http://localhost:8831");
    expect(loadSiteConfig).toHaveBeenCalled();
  });
});
