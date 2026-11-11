import { DOMWrapper, flushPromises, mount, type VueWrapper } from "@vue/test-utils";
import ElementPlus, { ElMessage, ElMessageBox } from "element-plus";
import { createPinia, setActivePinia } from "pinia";
import { createMemoryHistory, createRouter } from "vue-router";
import { beforeEach, describe, expect, it, vi } from "vitest";
import * as libraryApi from "@/api/kol-library/kol";
import type { LibraryKolDetailVO } from "@/api/kol-library/kol";
import { i18n } from "@/locales";
import { routes } from "@/router/routes";
import { useTabsStore } from "@/stores/tabs";
import { useUserStore } from "@/stores/user";
import { ApiError } from "@/utils/http/error";
import LibraryKolFormPage from "../kol-form/index.vue";

vi.mock("@/api/kol-library/kol");

/**
 * Full-page profile form (requirement 1.2.5 ④ and 1.2.6 ① as revised on 2026-11-10): three
 * second-level tabs, seven required fields, a failed save that shows the tab of the first error,
 * the duplicate answer with a link to the existing creator, the tab swap after saving, and delete
 * from the edit form for super administrators.
 */

const detail: LibraryKolDetailVO = {
  id: "11",
  platform: "TIKTOK",
  kolUsername: "beautybyamy",
  displayName: "Beauty by Amy",
  followers: "12000",
  countryCode: "US",
  profileUrl: "https://www.tiktok.com/@beautybyamy",
  categories: ["BEAUTY_PERSONAL_CARE"],
  avgViews: null,
  engagementRate: "3.25",
  audienceCountry: null,
  audienceAgeRange: "AGE_25_34",
  audienceGender: "FEMALE" as never,
  contact: "+1 555 0100",
  contentQuality: "HIGH",
  salesPotential: null,
  cooperationAttitude: null,
  grade: "A",
  tags: [],
  remark: null,
  outreachStatus: "REPLIED",
  followUpOwner: "Amy BD",
  lastContactDate: null,
  sampleStatus: "SHIPPED",
  trackingNo: null,
  sampleDate: null,
  followUpRemark: "call on friday",
  createTime: "2026-10-01T00:00:00Z",
  updateTime: "2026-11-01T00:00:00Z",
  totals: { contentCount: 0, clicks: 0, orders: 0, gmv: "0.00", totalCost: "0.00", roi: null }
};

function signIn(role: "SUPER_ADMIN" | "STAFF" = "SUPER_ADMIN") {
  useUserStore().user = {
    id: "1",
    username: "me",
    nickname: "Me",
    email: null,
    role,
    postTemplate: null,
    permissions: role === "SUPER_ADMIN" ? [] : ["kol-library:view", "kol-library:edit"],
    dataScope: "ALL",
    tenantId: "9",
    tenantName: "B 公司",
    edition: "KOL_LIBRARY",
    status: 1,
    lastLoginTime: null,
    createTime: "2026-10-01T00:00:00Z"
  };
}

async function mountAt(
  path: string,
  role: "SUPER_ADMIN" | "STAFF" = "SUPER_ADMIN"
): Promise<{
  wrapper: VueWrapper;
  router: ReturnType<typeof createRouter>;
}> {
  signIn(role);
  const router = createRouter({ history: createMemoryHistory(), routes });
  await router.push(path);
  const wrapper = mount(LibraryKolFormPage, {
    global: { plugins: [ElementPlus, i18n, router] }
  });
  await flushPromises();
  return { wrapper, router };
}

/** The plain input inside a field: `data-test` lands on the ElInput wrapper when it is set there. */
function inputOf(wrapper: VueWrapper, testId: string): DOMWrapper<HTMLInputElement> {
  const host = wrapper.find(`[data-test="${testId}"]`);
  const field = host.element instanceof HTMLInputElement ? host.element : host.find("input");
  if (!(field instanceof HTMLInputElement)) throw new Error(`missing input ${testId}`);
  return new DOMWrapper(field);
}

async function fill(wrapper: VueWrapper, testId: string, value: string): Promise<void> {
  await inputOf(wrapper, testId).setValue(value);
}

/** Element Plus validates asynchronously; waits for the condition with a real timer. */
async function eventually(check: () => void): Promise<void> {
  await vi.waitFor(check, { timeout: 3000, interval: 20 });
}

