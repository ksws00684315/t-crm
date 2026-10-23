import { flushPromises, mount, type VueWrapper, type DOMWrapper } from "@vue/test-utils";
import ElementPlus, { ElMessageBox } from "element-plus";
import { createPinia } from "pinia";
import { beforeEach, afterEach, describe, expect, it, vi } from "vitest";
import * as api from "@/api/system/site-config";
import { i18n } from "@/locales";
import { useLocaleStore } from "@/stores/locale";
import { loadSiteConfig } from "@/stores/site-config";
import { notifyError } from "@/utils/notify";
import ConfigEditDialog from "../ConfigEditDialog.vue";
import SystemConfigPage from "../index.vue";

vi.mock("@/api/system/site-config");
vi.mock("@/stores/site-config", () => ({
  loadSiteConfig: vi.fn(),
  siteName: { value: "SparkTrack" },
  uploadBaseUrl: { value: "" }
}));
vi.mock("@/utils/notify", () => ({ notifyError: vi.fn() }));

function item(overrides: Partial<api.SystemConfigItem>): api.SystemConfigItem {
  return {
    key: "site.name",
    group: "site",
    type: "string",
    value: "SparkTrack",
    defaultValue: "SparkTrack",
    overridden: false,
    min: null,
    max: null,
    nameKey: "systemConfig.items.site_name",
    ...overrides
  };
}

const groups: api.SystemConfigGroup[] = [
  {
    group: "site",
    items: [
      item({}),
      item({
        key: "mail_from",
        value: "",
        defaultValue: "",
        nameKey: "systemConfig.items.mail_from"
      })
    ]
  },
  {
    group: "business",
    items: [
      item({
        key: "copy_token_default_quota",
        group: "business",
        type: "integer",
        value: "100",
        defaultValue: "50",
        overridden: true,
        min: 1,
        max: 1000,
        nameKey: "systemConfig.items.copy_token_default_quota"
      })
    ]
  },
  {
    group: "url",
    items: [
      item({
        key: "url.admin",
        group: "url",
        type: "url",
        value: "https://admin.example.com",
        nameKey: "systemConfig.items.url_admin"
      })
    ]
  }
];

const wrappers: VueWrapper[] = [];

async function mountPage(): Promise<VueWrapper> {
  // A fresh pinia per mount keeps the locale store isolated between tests.
  const wrapper = mount(SystemConfigPage, {
    attachTo: document.body,
    global: { plugins: [ElementPlus, i18n, createPinia()], stubs: { teleport: true } }
  });
  wrappers.push(wrapper);
  await flushPromises();
  return wrapper;
}

function headerTexts(wrapper: VueWrapper): string[] {
  return wrapper
    .findAll(".el-table__header-wrapper th .cell")
    .map(cell => cell.text().trim())
    .filter(Boolean);
}

/** Row buttons only: el-table's hidden measuring copy renders the slots without a row. */
function rowButtons(wrapper: VueWrapper): DOMWrapper<Element>[] {
  return wrapper.findAll(".el-table__body-wrapper [data-test^=config-edit-]");
}

beforeEach(() => {
  localStorage.clear();
  document.body.innerHTML = "";
  i18n.global.locale.value = "zh-CN";
  vi.clearAllMocks();
  vi.mocked(api.getSystemConfigs).mockResolvedValue(groups);
});

afterEach(() => {
  wrappers.splice(0).forEach(wrapper => wrapper.unmount());
});

