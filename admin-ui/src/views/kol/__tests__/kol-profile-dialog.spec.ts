import { DOMWrapper, flushPromises, mount } from "@vue/test-utils";
import ElementPlus, { ElMessage, ElMessageBox } from "element-plus";
import { createPinia, setActivePinia } from "pinia";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import * as kolApi from "@/api/kol/kol";
import type { KolVO, Role } from "@/api/types";
import { i18n } from "@/locales";
import { notifyError } from "@/utils/notify";
import { useUserStore } from "@/stores/user";
import KolProfileDialog from "../KolProfileDialog.vue";

vi.mock("@/api/kol/kol");
vi.mock("@/utils/notify");

const kol: KolVO = {
  id: "9007199254740993",
  kolCode: "KOL-ASD001",
  name: "Alice",
  email: "alice@example.com",
  country: "US",
  socialAccounts: [
    { platform: "TikTok", handle: "@alice", url: null, followers: null, primary: true }
  ],
  bdUserId: "1",
  bdUserName: "BD Five",
  defaultRate: "0.1550",
  status: 1,
  remark: null,
  whatsapp: null,
  phone: null,
  linkTemplateId: null,
  linkTemplateName: null,
  inviteCode: null,
  sheetUrl: null,
  registerSource: 1,
  accountStatus: 1,
  reviewStatus: "APPROVED",
  relationStatus: "PENDING_PROMOTION",
  displayRelationStatus: "PENDING_PROMOTION",
  loginStatus: "NORMAL",
  platforms: [],
  jumpTemplateId: null,
  configVersion: 1,
  inviteTime: "2026-10-05T01:02:03Z",
  createTime: "2026-10-05T01:02:03Z",
  updateTime: "2026-10-05T01:02:03Z",
  autoCleanupDays: null
};

function element(testId: string): DOMWrapper<Element> {
  const el = document.body.querySelector(`[data-test="${testId}"]`);
  if (!el) throw new Error(`missing ${testId}`);
  return new DOMWrapper(el);
}

function signIn(role: Role, permissions: string[] = []) {
  useUserStore().user = {
    id: "1",
    username: "me",
    nickname: "Me",
    email: null,
    role,
    postTemplate: role === "STAFF" ? "BD" : null,
    permissions,
    dataScope: "ALL",
    tenantId: "1",
    tenantName: "默认公司",
    status: 1,
    lastLoginTime: null,
    createTime: "2026-10-01T00:00:00Z"
  };
}

async function openDialog(overrides: Partial<KolVO> = {}, review = false) {
  const wrapper = mount(KolProfileDialog, {
    props: { modelValue: false, kol: { ...kol, ...overrides }, review },
    global: { plugins: [ElementPlus, i18n] },
    attachTo: document.body
  });
  await wrapper.setProps({ modelValue: true });
  await flushPromises();
  return wrapper;
}

describe("creator profile dialog password reset (task 147)", () => {
  beforeEach(() => {
    document.body.innerHTML = "";
    setActivePinia(createPinia());
    vi.mocked(kolApi.resetKolPassword).mockReset();
    vi.mocked(kolApi.editKol).mockReset();
    vi.mocked(kolApi.reviewKol).mockReset();
    vi.mocked(notifyError).mockReset();
    i18n.global.locale.value = "zh-CN";
  });

  afterEach(() => {
    document.body.innerHTML = "";
    vi.restoreAllMocks();
  });

  it("shows the entry to a super administrator for an activated creator only", async () => {
    signIn("SUPER_ADMIN");
    await openDialog();
    expect(element("reset-password").text()).toBe("重置密码");

    document.body.innerHTML = "";
    await openDialog({ accountStatus: 0 });
    expect(document.body.querySelector('[data-test="reset-password"]')).toBeNull();

    document.body.innerHTML = "";
    signIn("STAFF", ["kol:edit"]);
    await openDialog();
    expect(document.body.querySelector('[data-test="reset-password"]')).toBeNull();

    document.body.innerHTML = "";
    signIn("SUPER_ADMIN");
    await openDialog({}, true);
    expect(document.body.querySelector('[data-test="reset-password"]')).toBeNull();
  });

  it("resets after the confirmation and shows the generated password once", async () => {
    signIn("SUPER_ADMIN");
    vi.spyOn(ElMessageBox, "confirm").mockResolvedValue({ action: "confirm" } as never);
    vi.mocked(kolApi.resetKolPassword).mockResolvedValue({ password: "23456789Abcdefgh" });
    const success = vi.spyOn(ElMessage, "success").mockImplementation(() => ({}) as never);

    await openDialog();
    await element("reset-password").trigger("click");
    await flushPromises();

    expect(kolApi.resetKolPassword).toHaveBeenCalledWith("9007199254740993");
    expect(
      document.body.querySelector('[data-test="reset-password-result"] [data-test="value"]')
        ?.textContent
    ).toBe("23456789Abcdefgh");
    expect(success).toHaveBeenCalled();
  });

  it("calls nothing when the confirmation is cancelled", async () => {
    signIn("SUPER_ADMIN");
    vi.spyOn(ElMessageBox, "confirm").mockRejectedValue("cancel");

    await openDialog();
    await element("reset-password").trigger("click");
    await flushPromises();

    expect(kolApi.resetKolPassword).not.toHaveBeenCalled();
    expect(document.body.querySelector('[data-test="reset-password-result"]')).toBeNull();
  });

  it("reports a failure without showing a password", async () => {
    signIn("SUPER_ADMIN");
    vi.spyOn(ElMessageBox, "confirm").mockResolvedValue({ action: "confirm" } as never);
    vi.mocked(kolApi.resetKolPassword).mockRejectedValue(new Error("COMMON_NOT_FOUND"));

    await openDialog();
    await element("reset-password").trigger("click");
    await flushPromises();

    expect(notifyError).toHaveBeenCalled();
    expect(document.body.querySelector('[data-test="reset-password-result"]')).toBeNull();
  });

  it("clears the one-time password when the dialog reopens", async () => {
    signIn("SUPER_ADMIN");
    vi.spyOn(ElMessageBox, "confirm").mockResolvedValue({ action: "confirm" } as never);
    vi.mocked(kolApi.resetKolPassword).mockResolvedValue({ password: "23456789Abcdefgh" });

    const wrapper = await openDialog();
    await element("reset-password").trigger("click");
    await flushPromises();
    expect(document.body.querySelector('[data-test="reset-password-result"]')).not.toBeNull();

    await wrapper.setProps({ modelValue: false });
    await flushPromises();
    await wrapper.setProps({ modelValue: true });
    await flushPromises();
    expect(document.body.querySelector('[data-test="reset-password-result"]')).toBeNull();
  });
});