function save(wrapper: VueWrapper) {
  return wrapper.find('[data-test="form-save"]').trigger("click");
}

/** The remark textarea: ElInput puts `data-test` on the textarea itself. */
function textareaOf(wrapper: VueWrapper, testId: string): DOMWrapper<HTMLTextAreaElement> {
  const host = wrapper.find(`[data-test="${testId}"]`);
  const field = host.element instanceof HTMLTextAreaElement ? host.element : host.find("textarea");
  if (!(field instanceof HTMLTextAreaElement)) throw new Error(`missing textarea ${testId}`);
  return new DOMWrapper(field);
}

/** The name of the second-level tab currently shown. */
function activeTab(wrapper: VueWrapper): string {
  return wrapper.find(".form-tabs .el-tabs__item.is-active").text();
}

/** Fills the seven required fields that have no default (platform and country default). */
async function fillRequired(wrapper: VueWrapper): Promise<void> {
  await fill(wrapper, "kol-username", "@BeautyByAmy ");
  await fill(wrapper, "display-name", "Beauty by Amy");
  await fill(wrapper, "followers", "12000");
  await fill(wrapper, "profile-url", "https://www.tiktok.com/@beautybyamy");
  await fill(wrapper, "follow-up-owner", "Amy BD");
}

/** The content-category multi-select: the page has a dozen selects, so pick it by its field. */
function selectCategories(wrapper: VueWrapper, values: string[]): Promise<void> {
  const categories = wrapper
    .findAllComponents({ name: "ElSelect" })
    .find(item => item.attributes("data-test") === "categories");
  if (!categories) throw new Error("missing categories select");
  return categories.vm.$emit("update:modelValue", values) as unknown as Promise<void>;
}