describe("platform system configuration key-value table (task 53 §2)", () => {
  it("renders the four columns in order as one flat table", async () => {
    const wrapper = await mountPage();

    expect(headerTexts(wrapper)).toEqual(["配置键", "说明", "值", "操作"]);
    expect(rowButtons(wrapper)).toHaveLength(groups.reduce((sum, g) => sum + g.items.length, 0));
    // No group headings, cards, or "modified" tags anymore.
    expect(wrapper.text()).not.toContain("站点");
    expect(wrapper.text()).not.toContain("已修改");
    // An empty value renders as the dash placeholder.
    expect(wrapper.get('[data-test="config-value-mail_from"]').text()).toBe("—");
    expect(wrapper.get('[data-test="config-value-url.admin"]').text()).toBe(
      "https://admin.example.com"
    );
  });

  it("filters by key or description and resets from the chip", async () => {
    const wrapper = await mountPage();

    await wrapper.get('[data-test="config-keyword"]').setValue("COPY_TOKEN");
    await wrapper.get('[data-test="search"]').trigger("click");
    expect(rowButtons(wrapper)).toHaveLength(1);
    expect(wrapper.find('[data-test="config-value-copy_token_default_quota"]').exists()).toBe(true);

    await wrapper.get('[data-test="config-keyword"]').setValue("邮件发件人");
    await wrapper.get('[data-test="search"]').trigger("click");
    expect(wrapper.find('[data-test="config-value-mail_from"]').exists()).toBe(true);
    expect(wrapper.find('[data-test="config-value-site.name"]').exists()).toBe(false);

    // The applied keyword shows as a chip; removing it restores the full list.
    expect(wrapper.text()).toContain("关键字");
    await wrapper.get('[data-test="search-clear"]').trigger("click");
    expect(rowButtons(wrapper)).toHaveLength(4);
  });

  it("opens the edit dialog with the stored value plus default and range", async () => {
    const wrapper = await mountPage();

    await wrapper.get('[data-test="config-edit-copy_token_default_quota"]').trigger("click");
    const dialog = wrapper.findComponent(ConfigEditDialog);
    expect(dialog.props("modelValue")).toBe(true);
    expect(wrapper.get('[data-test="config-dialog-key"]').text()).toBe("copy_token_default_quota");
    expect(wrapper.get('[data-test="config-dialog-name"]').text()).toBe("复制令牌默认额度");
    expect(wrapper.find('[data-test="config-value-input"]').exists()).toBe(true);
    expect(
      (wrapper.get('[data-test="config-value-input"]').element as HTMLInputElement).value
    ).toBe("100");
    expect(wrapper.get('[data-test="config-value-hint"]').text()).toContain("默认值：50");
    expect(wrapper.get('[data-test="config-value-hint"]').text()).toContain("允许范围：1–1000");
    // The stored value differs from the default, so restoring is available.
    expect(wrapper.get('[data-test="config-restore"]').attributes("disabled")).toBeUndefined();
  });

  it("disables restore while the value equals the default", async () => {
    const wrapper = await mountPage();

    await wrapper.get('[data-test="config-edit-site.name"]').trigger("click");
    expect(wrapper.get('[data-test="config-value-hint"]').text()).toContain("默认值：SparkTrack");
    expect(wrapper.get('[data-test="config-value-hint"]').text()).not.toContain("允许范围");
    expect(wrapper.get('[data-test="config-restore"]').attributes("disabled")).toBeDefined();
  });

  it("rejects a non-integer value for a numeric config without sending a request", async () => {
    vi.mocked(api.updateSystemConfig).mockResolvedValue(undefined);
    const wrapper = await mountPage();

    await wrapper.get('[data-test="config-edit-copy_token_default_quota"]').trigger("click");
    await wrapper.get('[data-test="config-value-input"]').setValue("12.5");
    await wrapper.get('[data-test="config-save"]').trigger("click");
    await flushPromises();

    expect(api.updateSystemConfig).not.toHaveBeenCalled();
    expect(wrapper.findComponent(ConfigEditDialog).props("modelValue")).toBe(true);
  });

  it("saves a new value, reloads the list and the site config", async () => {
    vi.mocked(api.updateSystemConfig).mockResolvedValue(undefined);
    const wrapper = await mountPage();
    const loadsAfterMount = vi.mocked(api.getSystemConfigs).mock.calls.length;

    await wrapper.get('[data-test="config-edit-copy_token_default_quota"]').trigger("click");
    await wrapper.get('[data-test="config-value-input"]').setValue("200");
    await wrapper.get('[data-test="config-save"]').trigger("click");
    await flushPromises();

    expect(api.updateSystemConfig).toHaveBeenCalledWith("copy_token_default_quota", "200");
    expect(loadSiteConfig).toHaveBeenCalled();
    expect(vi.mocked(api.getSystemConfigs).mock.calls.length).toBeGreaterThan(loadsAfterMount);
    expect(wrapper.findComponent(ConfigEditDialog).props("modelValue")).toBe(false);
  });

  it("keeps the dialog open and reports when the backend rejects the value", async () => {
    vi.mocked(api.updateSystemConfig).mockRejectedValue(new Error("rejected"));
    const wrapper = await mountPage();

    await wrapper.get('[data-test="config-edit-site.name"]').trigger("click");
    await wrapper.get('[data-test="config-value-input"]').setValue("");
    await wrapper.get('[data-test="config-save"]').trigger("click");
    await flushPromises();

    expect(notifyError).toHaveBeenCalled();
    expect(wrapper.findComponent(ConfigEditDialog).props("modelValue")).toBe(true);
  });

  it("restores the default after a second confirmation", async () => {
    vi.mocked(api.resetSystemConfig).mockResolvedValue(undefined);
    vi.spyOn(ElMessageBox, "confirm").mockResolvedValue("confirm" as never);
    const wrapper = await mountPage();

    await wrapper.get('[data-test="config-edit-copy_token_default_quota"]').trigger("click");
    await wrapper.get('[data-test="config-restore"]').trigger("click");
    await flushPromises();

    expect(ElMessageBox.confirm).toHaveBeenCalledWith(
      "确定将“复制令牌默认额度”恢复为默认值吗？",
      "恢复默认",
      { type: "warning" }
    );
    expect(api.resetSystemConfig).toHaveBeenCalledWith("copy_token_default_quota");
    expect(loadSiteConfig).toHaveBeenCalled();
    expect(wrapper.findComponent(ConfigEditDialog).props("modelValue")).toBe(false);
  });

  it("confirms the Weidian callback warning before editing the admin URL", async () => {
    vi.spyOn(ElMessageBox, "confirm").mockResolvedValue("confirm" as never);
    const wrapper = await mountPage();

    await wrapper.get('[data-test="config-edit-url.admin"]').trigger("click");
    await flushPromises();

    expect(ElMessageBox.confirm).toHaveBeenCalledWith(
      "微店开放平台登记的回调域名需同步修改。",
      "确认修改管理端地址",
      { type: "warning" }
    );
    expect(
      (wrapper.get('[data-test="config-value-input"]').element as HTMLInputElement).value
    ).toBe("https://admin.example.com");
  });

  it("does not open the dialog when the admin URL warning is cancelled", async () => {
    vi.spyOn(ElMessageBox, "confirm").mockRejectedValue("cancel" as never);
    const wrapper = await mountPage();

    await wrapper.get('[data-test="config-edit-url.admin"]').trigger("click");
    await flushPromises();

    expect(ElMessageBox.confirm).toHaveBeenCalled();
    expect(wrapper.findComponent(ConfigEditDialog).props("modelValue")).toBe(false);
  });

  it("renders the table and the dialog in English", async () => {
    useLocaleStore().setLocale("en-US");
    const wrapper = await mountPage();

    expect(headerTexts(wrapper)).toEqual(["Config key", "Description", "Value", "Actions"]);
    expect(wrapper.text()).toContain("System name");

    await wrapper.get('[data-test="config-edit-copy_token_default_quota"]').trigger("click");
    expect(wrapper.get('[data-test="config-dialog-name"]').text()).toBe("Default copy-token quota");
    expect(wrapper.get('[data-test="config-value-hint"]').text()).toContain("Default: 50");
    expect(wrapper.get('[data-test="config-value-hint"]').text()).toContain(
      "Allowed range: 1–1000"
    );
    expect(wrapper.get('[data-test="config-save"]').text()).toContain("Save");
  });

  it("reports a failed list load and does not stay in the loading state", async () => {
    vi.mocked(api.getSystemConfigs).mockRejectedValue(new Error("down"));
    const wrapper = await mountPage();

    expect(notifyError).toHaveBeenCalled();
    expect(wrapper.find(".el-loading-mask").isVisible()).toBe(false);
  });
});
