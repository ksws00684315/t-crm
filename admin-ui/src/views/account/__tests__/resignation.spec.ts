import { flushPromises, mount } from "@vue/test-utils";
import ElementPlus, { ElMessageBox } from "element-plus";
import { createPinia, setActivePinia } from "pinia";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import * as authApi from "@/api/auth";
import { i18n } from "@/locales";
import { useUserStore } from "@/stores/user";
import ResignationPage from "../resignation.vue";

vi.mock("@/api/auth");

const bdUser = {
  id: "9007199254740993",
  username: "t41_bd_a",
  nickname: "Alice",
  email: null,
  role: "STAFF" as const,
  postTemplate: "BD" as const,
  permissions: ["kol:view", "kol:edit"],
  dataScope: "OWN" as const,
  tenantId: "1",
  tenantName: "默认公司",
  status: 1 as const,
  lastLoginTime: null,
  createTime: "2026-10-05T01:02:03Z"
};

const operatorUser = {
  ...bdUser,
  id: "9007199254740994",
  username: "t41_operator",
  permissions: ["kol:view"],
  dataScope: "ALL" as const,
  postTemplate: "OPERATOR" as const
};

const plan = {
  id: "7001",
  userId: bdUser.id,
  type: "RESIGN" as const,
  effectiveAt: "2030-01-01T10:00:00.000Z",
  heirUserId: null,
  heirName: null,
  initiator: "SELF" as const,
  reason: "family",
  status: "SCHEDULED" as const,
  executedAt: null,
  result: null,
  resultMessage: null,
  awaitingHeir: true
};

function signIn(user: typeof bdUser | typeof operatorUser) {
  // The page only reads the signed-in account's role and scope; patching `user` is enough here
  // (`expiresAt` is store-internal and not part of the patchable state).
  useUserStore().$patch({ user, token: "test-token" });
}

async function mountPage() {
  const wrapper = mount(ResignationPage, { global: { plugins: [ElementPlus, i18n] } });
  await flushPromises();
  return wrapper;
}

describe("own resignation page (task 41)", () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    document.body.innerHTML = "";
    vi.mocked(authApi.getOwnResignation).mockReset();
    vi.mocked(authApi.submitResignation).mockReset();
    vi.mocked(authApi.cancelResignation).mockReset();
    vi.mocked(authApi.getOwnResignation).mockResolvedValue(null);
  });

  afterEach(() => {
    document.body.innerHTML = "";
    vi.restoreAllMocks();
  });

  it("tells a non-BD account that only BDs may resign, without calling the API", async () => {
    signIn(operatorUser);
    const wrapper = await mountPage();
    expect(wrapper.find('[data-test="resignation-bd-only"]').exists()).toBe(true);
    expect(wrapper.text()).toContain("只有商务");
    expect(authApi.getOwnResignation).not.toHaveBeenCalled();
    wrapper.unmount();
  });

  it("asks a BD without a plan for the leave time and refuses an empty one", async () => {
    signIn(bdUser);
    const wrapper = await mountPage();
    expect(authApi.getOwnResignation).toHaveBeenCalled();
    expect(wrapper.findComponent({ name: "ElDatePicker" }).exists()).toBe(true);
    await wrapper.find('[data-test="resignation-submit"]').trigger("click");
    await flushPromises();
    expect(wrapper.find('[data-test="resignation-error"]').text()).toContain("请选择生效时间");
    expect(authApi.submitResignation).not.toHaveBeenCalled();
    wrapper.unmount();
  });

  it("shows the submitted plan with the heir still to be named", async () => {
    signIn(bdUser);
    vi.mocked(authApi.getOwnResignation).mockResolvedValue(plan);
    const wrapper = await mountPage();
    expect(wrapper.find('[data-test="resignation-plan"]').exists()).toBe(true);
    expect(wrapper.text()).toContain("待指定继承人");
    expect(wrapper.text()).toContain("family");
    expect(wrapper.text()).toContain("待生效");
    expect(wrapper.findComponent({ name: "ElDatePicker" }).exists()).toBe(false);
    wrapper.unmount();
  });

  it("withdraws the plan only after the confirmation", async () => {
    signIn(bdUser);
    vi.mocked(authApi.getOwnResignation).mockResolvedValue(plan);
    vi.mocked(authApi.cancelResignation).mockResolvedValue(null);
    const confirm = vi.spyOn(ElMessageBox, "confirm").mockResolvedValue("confirm" as never);
    const wrapper = await mountPage();
    await wrapper.find('[data-test="resignation-cancel"]').trigger("click");
    await flushPromises();
    expect(confirm).toHaveBeenCalled();
    expect(authApi.cancelResignation).toHaveBeenCalledTimes(1);
    expect(wrapper.find('[data-test="resignation-plan"]').exists()).toBe(false);
    expect(wrapper.findComponent({ name: "ElDatePicker" }).exists()).toBe(true);
    wrapper.unmount();
  });

  it("keeps the plan when the confirmation is dismissed", async () => {
    signIn(bdUser);
    vi.mocked(authApi.getOwnResignation).mockResolvedValue(plan);
    vi.spyOn(ElMessageBox, "confirm").mockRejectedValue("cancel");
    const wrapper = await mountPage();
    await wrapper.find('[data-test="resignation-cancel"]').trigger("click");
    await flushPromises();
    expect(authApi.cancelResignation).not.toHaveBeenCalled();
    expect(wrapper.find('[data-test="resignation-plan"]').exists()).toBe(true);
    wrapper.unmount();
  });
});
