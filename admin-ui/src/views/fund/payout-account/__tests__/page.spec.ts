import { flushPromises, mount, type VueWrapper } from "@vue/test-utils";
import ElementPlus, { ElSelect } from "element-plus";
import { createPinia, setActivePinia } from "pinia";
import { createMemoryHistory, createRouter, type Router } from "vue-router";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { Role, UserVO } from "@/api/types";
import * as api from "@/api/commission/payout-account";
import type { PayoutAccountRowVO } from "@/api/commission/payout-account";
import { i18n } from "@/locales";
import { useLocaleStore } from "@/stores/locale";
import { usePayoutPendingStore } from "@/stores/payout-pending";
import { useUserStore } from "@/stores/user";
import ReviewDrawer from "../ReviewDrawer.vue";
import PayoutAccountPage from "../index.vue";

vi.mock("@/api/commission/payout-account", () => ({
  listPayoutAccounts: vi.fn(),
  fetchPayoutPendingCount: vi.fn(),
  getPayoutAccount: vi.fn(),
  reviewPayoutAccount: vi.fn(),
  downloadPayoutQr: vi.fn()
}));

vi.mock("../ReviewDrawer.vue", () => ({
  default: {
    name: "PayoutReviewDrawer",
    props: ["modelValue", "accountId"],
    emits: ["update:modelValue", "reviewed"],
    template: '<div data-test="drawer-stub" v-if="modelValue">{{ accountId }}</div>'
  }
}));

const row: PayoutAccountRowVO = {
  id: "9007199254740993",
  kolId: "31",
  kolCode: "KOL0001",
  kolName: "Alice",
  accountType: "USDT",
  accountMasked: "TQ5x…K9aZ",
  holderName: null,
  currency: null,
  network: "TRC20",
  qrFileId: "66",
  status: "PENDING",
  defaultAccount: false,
  rejectReason: null,
  lastSubmittedAt: "2026-10-16T08:00:00Z",
  reviewedTime: null
};

const wrappers: VueWrapper[] = [];

function signIn(role: Role, permissions: string[]) {
  const pinia = createPinia();
  setActivePinia(pinia);
  useUserStore().user = {
    id: "1",
    username: "me",
    nickname: "Me",
    email: null,
    role,
    postTemplate: role === "STAFF" ? "BD" : null,
    permissions,
    dataScope: "OWN",
    tenantId: "1",
    tenantName: "默认公司",
    status: 1,
    lastLoginTime: null,
    createTime: "2026-10-01T00:00:00Z"
  } as UserVO;
  return pinia;
}

async function mountPage(
  pinia: ReturnType<typeof createPinia>,
  query = ""
): Promise<{
  wrapper: VueWrapper;
  router: Router;
}> {
  vi.mocked(api.listPayoutAccounts).mockResolvedValue({
    items: [row],
    total: "1",
    page: 1,
    size: 20
  });
  useLocaleStore().setLocale("zh-CN");
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: "/fund/payout-accounts", component: PayoutAccountPage }]
  });
  await router.push(`/fund/payout-accounts${query}`);
  const wrapper = mount(PayoutAccountPage, {
    attachTo: document.body,
    global: { plugins: [ElementPlus, i18n, pinia, router] }
  });
  wrappers.push(wrapper);
  await flushPromises();
  return { wrapper, router };
}

beforeEach(() => {
  document.body.innerHTML = "";
  localStorage.removeItem("tcrm.admin.table.payout-accounts");
  localStorage.removeItem("tcrm.admin.locale");
  setActivePinia(createPinia());
  vi.clearAllMocks();
});

afterEach(() => {
  wrappers.splice(0).forEach(wrapper => wrapper.unmount());
});

