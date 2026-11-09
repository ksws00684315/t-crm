import { flushPromises, mount } from "@vue/test-utils";
import ElementPlus from "element-plus";
import { createPinia, setActivePinia } from "pinia";
import { createMemoryHistory, createRouter } from "vue-router";
import { beforeEach, describe, expect, it, vi } from "vitest";
import * as libraryApi from "@/api/kol-library/kol";
import type { LibraryContentVO, LibraryKolDetailVO } from "@/api/kol-library/kol";
import { i18n } from "@/locales";
import { routes } from "@/router/routes";
import { useTabsStore } from "@/stores/tabs";
import { saveSession } from "@/utils/session";
import LibraryKolDetailPage from "../kol-detail/index.vue";

vi.mock("@/api/kol-library/kol");

/**
 * Creator detail page loading (requirement 1.2.5 ③): the contents need the profile, so they are
 * requested after it. Started side by side on a fresh page the contents were skipped (the profile
 * was still missing) and the table stayed empty until the tab was mounted again.
 */

const profile: LibraryKolDetailVO = {
  id: "11",
  platform: "TIKTOK",
  kolUsername: "beautybyamy",
  displayName: "Beauty by Amy",
  followers: "128000",
  countryCode: "US",
  categories: ["BEAUTY_PERSONAL_CARE"],
  grade: "A",
  cooperationStage: "FIRST_COOPERATION",
  outreachStatus: "EMAIL_SENT",
  followUpOwner: "Amy BD",
  avgViews: "30000",
  engagementRate: "4.20",
  profileUrl: "https://www.tiktok.com/@beautybyamy",
  audienceCountry: "United States",
  audienceAgeRange: "AGE_25_34",
  audienceGender: "MOSTLY_FEMALE",
  contact: null,
  contentQuality: "HIGH",
  salesPotential: "MEDIUM",
  cooperationAttitude: "HIGH",
  tags: [],
  remark: null,
  lastContactDate: null,
  sampleStatus: "SHIPPED",
  trackingNo: "SF123",
  sampleDate: null,
  updateTime: "2026-11-01T00:00:00Z",
  createTime: "2026-10-01T00:00:00Z",
  totals: {
    contentCount: 1,
    clicks: 1200,
    orders: 30,
    gmv: "1000.00",
    totalCost: "205.00",
    roi: "4.88"
  }
};

const content: LibraryContentVO = {
  id: "31",
  publishUrl: "https://www.tiktok.com/@beautybyamy/video/1",
  publishDate: "2026-10-01",
  contentForm: "SHORT_VIDEO",
  clicks: "1200",
  orders: "30",
  gmv: "1000.00",
  sampleType: "GIFT",
  sampleCost: "30.00",
  slotFee: "50.00",
  commissionRate: "12.50",
  estimatedCommission: "125.00",
  totalCost: "205.00",
  actualRoi: "4.88",
  nextAction: "follow up",
  updateTime: "2026-10-02T00:00:00Z"
};

async function mountDetail() {
  saveSession({
    token: "tok",
    expiresAt: Date.now() + 60_000,
    user: {
      id: "1",
      username: "owner",
      nickname: "Owner",
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
      createTime: "2026-10-01T00:00:00Z",
      companies: [
        { tenantId: "9", tenantName: "B 公司", role: "SUPER_ADMIN", edition: "KOL_LIBRARY" }
      ]
    }
  });
  setActivePinia(createPinia());
  void useTabsStore();
  const router = createRouter({ history: createMemoryHistory(), routes });
  await router.push("/kol-library/kols/11");
  const wrapper = mount(LibraryKolDetailPage, {
    global: { plugins: [ElementPlus, i18n, router] }
  });
  await flushPromises();
  return wrapper;
}

describe("library creator detail", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.mocked(libraryApi.getLibraryKol).mockResolvedValue(profile);
    vi.mocked(libraryApi.listLibraryContents).mockResolvedValue([content]);
  });

  it("loads the contents after the profile, so the table is filled on a fresh page", async () => {
    const wrapper = await mountDetail();
    expect(libraryApi.getLibraryKol).toHaveBeenCalledWith("11");
    // Without waiting for the profile the contents were skipped and never asked for again.
    expect(libraryApi.listLibraryContents).toHaveBeenCalledWith("11");
    wrapper.unmount();
  });
});
