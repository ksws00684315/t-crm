import { DOMWrapper, flushPromises, mount, type VueWrapper } from "@vue/test-utils";
import ElementPlus, { ElMessage } from "element-plus";
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
 * Full-page profile form (requirement 1.2.5 ④): six group cards, single column, a required creator
 * ID and name, the duplicate answer with a link to the existing creator, and the tab swap after
 * saving.
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
  cooperationStage: "REPEAT_INVESTMENT",
  tags: [],
  remark: null,
  outreachStatus: "REPLIED",
  followUpOwner: "Amy BD",
  lastContactDate: null,
  sampleStatus: "SHIPPED",
  trackingNo: null,
  sampleDate: null,
  createTime: "2026-10-01T00:00:00Z",
  updateTime: "2026-11-01T00:00:00Z",
  totals: { contentCount: 0, clicks: 0, orders: 0, gmv: "0.00", totalCost: "0.00", roi: null }
};

function signIn() {
  useUserStore().user = {
    id: "1",
    username: "me",
    nickname: "Me",
    email: null,
    role: "SUPER_ADMIN",
    postTemplate: null,
    permissions: [],
    dataScope: "ALL",
    tenantId: "9",
    tenantName: "B 公司",
    edition: "KOL_LIBRARY",
    status: 1,
    lastLoginTime: null,
    createTime: "2026-10-01T00:00:00Z"
  };
}

async function mountAt(path: string): Promise<{
  wrapper: VueWrapper;
  router: ReturnType<typeof createRouter>;
}> {
  signIn();
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
    vi.mocked(libraryApi.getLibraryKol).mockReset().mockResolvedValue(detail);
    vi.mocked(libraryApi.createLibraryKol).mockReset().mockResolvedValue(detail);
    vi.mocked(libraryApi.updateLibraryKol).mockReset().mockResolvedValue(detail);
  });

  it("shows the six group cards of requirement 1.2.5 ④", async () => {
    const { wrapper } = await mountAt("/kol-library/kols/new");
    const groups = wrapper.findAll(".form-card").map(card => card.find(".el-card__header").text());
    expect(groups).toEqual([
      "基础数据",
      "受众画像",
      "联系方式",
      "质量及分层",
      "标签备注",
      "建联与寄样"
    ]);
    // The creator ID hint says an @username may be pasted (requirement 1.2.6 ①).
    expect(inputOf(wrapper, "kol-username").attributes("placeholder")).toContain("@");
    wrapper.unmount();
  });

  it("blocks an empty creator ID and creator name before calling the API", async () => {
    const { wrapper } = await mountAt("/kol-library/kols/new");
    await save(wrapper);
    await eventually(() =>
      expect(wrapper.findAll(".el-form-item__error").map(item => item.text())).toEqual(
        expect.arrayContaining([
          "请输入达人ID",
          "请输入达人名称",
          "请至少选择一个内容类目",
          "请填写跟进负责人"
        ])
      )
    );
    expect(libraryApi.createLibraryKol).not.toHaveBeenCalled();
    wrapper.unmount();
  });

  it("creates the creator, closes the form tab and opens the detail tab", async () => {
    const { wrapper, router } = await mountAt("/kol-library/kols/new");
    const tabs = useTabsStore();
    tabs.open({
      path: "/kol-library/kols/new",
      titleKey: "route.libraryKolNew",
      affix: false,
      componentName: "LibraryKolFormPage"
    });
    await fill(wrapper, "kol-username", "@BeautyByAmy ");
    await fill(wrapper, "display-name", "Beauty by Amy");
    await fill(wrapper, "follow-up-owner", "Amy BD");
    // Content categories are a required multi-select.
    await selectCategories(wrapper, ["BEAUTY_PERSONAL_CARE"]);
    await flushPromises();
    await save(wrapper);
    await eventually(() => expect(libraryApi.createLibraryKol).toHaveBeenCalled());
    const payload = vi.mocked(libraryApi.createLibraryKol).mock.calls[0][0];
    expect(payload.kolUsername).toBe("@BeautyByAmy".trim());
    expect(payload.displayName).toBe("Beauty by Amy");
    expect(payload.followUpOwner).toBe("Amy BD");
    expect(payload.categories).toEqual(["BEAUTY_PERSONAL_CARE"]);
    expect(payload.outreachStatus).toBe("NOT_CONTACTED");
    expect(payload.sampleStatus).toBe("NOT_SENT");
    expect(payload.platform).toBe("TIKTOK");
    expect(payload.countryCode).toBe("US");
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
    await fill(wrapper, "kol-username", "beautybyamy");
    await fill(wrapper, "display-name", "Beauty by Amy");
    await fill(wrapper, "follow-up-owner", "Amy BD");
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
    await fill(wrapper, "display-name", "Beauty by Amy II");
    await save(wrapper);
    await eventually(() => expect(libraryApi.updateLibraryKol).toHaveBeenCalled());
    expect(libraryApi.updateLibraryKol).toHaveBeenCalledWith(
      "11",
      expect.objectContaining({ displayName: "Beauty by Amy II", platform: "TIKTOK" })
    );
    // The details tab is opened and the form tab closed.
    const tabs = useTabsStore();
    expect(tabs.tabs.find(tab => tab.path === "/kol-library/kols/11/edit")).toBeUndefined();
    expect(tabs.tabs.find(tab => tab.path === "/kol-library/kols/11")).toBeDefined();
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