describe("payout account review page", () => {
  it("lists the review queue with the PENDING filter by default", async () => {
    const pinia = signIn("STAFF", ["payout-account:review"]);
    const { wrapper } = await mountPage(pinia);
    expect(api.listPayoutAccounts).toHaveBeenCalledWith(
      expect.objectContaining({ status: "PENDING", page: 1 })
    );
    expect(wrapper.text()).toContain("KOL0001");
    expect(wrapper.text()).toContain("Alice");
    expect(wrapper.text()).toContain("TQ5x…K9aZ");
    expect(wrapper.text()).toContain("USDT");
    expect(wrapper.text()).toContain("TRC20");
    expect(wrapper.text()).toContain("待审核");
    expect(wrapper.text()).toContain("2026/10/16");
  });

  it("sends ALL and the other filters only after the search is applied", async () => {
    const pinia = signIn("STAFF", ["payout-account:review"]);
    const { wrapper } = await mountPage(pinia);
    vi.mocked(api.listPayoutAccounts).mockClear();

    // The status filter starts on the review queue; ALL is the only other choice.
    const statusSelect = wrapper.findAllComponents(ElSelect)[0];
    statusSelect.vm.$emit("update:model-value", "ALL");
    await wrapper.get('[data-test="search"]').trigger("click");
    expect(api.listPayoutAccounts).toHaveBeenCalledWith(expect.objectContaining({ status: "ALL" }));

    vi.mocked(api.listPayoutAccounts).mockClear();
    const typeSelect = wrapper.findAllComponents(ElSelect)[1];
    typeSelect.vm.$emit("update:model-value", "USDT");
    await wrapper.get('[data-test="search"]').trigger("click");
    expect(api.listPayoutAccounts).toHaveBeenCalledWith(
      expect.objectContaining({ status: "ALL", type: "USDT" })
    );
    // Typing a creator code without pressing search changes nothing yet.
    vi.mocked(api.listPayoutAccounts).mockClear();
    await wrapper.get('[data-test="kol-code"]').setValue("KOL0002");
    expect(api.listPayoutAccounts).not.toHaveBeenCalled();
    await wrapper.get('[data-test="search"]').trigger("click");
    expect(api.listPayoutAccounts).toHaveBeenCalledWith(
      expect.objectContaining({ kolCode: "KOL0002" })
    );
  });

  it("shows the detail action and drawer to a reviewer", async () => {
    const pinia = signIn("STAFF", ["payout-account:review"]);
    const { wrapper } = await mountPage(pinia);
    // el-table also renders the slot once in its hidden-columns area with an empty row; the
    // visible cell is the one inside .el-table__row.
    const detailButton = wrapper.get('.el-table__row [data-test="detail"]');
    expect(detailButton.text()).toBe("详情");
    await detailButton.trigger("click");
    await flushPromises();
    expect(wrapper.findComponent(ReviewDrawer).props("modelValue")).toBe(true);
    expect(wrapper.findComponent(ReviewDrawer).props("accountId")).toBe(row.id);
  });

  it("hides detail and review from an account with only commission:view", async () => {
    const pinia = signIn("STAFF", ["commission:view"]);
    const { wrapper } = await mountPage(pinia);
    expect(wrapper.find('.el-table__row [data-test="detail"]').exists()).toBe(false);
    expect(wrapper.findComponent(ReviewDrawer).exists()).toBe(false);
    // The list itself still loads.
    expect(api.listPayoutAccounts).toHaveBeenCalledTimes(1);
  });

  it("opens the detail drawer from the ?id= query parameter", async () => {
    const pinia = signIn("SUPER_ADMIN", []);
    const { wrapper } = await mountPage(pinia, "?id=9007199254740993");
    expect(wrapper.findComponent(ReviewDrawer).props("modelValue")).toBe(true);
    expect(wrapper.findComponent(ReviewDrawer).props("accountId")).toBe("9007199254740993");
  });

  it("drops ?id= when the drawer closes so the same link can open it again", async () => {
    const pinia = signIn("SUPER_ADMIN", []);
    const { wrapper, router } = await mountPage(pinia, "?id=9007199254740993");
    const drawer = () => wrapper.findComponent(ReviewDrawer);
    expect(drawer().props("modelValue")).toBe(true);

    drawer().vm.$emit("update:modelValue", false);
    await flushPromises();
    expect(drawer().props("modelValue")).toBe(false);
    expect(drawer().props("accountId")).toBeNull();
    expect(router.currentRoute.value.query.id).toBeUndefined();
    expect(router.currentRoute.value.path).toBe("/fund/payout-accounts");

    // Clicking the same notification pushes the same URL again; it must be a real query change.
    await router.push("/fund/payout-accounts?id=9007199254740993");
    await flushPromises();
    expect(drawer().props("modelValue")).toBe(true);
    expect(drawer().props("accountId")).toBe("9007199254740993");
  });

  it("keeps other query parameters when dropping ?id=", async () => {
    const pinia = signIn("SUPER_ADMIN", []);
    const { wrapper, router } = await mountPage(pinia, "?id=9007199254740993&from=inbox");
    wrapper.findComponent(ReviewDrawer).vm.$emit("update:modelValue", false);
    await flushPromises();
    expect(router.currentRoute.value.query).toEqual({ from: "inbox" });
  });

  it("does not open the drawer from ?id= without the review permission", async () => {
    const pinia = signIn("STAFF", ["commission:view"]);
    const { wrapper } = await mountPage(pinia, "?id=9007199254740993");
    expect(wrapper.findComponent(ReviewDrawer).exists()).toBe(false);
  });

  it("refreshes the pending count when entering and after a review", async () => {
    const pinia = signIn("STAFF", ["payout-account:review"]);
    vi.mocked(api.fetchPayoutPendingCount).mockResolvedValue(2);
    const { wrapper } = await mountPage(pinia);
    const store = usePayoutPendingStore();
    expect(store.count).toBe(2);

    vi.mocked(api.fetchPayoutPendingCount).mockClear();
    vi.mocked(api.listPayoutAccounts).mockClear();
    wrapper.findComponent(ReviewDrawer).vm.$emit("reviewed");
    await flushPromises();
    expect(api.fetchPayoutPendingCount).toHaveBeenCalledTimes(1);
    expect(api.listPayoutAccounts).toHaveBeenCalledTimes(1);
  });
});