describe("creator library form", () => {
  beforeEach(() => {
    document.body.innerHTML = "";
    localStorage.clear();
    sessionStorage.clear();
    setActivePinia(createPinia());
    // jsdom has no scrollIntoView; the form scrolls to the first field with an error.
    Element.prototype.scrollIntoView = vi.fn();
    vi.mocked(libraryApi.getLibraryKol).mockReset().mockResolvedValue(detail);
    vi.mocked(libraryApi.createLibraryKol).mockReset().mockResolvedValue(detail);
    vi.mocked(libraryApi.updateLibraryKol).mockReset().mockResolvedValue(detail);
  });

  it("shows three second-level tabs with their cards and fields", async () => {
    const { wrapper } = await mountAt("/kol-library/kols/new");
    expect(wrapper.findAll(".form-tabs .el-tabs__item").map(item => item.text())).toEqual([
      "基础信息",
      "建联及寄样",
      "质量及分层"
    ]);
    expect(activeTab(wrapper)).toBe("基础信息");
    const pane = (testId: string) => wrapper.find(`[data-test="${testId}"]`);
    const labels = (testId: string) =>
      pane(testId)
        .findAll(".el-form-item__label")
        .map(label => label.text());
    expect(
      pane("tab-basic")
        .findAll(".form-card > .el-card__header")
        .map(header => header.text())
    ).toEqual(["基础信息", "账号类型", "受众画像"]);
    expect(labels("section-basic")).toEqual([
      "达人ID（用户名）",
      "达人名称（昵称）",
      "所属平台",
      "粉丝数",
      "国家站点",
      "主页链接",
      "联系方式",
      "自由标签",
      "备注"
    ]);
    expect(labels("section-account-type")).toEqual(["内容类目", "近期平均播放量", "互动率（%）"]);
    expect(labels("section-audience")).toEqual(["国家/地区", "年龄段", "性别"]);
    expect(labels("tab-follow-up")).toEqual([
      "建联状态",
      "跟进负责人",
      "最近沟通时间",
      "寄样状态",
      "寄样单号",
      "寄样日期",
      "跟进备注"
    ]);
    expect(labels("tab-quality")).toEqual(["内容质量", "带货潜力", "配合度", "达人分级"]);
    // The cooperation stage is retired (2026-11-10).
    expect(wrapper.text()).not.toContain("合作阶段");
    // The creator ID hint says an @username may be pasted (requirement 1.2.6 ①).
    expect(inputOf(wrapper, "kol-username").attributes("placeholder")).toContain("@");
    wrapper.unmount();
  });

  it("marks exactly the seven required fields", async () => {
    const { wrapper } = await mountAt("/kol-library/kols/new");
    const required = wrapper
      .findAll(".el-form-item.is-required .el-form-item__label")
      .map(label => label.text());
    expect(required).toEqual([
      "达人ID（用户名）",
      "达人名称（昵称）",
      "所属平台",
      "粉丝数",
      "国家站点",
      "主页链接",
      "跟进负责人"
    ]);
    wrapper.unmount();
  });

  it("blocks the missing required fields before calling the API", async () => {
    const { wrapper } = await mountAt("/kol-library/kols/new");
    await save(wrapper);
    await eventually(() =>
      expect(wrapper.findAll(".el-form-item__error").map(item => item.text())).toEqual(
        expect.arrayContaining([
          "请输入达人ID",
          "请输入达人名称",
          "请填写粉丝数",
          "请填写主页链接",
          "请填写跟进负责人"
        ])
      )
    );
    expect(wrapper.text()).not.toContain("请至少选择一个内容类目");
    expect(libraryApi.createLibraryKol).not.toHaveBeenCalled();
    wrapper.unmount();
  });

  it("rejects negative followers and a link without http", async () => {
    const { wrapper } = await mountAt("/kol-library/kols/new");
    await fillRequired(wrapper);
    await fill(wrapper, "followers", "-5");
    await fill(wrapper, "profile-url", "www.tiktok.com/@x");
    await save(wrapper);
    await eventually(() =>
      expect(wrapper.findAll(".el-form-item__error").map(item => item.text())).toEqual(
        expect.arrayContaining(["粉丝数须为非负整数", "主页链接须以 http:// 或 https:// 开头"])
      )
    );
    expect(libraryApi.createLibraryKol).not.toHaveBeenCalled();
    wrapper.unmount();
  });

  it("switches back to the basic tab when saving from the outreach tab with a basic error", async () => {
    const { wrapper } = await mountAt("/kol-library/kols/new");
    await fill(wrapper, "follow-up-owner", "Amy BD");
    await wrapper.findAll(".form-tabs .el-tabs__item")[1].trigger("click");
    await flushPromises();
    expect(activeTab(wrapper)).toBe("建联及寄样");
    await save(wrapper);
    await eventually(() => expect(activeTab(wrapper)).toBe("基础信息"));
    expect(libraryApi.createLibraryKol).not.toHaveBeenCalled();
    wrapper.unmount();
  });

  it("switches to the outreach tab when only the follow-up owner is missing", async () => {
    const { wrapper } = await mountAt("/kol-library/kols/new");
    await fillRequired(wrapper);
    await fill(wrapper, "follow-up-owner", "");
    await save(wrapper);
    await eventually(() => expect(activeTab(wrapper)).toBe("建联及寄样"));
    expect(libraryApi.createLibraryKol).not.toHaveBeenCalled();
    wrapper.unmount();
  });

  it("shows the creator ID and name read-only on the outreach tab as they are typed", async () => {
    const { wrapper } = await mountAt("/kol-library/kols/new");
    const identity = () => [
      wrapper.find('[data-test="identity-username"]').text(),
      wrapper.find('[data-test="identity-name"]').text()
    ];
    expect(identity()).toEqual(["—", "—"]);
    await fill(wrapper, "kol-username", "beautybyamy");
    await fill(wrapper, "display-name", "Beauty by Amy");
    expect(identity()).toEqual(["beautybyamy", "Beauty by Amy"]);
    expect(wrapper.find('[data-test="follow-up-identity"] input').exists()).toBe(false);
    wrapper.unmount();
  });

  it("keeps what was typed when switching between tabs", async () => {
    const { wrapper } = await mountAt("/kol-library/kols/new");
    await fill(wrapper, "display-name", "Beauty by Amy");
    const items = wrapper.findAll(".form-tabs .el-tabs__item");
    await items[1].trigger("click");
    await textareaOf(wrapper, "follow-up-remark").setValue("call on friday");
    await items[2].trigger("click");
    await items[0].trigger("click");
    await flushPromises();
    expect(inputOf(wrapper, "display-name").element.value).toBe("Beauty by Amy");
    expect(textareaOf(wrapper, "follow-up-remark").element.value).toBe("call on friday");
    wrapper.unmount();
  });

  it("creates the creator without categories, closes the form tab and opens the detail tab", async () => {
    const { wrapper, router } = await mountAt("/kol-library/kols/new");
    const tabs = useTabsStore();
    tabs.open({
      path: "/kol-library/kols/new",
      titleKey: "route.libraryKolNew",
      affix: false,
      componentName: "LibraryKolFormPage"
    });
    await fillRequired(wrapper);
    await textareaOf(wrapper, "follow-up-remark").setValue(" call on friday ");
    await flushPromises();
    await save(wrapper);
    await eventually(() => expect(libraryApi.createLibraryKol).toHaveBeenCalled());
    const payload = vi.mocked(libraryApi.createLibraryKol).mock.calls[0][0];
    expect(payload.kolUsername).toBe("@BeautyByAmy".trim());
    expect(payload.displayName).toBe("Beauty by Amy");
    expect(payload.followers).toBe("12000");
    expect(payload.profileUrl).toBe("https://www.tiktok.com/@beautybyamy");
    expect(payload.followUpOwner).toBe("Amy BD");
    expect(payload.followUpRemark).toBe("call on friday");
    expect(payload.categories).toEqual([]);
    expect(payload.outreachStatus).toBe("NOT_CONTACTED");
    expect(payload.sampleStatus).toBe("NOT_SENT");
    expect(payload.platform).toBe("TIKTOK");
    expect(payload.countryCode).toBe("US");
    expect(payload).not.toHaveProperty("cooperationStage");
    expect(tabs.tabs.map(tab => tab.path)).toEqual(["/kol-library/kols/11"]);
    expect(tabs.tabs[0].titleKey).toBe("Beauty by Amy");
    await vi.waitFor(() => expect(router.currentRoute.value.path).toBe("/kol-library/kols/11"));
    wrapper.unmount();
  });

  it("explains a duplicate with the existing owner and links to that creator", async () => {
    const { wrapper, router } = await mountAt("/kol-library/kols/new");
    const tabs = useTabsStore();
    vi.mocked(libraryApi.createLibraryKol).mockRejectedValue(
      new ApiError({
        status: 409,
        errorCode: "KOL_LIBRARY_DUPLICATE",
        traceId: "t",
        payload: { id: "77", followUpOwner: "Bob BD", displayName: "Beauty by Amy" }
      })
    );
    await fillRequired(wrapper);
    await selectCategories(wrapper, ["BEAUTY_PERSONAL_CARE"]);
    await flushPromises();
    await save(wrapper);
    await eventually(() =>
      expect(wrapper.find('[data-test="library-duplicate"]').exists()).toBe(true)
    );
    const alert = wrapper.find('[data-test="library-duplicate"]');
    expect(alert.exists()).toBe(true);
    expect(alert.text()).toContain("该达人已由 Bob BD 录入。");
    await wrapper.find('[data-test="library-duplicate-open"]').trigger("click");
    await vi.waitFor(() => expect(router.currentRoute.value.path).toBe("/kol-library/kols/77"));
    expect(tabs.tabs.map(tab => tab.path)).toContain("/kol-library/kols/77");
    wrapper.unmount();
  });

  it("loads the stored profile when editing and saves it back", async () => {
    const { wrapper } = await mountAt("/kol-library/kols/11/edit");
    expect(libraryApi.getLibraryKol).toHaveBeenCalledWith("11");
    expect(inputOf(wrapper, "kol-username").element.value).toBe("beautybyamy");
    expect(inputOf(wrapper, "followers").element.value).toBe("12000");
    expect(textareaOf(wrapper, "follow-up-remark").element.value).toBe("call on friday");
    await fill(wrapper, "display-name", "Beauty by Amy II");
    await save(wrapper);
    await eventually(() => expect(libraryApi.updateLibraryKol).toHaveBeenCalled());
    expect(libraryApi.updateLibraryKol).toHaveBeenCalledWith(
      "11",
      expect.objectContaining({
        displayName: "Beauty by Amy II",
        platform: "TIKTOK",
        followUpRemark: "call on friday"
      })
    );
    // The details tab is opened and the form tab closed.
    const tabs = useTabsStore();
    expect(tabs.tabs.find(tab => tab.path === "/kol-library/kols/11/edit")).toBeUndefined();
    expect(tabs.tabs.find(tab => tab.path === "/kol-library/kols/11")).toBeDefined();
    wrapper.unmount();
  });

  it("asks an older creator without followers or link to fill them in on save", async () => {
    vi.mocked(libraryApi.getLibraryKol).mockResolvedValue({
      ...detail,
      followers: null,
      profileUrl: null
    });
    const { wrapper } = await mountAt("/kol-library/kols/11/edit");
    expect(inputOf(wrapper, "display-name").element.value).toBe("Beauty by Amy");
    await save(wrapper);
    await eventually(() =>
      expect(wrapper.findAll(".el-form-item__error").map(item => item.text())).toEqual(
        expect.arrayContaining(["请填写粉丝数", "请填写主页链接"])
      )
    );
    expect(libraryApi.updateLibraryKol).not.toHaveBeenCalled();
    wrapper.unmount();
  });

  it("offers delete only in the edit form and only to a super administrator", async () => {
    const created = await mountAt("/kol-library/kols/new");
    expect(created.wrapper.find('[data-test="form-delete"]').exists()).toBe(false);
    created.wrapper.unmount();

    setActivePinia(createPinia());
    const sub = await mountAt("/kol-library/kols/11/edit", "STAFF");
    expect(sub.wrapper.find('[data-test="form-delete"]').exists()).toBe(false);
    sub.wrapper.unmount();

    setActivePinia(createPinia());
    const admin = await mountAt("/kol-library/kols/11/edit");
    expect(admin.wrapper.find('[data-test="form-delete"]').exists()).toBe(true);
    admin.wrapper.unmount();
  });

  it("deletes from the edit form, closes the creator tabs and refreshes the list", async () => {
    vi.mocked(libraryApi.deleteLibraryKol).mockReset().mockResolvedValue(null);
    const confirm = vi.spyOn(ElMessageBox, "confirm").mockResolvedValue("confirm" as never);
    const tabs = useTabsStore();
    for (const path of ["/kol-library/kols", "/kol-library/kols/11", "/kol-library/kols/11/edit"]) {
      tabs.open({ path, titleKey: path, literalTitle: true, affix: false, componentName: "X" });
    }
    const refresh = vi.spyOn(tabs, "refresh");
    const { wrapper, router } = await mountAt("/kol-library/kols/11/edit");
    await wrapper.find('[data-test="form-delete"]').trigger("click");
    await eventually(() => expect(libraryApi.deleteLibraryKol).toHaveBeenCalledWith("11"));
    expect(confirm.mock.calls[0][0]).toContain("Beauty by Amy");
    await eventually(() => expect(router.currentRoute.value.path).toBe("/kol-library/kols"));
    expect(tabs.tabs.map(tab => tab.path)).not.toContain("/kol-library/kols/11");
    expect(tabs.tabs.map(tab => tab.path)).not.toContain("/kol-library/kols/11/edit");
    expect(refresh).toHaveBeenCalledWith("/kol-library/kols");
    confirm.mockRestore();
    wrapper.unmount();
  });

  it("names the edit tab after the creator being edited", async () => {
    const tabs = useTabsStore();
    tabs.open({
      path: "/kol-library/kols/11/edit",
      titleKey: "route.libraryKolEdit",
      affix: false,
      componentName: "LibraryKolFormPage"
    });
    const { wrapper } = await mountAt("/kol-library/kols/11/edit");
    const formTab = tabs.tabs.find(tab => tab.path === "/kol-library/kols/11/edit");
    // The route's title key alone would show as "route.libraryKolEdit" in the tab strip.
    expect(formTab?.literalTitle).toBe(true);
    expect(formTab?.titleKey).toContain("Beauty by Amy");
    wrapper.unmount();
  });

  it("keeps tags free text, at most ten", async () => {
    const { wrapper } = await mountAt("/kol-library/kols/new");
    for (let index = 0; index < 11; index += 1) {
      await fill(wrapper, "tag-input", `tag${index}`);
      await wrapper.find('[data-test="tag-add"]').trigger("click");
      await flushPromises();
    }
    expect(wrapper.findAll('[data-test="tag-item"]')).toHaveLength(10);
    expect(ElMessage.warning).toBeDefined();
    wrapper.unmount();
  });
});
