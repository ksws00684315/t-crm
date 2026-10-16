import { DOMWrapper, flushPromises, mount, type VueWrapper } from "@vue/test-utils";
import ElementPlus, { ElMessage, ElMessageBox } from "element-plus";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { TenantVO } from "@/api/platform/tenant";
import * as tenantApi from "@/api/platform/tenant";
import * as platformUserApi from "@/api/platform/user";
import { i18n } from "@/locales";
import { ApiError } from "@/utils/http/error";
import JoinMemberDialog from "../JoinMemberDialog.vue";
import TenantAdminsDialog from "../TenantAdminsDialog.vue";

vi.mock("@/api/platform/user");
vi.mock("@/api/platform/tenant");

const tenant: TenantVO = {
  id: "20",
  tenantCode: "company-b",
  name: "B 公司",
  status: 1,
  remark: null,
  createTime: "2026-10-05T00:00:00Z"
};

const account: platformUserApi.PlatformUserVO = {
  id: "7",
  username: "alice",
  nickname: "Alice",
  status: 1,
  companies: [{ tenantId: "10", tenantName: "A 公司", role: "SUPER_ADMIN", status: 1 }]
};

function byTest(wrapper: VueWrapper, name: string, testId: string) {
  const found = wrapper
    .findAllComponents({ name })
    .find(item => item.attributes("data-test") === testId);
  if (!found) throw new Error(`missing ${name} ${testId}`);
  return found;
}

function input(testId: string): DOMWrapper<HTMLInputElement> {
  const host = document.body.querySelector(`[data-test="${testId}"]`);
  const field = host instanceof HTMLInputElement ? host : host?.querySelector("input");
  if (!(field instanceof HTMLInputElement)) throw new Error(`missing input ${testId}`);
  return new DOMWrapper(field);
}

function click(testId: string): void {
  const button = document.body.querySelector(`button[data-test="${testId}"]`);
  if (!(button instanceof HTMLButtonElement)) throw new Error(`missing button ${testId}`);
  button.click();
}

async function openWithAccount(found: platformUserApi.PlatformUserVO | null = account) {
  vi.mocked(platformUserApi.findPlatformUser).mockResolvedValue(found);
  const wrapper = mount(JoinMemberDialog, {
    props: { modelValue: false, tenant },
    global: { plugins: [ElementPlus, i18n] },
    attachTo: document.body
  });
  await wrapper.setProps({ modelValue: true });
  await flushPromises();
  await input("lookup-username").setValue("alice");
  click("lookup");
  await flushPromises();
  return wrapper;
}

