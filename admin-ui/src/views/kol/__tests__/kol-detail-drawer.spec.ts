import { flushPromises, mount } from "@vue/test-utils";
import { nextTick } from "vue";
import ElementPlus, { ElMessage } from "element-plus";
import { createPinia, setActivePinia } from "pinia";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import * as kolApi from "@/api/kol/kol";
import type { KolDetailVO, Role } from "@/api/types";
import { i18n } from "@/locales";
import { useUserStore } from "@/stores/user";
import { ApiError } from "@/utils/http/error";
import KolDetailDrawer from "../KolDetailDrawer.vue";

vi.mock("@/api/kol/kol");

const sheet = "https://docs.google.com/spreadsheets/d/abc";

function detail(overrides: Partial<KolDetailVO> = {}): KolDetailVO {
  return {
    id: "9007199254740993",
    kolCode: "KOL0001",
    name: "Alice",
    email: "alice@example.com",
    country: "US",
    socialAccounts: [
      { platform: "TikTok", handle: "@a", url: null, followers: null, primary: true }
    ],
    bdUserId: null,
    bdUserName: null,
    defaultRate: null,
    status: 1,
    remark: null,
    whatsapp: "+8613800000000",
    phone: null,
    linkTemplateId: "7",
    linkTemplateName: "AgentOne",
    inviteCode: "ABC123",
    sheetUrl: sheet,
    registerSource: 1,
    accountStatus: 0,
    reviewStatus: "APPROVED",
    relationStatus: "PENDING_PROMOTION",
    displayRelationStatus: "PENDING_PROMOTION",
    loginStatus: "NORMAL",
    platforms: [],
    jumpTemplateId: null,
    configVersion: null,
    inviteTime: "2026-10-05T01:02:03Z",
    createTime: "2026-10-05T01:02:03Z",
    updateTime: "2026-10-05T01:02:03Z",
    bdHistory: [],
    ...overrides
  };
}

function signIn(role: Role, permissions: string[]) {
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

async function open(current: KolDetailVO) {
  vi.mocked(kolApi.getKol).mockResolvedValue(current);
  const wrapper = mount(KolDetailDrawer, {
    props: { modelValue: false, kolId: current.id },
    global: { plugins: [ElementPlus, i18n] },
    attachTo: document.body
  });
  await wrapper.setProps({ modelValue: true });
  await flushPromises();
  await nextTick();
  return wrapper;
}

describe("KOL detail drawer", () => {
  beforeEach(() => {
    document.body.innerHTML = "";
    setActivePinia(createPinia());
    signIn("SUPER_ADMIN", []);
  });

  afterEach(() => {
    document.body.innerHTML = "";
  });

  it("shows the new profile fields, the sheet link and the primary tag", async () => {
    const wrapper = await open(detail());
    const text = document.body.textContent ?? "";
    expect(text).toContain("+8613800000000");
    expect(text).toContain("ABC123");
    expect(text).toContain("达人邀请码");
    expect(text).not.toContain("kol.inviteCode");
    expect(text).toContain("AgentOne");
    expect(text).toContain("后台录入");
    expect(text).toContain("未激活");
    const link = document.body.querySelector('[data-test="detail-sheet"]');
    expect(link?.getAttribute("href")).toBe(sheet);
    expect(link?.getAttribute("target")).toBe("_blank");
    expect(link?.getAttribute("rel")).toBe("noopener noreferrer");
    expect(document.body.querySelector('[data-test="primary-tag"]')?.textContent).toContain("主");
    expect(document.body.querySelector('[data-test="detail-invite"]')).not.toBeNull();
    wrapper.unmount();
  });

  it("omits the empty legacy platform field while keeping configured platforms", async () => {
    const wrapper = await open(
      detail({
        linkTemplateName: null,
        platforms: [
          { templateId: "7", templateName: "kakobuy", address: "https://example.com/u/a" }
        ]
      })
    );
    const platformLabels = [...document.body.querySelectorAll(".el-descriptions__label")].filter(
      label => label.textContent?.trim() === "代购平台"
    );
    expect(platformLabels).toHaveLength(1);
    expect(document.body.textContent).toContain("kakobuy");
    wrapper.unmount();
  });

  it("hides the invite button unless the account is inactive and entered in the admin console", async () => {
    const activated = await open(detail({ accountStatus: 1 }));
    expect(document.body.querySelector('[data-test="detail-invite"]')).toBeNull();
    expect(document.body.querySelector('[data-test="account-status"]')?.textContent).toContain(
      "已激活"
    );
    activated.unmount();

    const selfRegistered = await open(detail({ registerSource: 2 }));
    expect(document.body.querySelector('[data-test="detail-invite"]')).toBeNull();
    expect(document.body.textContent).toContain("自助注册");
    selfRegistered.unmount();

    const missing = await open(detail({ accountStatus: undefined }));
    expect(document.body.querySelector('[data-test="detail-invite"]')).toBeNull();
    expect(document.body.querySelector('[data-test="account-status"]')?.textContent?.trim()).toBe(
      "-"
    );
    missing.unmount();

    signIn("STAFF", ["kol:view"]);
    const viewer = await open(detail());
    expect(document.body.querySelector('[data-test="detail-invite"]')).toBeNull();
    viewer.unmount();
  });

  it("confirms a sent invite and reloads after a 409", async () => {
    const success = vi.spyOn(ElMessage, "success").mockImplementation(() => ({}) as never);
    vi.mocked(kolApi.inviteKol).mockResolvedValue(null);
    const sent = await open(detail());
    await document.body.querySelector<HTMLButtonElement>('[data-test="detail-invite"]')!.click();
    await flushPromises();
    expect(kolApi.inviteKol).toHaveBeenCalledWith("9007199254740993");
    expect(success).toHaveBeenCalledWith("邀请邮件已发送");
    expect(kolApi.getKol).toHaveBeenCalledTimes(2);
    sent.unmount();

    vi.mocked(kolApi.getKol).mockClear();
    vi.mocked(kolApi.inviteKol).mockRejectedValue(
      new ApiError({ status: 409, errorCode: "KOL_ALREADY_ACTIVATED", traceId: "trace-1" })
    );
    const error = vi.spyOn(ElMessage, "error").mockImplementation(() => ({}) as never);
    const conflict = await open(detail());
    await document.body.querySelector<HTMLButtonElement>('[data-test="detail-invite"]')!.click();
    await flushPromises();
    expect(error).toHaveBeenCalledWith("该达人已激活账号，无需再次邀请");
    expect(kolApi.getKol).toHaveBeenCalledTimes(2);
    conflict.unmount();
  });

  it("shows the relation status and configured platform address", async () => {
    const wrapper = await open(
      detail({
        displayRelationStatus: "PENDING_UPDATE",
        relationStatus: "PROMOTING",
        platforms: [
          { templateId: "7", templateName: "AgentOne", address: "https://shop.example/ada" }
        ]
      })
    );
    const text = document.body.textContent ?? "";
    expect(wrapper.find('[data-test="detail-display-status"]').text()).toBe("待更新");
    expect(text).toContain("https://shop.example/ada");
    wrapper.unmount();
  });

  it("offers the promotion settings from the detail drawer to editors (task 25b)", async () => {
    const wrapper = await open(detail({ displayRelationStatus: "PROMOTING" }));
    const button = wrapper.find('[data-test="detail-edit-config"]');
    expect(button.exists()).toBe(true);
    await button.trigger("click");
    const emittedKol = wrapper.emitted("openConfig")?.[0]?.[0] as { id?: string } | undefined;
    expect(emittedKol?.id).toBe(detail().id);
    wrapper.unmount();
  });
});