describe("platform: add an existing account to a company", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.spyOn(ElMessage, "success").mockImplementation(() => ({}) as never);
    vi.spyOn(ElMessage, "error").mockImplementation(() => ({}) as never);
  });

  afterEach(() => {
    document.body.innerHTML = "";
  });

  it("names the dialog Members and opens the join dialog from there", async () => {
    vi.mocked(tenantApi.listTenantAdmins).mockResolvedValue([]);
    const wrapper = mount(TenantAdminsDialog, {
      props: { modelValue: true, tenant },
      global: { plugins: [ElementPlus, i18n] },
      attachTo: document.body
    });
    await flushPromises();
    expect(document.body.textContent).toContain("成员：B 公司");
    click("join-member");
    await flushPromises();
    expect(wrapper.findComponent(JoinMemberDialog).props("modelValue")).toBe(true);
    expect(document.body.textContent).toContain("加入已有账号：B 公司");
  });

  it("looks up the exact username and shows the companies it already belongs to", async () => {
    await openWithAccount();
    expect(platformUserApi.findPlatformUser).toHaveBeenCalledWith("alice");
    const result = document.body.querySelector('[data-test="lookup-result"]');
    expect(result?.textContent).toContain("Alice");
    expect(result?.textContent).toContain("A 公司 · 超级管理员");
  });

  it("says when no account has that username", async () => {
    await openWithAccount(null);
    expect(document.body.querySelector('[data-test="lookup-empty"]')?.textContent).toContain(
      "没有用户名为 alice 的账号"
    );
    expect(
      (document.body.querySelector('button[data-test="join-submit"]') as HTMLButtonElement).disabled
    ).toBe(true);
  });

  it("adds a sub-account with post template and data scope", async () => {
    vi.mocked(platformUserApi.addTenantMember).mockResolvedValue(null);
    const wrapper = await openWithAccount();
    byTest(wrapper, "ElSelect", "join-post").vm.$emit("update:modelValue", "BD");
    byTest(wrapper, "ElSelect", "join-scope").vm.$emit("update:modelValue", "OWN");
    await flushPromises();
    click("join-submit");
    await flushPromises();
    expect(platformUserApi.addTenantMember).toHaveBeenCalledWith("20", {
      userId: "7",
      role: "STAFF",
      postTemplate: "BD",
      dataScope: "OWN"
    });
    expect(ElMessage.success).toHaveBeenCalledWith("已加入 B 公司");
    expect(wrapper.emitted("joined")).toHaveLength(1);
  });

  it("omits the data scope so the template default applies", async () => {
    vi.mocked(platformUserApi.addTenantMember).mockResolvedValue(null);
    const wrapper = await openWithAccount();
    byTest(wrapper, "ElSelect", "join-post").vm.$emit("update:modelValue", "OPERATOR");
    await flushPromises();
    click("join-submit");
    await flushPromises();
    expect(platformUserApi.addTenantMember).toHaveBeenCalledWith("20", {
      userId: "7",
      role: "STAFF",
      postTemplate: "OPERATOR",
      dataScope: undefined
    });
  });

  it("adds a super administrator without template or scope", async () => {
    vi.mocked(platformUserApi.addTenantMember).mockResolvedValue(null);
    const wrapper = await openWithAccount();
    byTest(wrapper, "ElRadioGroup", "join-role").vm.$emit("update:modelValue", "SUPER_ADMIN");
    await flushPromises();
    click("join-submit");
    await flushPromises();
    expect(platformUserApi.addTenantMember).toHaveBeenCalledWith("20", {
      userId: "7",
      role: "SUPER_ADMIN"
    });
  });

  it("requires a post template for a sub-account", async () => {
    await openWithAccount();
    click("join-submit");
    await flushPromises();
    expect(platformUserApi.addTenantMember).not.toHaveBeenCalled();
    await vi.waitFor(() => expect(document.body.textContent).toContain("请选择岗位模板"));
  });

  it("shows the 409 USER_ALREADY_MEMBER text", async () => {
    vi.mocked(platformUserApi.addTenantMember).mockRejectedValue(
      new ApiError({ status: 409, errorCode: "USER_ALREADY_MEMBER", traceId: "t" })
    );
    const wrapper = await openWithAccount();
    byTest(wrapper, "ElRadioGroup", "join-role").vm.$emit("update:modelValue", "SUPER_ADMIN");
    await flushPromises();
    click("join-submit");
    await flushPromises();
    expect(ElMessage.error).toHaveBeenCalledWith("该账号已是这家公司的成员");
  });

  it("does not offer joining a company the account is already in", async () => {
    await openWithAccount({
      ...account,
      companies: [
        ...account.companies,
        { tenantId: "20", tenantName: "B 公司", role: "STAFF", status: 1 }
      ]
    });
    expect(document.body.querySelector('[data-test="already-member"]')).not.toBeNull();
    expect(document.body.querySelector('[data-test="join-form"]')).toBeNull();
  });

  it("edits name and email of the account", async () => {
    vi.mocked(platformUserApi.updatePlatformUser).mockResolvedValue(null);
    await openWithAccount();
    click("account-edit");
    await flushPromises();
    expect(input("account-nickname").element.value).toBe("Alice");
    await input("account-nickname").setValue("Alice Chen");
    await input("account-email").setValue("alice@example.com");
    click("account-save");
    await flushPromises();
    expect(platformUserApi.updatePlatformUser).toHaveBeenCalledWith("7", {
      nickname: "Alice Chen",
      email: "alice@example.com"
    });
    expect(platformUserApi.findPlatformUser).toHaveBeenCalledTimes(2);
  });

  it("warns that a password reset signs the account out of every company", async () => {
    const confirm = vi.spyOn(ElMessageBox, "confirm").mockResolvedValue("confirm" as never);
    vi.mocked(platformUserApi.resetPlatformUserPassword).mockResolvedValue(null);
    await openWithAccount();
    click("account-reset");
    await flushPromises();
    await input("account-password").setValue("NewSecret123!");
    click("account-reset-submit");
    await flushPromises();
    expect(confirm).toHaveBeenCalledWith(
      "重置后，该账号在所有公司的登录都会失效。确定继续吗？",
      "重置密码",
      expect.anything()
    );
    expect(platformUserApi.resetPlatformUserPassword).toHaveBeenCalledWith("7", "NewSecret123!");
  });

  it("does not reset when the confirmation is cancelled", async () => {
    vi.spyOn(ElMessageBox, "confirm").mockRejectedValue("cancel");
    await openWithAccount();
    click("account-reset");
    await flushPromises();
    await input("account-password").setValue("NewSecret123!");
    click("account-reset-submit");
    await flushPromises();
    expect(platformUserApi.resetPlatformUserPassword).not.toHaveBeenCalled();
  });

  it("disables the whole account after a confirmation that names every company", async () => {
    const confirm = vi.spyOn(ElMessageBox, "confirm").mockResolvedValue("confirm" as never);
    vi.mocked(platformUserApi.updatePlatformUserStatus).mockResolvedValue(null);
    await openWithAccount();
    click("account-status");
    await flushPromises();
    expect(confirm).toHaveBeenCalledWith(
      "停用后，alice 在所有公司都无法登录，已登录的会被退出。确定停用吗？",
      "停用账号",
      expect.anything()
    );
    expect(platformUserApi.updatePlatformUserStatus).toHaveBeenCalledWith("7", 0);
  });
});
