import { flushPromises, mount } from "@vue/test-utils";
import { nextTick } from "vue";
import ElementPlus, { ElMessage, ElMessageBox } from "element-plus";
import { createPinia, setActivePinia } from "pinia";
import { beforeEach, describe, expect, it, vi } from "vitest";
import * as kolApi from "@/api/kol/kol";
import * as templateApi from "@/api/link/template";
import * as bdApi from "@/api/system/bd-user";
import type { DataScope, KolVO, Role } from "@/api/types";
import { i18n } from "@/locales";
import { useLocaleStore } from "@/stores/locale";
import { useUserStore } from "@/stores/user";
import { ApiError } from "@/utils/http/error";
import KolPage from "../index.vue";

vi.mock("@/api/kol/kol");
vi.mock("@/api/link/link");
vi.mock("@/api/link/template");
vi.mock("@/api/system/bd-user");

const row: KolVO = {
  id: "9007199254740993",
  kolCode: "KOL0001",
  name: "Alice",
  email: "alice@example.com",
  country: "US",
  socialAccounts: [],
  bdUserId: null,
  bdUserName: null,
  defaultRate: "0.1550",
  status: 1,
  remark: null,
  whatsapp: "+8613800000000",
  linkTemplateId: "7",
  linkTemplateName: "AgentOne",
  inviteCode: "ABC123",
  sheetUrl: "https://docs.google.com/spreadsheets/d/abc",
  registerSource: 1,
  accountStatus: 0,
  createTime: "2026-10-05T01:02:03Z",
  updateTime: "2026-10-05T01:02:03Z"
};

function signIn(role: Role, dataScope: DataScope, permissions: string[] = []) {
  useUserStore().user = {
    id: "1",
    username: "me",
    nickname: "Me",
    email: null,
    role,
    postTemplate: role === "STAFF" ? "BD" : null,
    permissions,
    dataScope,
    tenantId: "1",
    tenantName: "默认公司",
    status: 1,
    lastLoginTime: null,
    createTime: "2026-10-01T00:00:00Z"
  };
}

async function mountPage(total = "1", items: KolVO[] = [row]) {
  vi.mocked(kolApi.listKols).mockResolvedValue({ items, total, page: 1, size: 20 });
  vi.mocked(bdApi.listBdUsers).mockResolvedValue([
    { id: "5", username: "bd5", nickname: "BD Five" }
  ]);
  const wrapper = mount(KolPage, {
    global: { plugins: [ElementPlus, i18n] },
    attachTo: document.body
  });
  await flushPromises();
  return wrapper;
}

describe("KOL list page", () => {
  beforeEach(() => {
    document.body.innerHTML = "";
    localStorage.removeItem("tcrm.admin.table.kol");
    localStorage.removeItem("tcrm.admin.locale");
    vi.mocked(templateApi.listLinkTemplates).mockResolvedValue([]);
    setActivePinia(createPinia());
  });

  it("renders rows with rate percentage and the unassigned BD in Chinese", async () => {
    signIn("SUPER_ADMIN", "ALL");
    const wrapper = await mountPage();
    const text = wrapper.text();
    expect(text).toContain("KOL0001");
    expect(text).toContain("15.5%");
    expect(text).toContain("未分配");
    expect(text).toContain("启用");
  });

  it("renders the creator list in English", async () => {
    useLocaleStore().setLocale("en-US");
    signIn("SUPER_ADMIN", "ALL");
    const wrapper = await mountPage();
    const text = wrapper.text();
    expect(text).toContain("Creator Code");
    expect(text).toContain("Unassigned");
    expect(text).toContain("Enabled");
    expect(text).toContain("My registration link");
  });

  it("converts the string total before handing it to el-pagination", async () => {
    signIn("STAFF", "ALL", ["kol:view"]);
    const wrapper = await mountPage("42");
    const pagination = wrapper.findComponent({ name: "ElPagination" });
    expect(pagination.props("total")).toBe(42);
    expect(wrapper.find(".el-pagination").exists()).toBe(true);
  });

  it("shows write, BD change, BD filter, links and export to the super administrator", async () => {
    signIn("SUPER_ADMIN", "ALL");
    const wrapper = await mountPage();
    // Creating creators is hidden for now (owner decision 2026-10-16).
    expect(wrapper.find('[data-test="create"]').exists()).toBe(false);
    expect(wrapper.find('[data-test="import"]').exists()).toBe(false);
    expect(wrapper.find('[data-test="edit"]').exists()).toBe(true);
    expect(wrapper.find('[data-test="status"]').exists()).toBe(true);
    expect(wrapper.find('[data-test="change-bd"]').exists()).toBe(true);
    expect(wrapper.find('[data-test="bd-filter"]').exists()).toBe(true);
    expect(wrapper.find('[data-test="links"]').exists()).toBe(true);
    expect(wrapper.find('[data-test="export"]').exists()).toBe(true);
    expect(wrapper.text()).toContain("详情");
    expect(bdApi.listBdUsers).toHaveBeenCalledTimes(1);
  });

  it("lets a non-OWN editor write, filter and assign, but not change BD", async () => {
    signIn("STAFF", "ALL", ["kol:view", "kol:edit", "product:view", "data:export"]);
    const wrapper = await mountPage();
    expect(wrapper.find('[data-test="create"]').exists()).toBe(false);
    expect(wrapper.find('[data-test="import"]').exists()).toBe(false);
    expect(wrapper.find('[data-test="edit"]').exists()).toBe(true);
    expect(wrapper.find('[data-test="status"]').exists()).toBe(true);
    expect(wrapper.find('[data-test="change-bd"]').exists()).toBe(false);
    expect(wrapper.find('[data-test="bd-filter"]').exists()).toBe(true);
    expect(wrapper.find('[data-test="links"]').exists()).toBe(true);
    expect(wrapper.find('[data-test="export"]').exists()).toBe(true);
    expect(bdApi.listBdUsers).toHaveBeenCalledTimes(1);
    wrapper.unmount();
  });

  it("hides BD filter, BD assign and export for an OWN editor", async () => {
    signIn("STAFF", "OWN", ["kol:view", "kol:edit", "product:view"]);
    const wrapper = await mountPage();
    expect(wrapper.find('[data-test="create"]').exists()).toBe(false);
    expect(wrapper.find('[data-test="import"]').exists()).toBe(false);
    expect(wrapper.find('[data-test="edit"]').exists()).toBe(true);
    expect(wrapper.find('[data-test="status"]').exists()).toBe(true);
    expect(wrapper.find('[data-test="change-bd"]').exists()).toBe(false);
    expect(wrapper.find('[data-test="bd-filter"]').exists()).toBe(false);
    expect(wrapper.find('[data-test="links"]').exists()).toBe(true);
    expect(wrapper.find('[data-test="export"]').exists()).toBe(false);
    expect(bdApi.listBdUsers).not.toHaveBeenCalled();
    wrapper.unmount();
  });

  it("shows the BD filter without write buttons when the account can only view creators", async () => {
    signIn("STAFF", "ALL", ["kol:view"]);
    const wrapper = await mountPage();
    expect(wrapper.find('[data-test="create"]').exists()).toBe(false);
    expect(wrapper.find('[data-test="import"]').exists()).toBe(false);
    expect(wrapper.find('[data-test="edit"]').exists()).toBe(false);
    expect(wrapper.find('[data-test="status"]').exists()).toBe(false);
    expect(wrapper.find('[data-test="change-bd"]').exists()).toBe(false);
    expect(wrapper.find('[data-test="bd-filter"]').exists()).toBe(true);
    expect(wrapper.find('[data-test="links"]').exists()).toBe(false);
    expect(wrapper.find('[data-test="export"]').exists()).toBe(false);
    expect(bdApi.listBdUsers).toHaveBeenCalledTimes(1);
  });

  it("never sends a BD filter for an OWN account", async () => {
    signIn("STAFF", "OWN", ["kol:view"]);
    await mountPage();
    expect(kolApi.listKols).toHaveBeenCalledWith({
      page: 1,
      size: 20,
      keyword: undefined,
      country: undefined,
      status: undefined,
      bdUserId: undefined
    });
  });

  it("pages with the applied conditions, not text typed without searching", async () => {
    signIn("SUPER_ADMIN", "ALL");
    const wrapper = await mountPage("60");
    const keyword = wrapper.findAll("input").find(input => input.attributes("maxlength") === "128");
    await keyword!.setValue("applied");
    await keyword!.trigger("keyup.enter");
    await flushPromises();
    await keyword!.setValue("typed-only");
    vi.mocked(kolApi.listKols).mockClear();
    wrapper.findComponent({ name: "ElPagination" }).vm.$emit("current-change", 2);
    await flushPromises();
    expect(kolApi.listKols).toHaveBeenLastCalledWith(
      expect.objectContaining({ page: 2, keyword: "applied" })
    );
    expect(wrapper.text()).toContain("applied");
    expect(wrapper.text()).not.toContain("typed-only");
  });

  it("shows the platform, the sheet link and the account, and can hide the platform column", async () => {
    signIn("SUPER_ADMIN", "ALL");
    const wrapper = await mountPage();
    const link = wrapper.get('[data-test="sheet-link"]');
    expect(link.attributes("href")).toBe(row.sheetUrl);
    expect(link.attributes("target")).toBe("_blank");
    expect(link.attributes("rel")).toBe("noopener noreferrer");
    expect(wrapper.get('[data-test="account"]').text()).toContain("未激活");
    expect(wrapper.text()).toContain("AgentOne");
    await wrapper.get('[data-test="column-settings"]').trigger("click");
    await nextTick();
    const box = [...document.body.querySelectorAll(".el-checkbox")].find(element =>
      element.textContent?.includes("代购平台")
    );
    box?.querySelector("input")?.click();
    await nextTick();
    expect(wrapper.text()).not.toContain("AgentOne");
    wrapper.unmount();
  });

  it("shows invite only for an inactive creator entered in the admin console", async () => {
    signIn("STAFF", "ALL", ["kol:view"]);
    const viewer = await mountPage();
    expect(viewer.find('[data-test="invite"]').exists()).toBe(false);
    viewer.unmount();

    signIn("STAFF", "ALL", ["kol:view", "kol:edit"]);
    const activated = await mountPage("1", [{ ...row, accountStatus: 1 }]);
    expect(activated.find('[data-test="invite"]').exists()).toBe(false);
    expect(activated.get('[data-test="account"]').text()).toContain("已激活");
    activated.unmount();

    const selfRegistered = await mountPage("1", [{ ...row, registerSource: 2 }]);
    expect(selfRegistered.find('[data-test="invite"]').exists()).toBe(false);
    selfRegistered.unmount();

    const missing = await mountPage("1", [{ ...row, accountStatus: undefined }]);
    expect(missing.find('[data-test="invite"]').exists()).toBe(false);
    expect(missing.find('[data-test="account"]').exists()).toBe(false);
    expect(missing.text()).toContain("-");
    missing.unmount();
  });

  it("sends an invite and reloads the list after success or 409", async () => {
    signIn("SUPER_ADMIN", "ALL");
    const success = vi.spyOn(ElMessage, "success").mockImplementation(() => ({}) as never);
    vi.mocked(kolApi.inviteKol).mockResolvedValue(null);
    const sent = await mountPage();
    await sent.get('[data-test="invite"]').trigger("click");
    await flushPromises();
    expect(kolApi.inviteKol).toHaveBeenCalledWith(row.id);
    expect(success).toHaveBeenCalledWith("邀请邮件已发送");
    expect(kolApi.listKols).toHaveBeenCalledTimes(2);
    sent.unmount();

    vi.mocked(kolApi.listKols).mockClear();
    vi.mocked(kolApi.inviteKol).mockRejectedValue(
      new ApiError({ status: 409, errorCode: "KOL_ALREADY_ACTIVATED", traceId: "trace-1" })
    );
    const error = vi.spyOn(ElMessage, "error").mockImplementation(() => ({}) as never);
    const conflict = await mountPage();
    vi.mocked(kolApi.listKols).mockClear();
    await conflict.get('[data-test="invite"]').trigger("click");
    await flushPromises();
    expect(error).toHaveBeenCalledWith("该达人已激活账号，无需再次邀请");
    expect(kolApi.listKols).toHaveBeenCalledTimes(1);
    conflict.unmount();
  });

  it("offers the batch transfer to the super administrator with the selection as the target", async () => {
    signIn("SUPER_ADMIN", "ALL");
    const other = { ...row, id: "9007199254740994", kolCode: "KOL0002" };
    const wrapper = await mountPage("2", [row, other]);
    const button = wrapper.get('[data-test="transfer-bd"]');
    expect(button.attributes("disabled")).toBeDefined();
    wrapper
      .findComponent({ name: "DataTable" })
      .vm.$emit("selectionChange", [row as object, other as object]);
    await nextTick();
    expect(button.attributes("disabled")).toBeUndefined();
    await button.trigger("click");
    await flushPromises();
    const dialog = wrapper.findComponent({ name: "BdTransferDialog" });
    expect(dialog.props("modelValue")).toBe(true);
    expect(dialog.props("target")).toEqual({ kolIds: [row.id, other.id] });
    wrapper.unmount();
  });

  it("opens the same transfer dialog from a row and hides it from staff", async () => {
    signIn("STAFF", "ALL", ["kol:view", "kol:edit"]);
    const staffView = await mountPage();
    expect(staffView.find('[data-test="transfer-bd"]').exists()).toBe(false);
    expect(staffView.find('[data-test="change-bd"]').exists()).toBe(false);
    staffView.unmount();

    signIn("SUPER_ADMIN", "ALL");
    const wrapper = await mountPage();
    await wrapper.get('[data-test="change-bd"]').trigger("click");
    await flushPromises();
    const dialog = wrapper.findComponent({ name: "BdTransferDialog" });
    expect(dialog.props("modelValue")).toBe(true);
    expect(dialog.props("target")).toEqual({ kolIds: [row.id] });
    wrapper.unmount();
  });

  it("shows the registration link only with kol:register-link and rotates it after confirmation", async () => {
    signIn("STAFF", "ALL", ["kol:view", "kol:edit"]);
    const writer = await mountPage();
    expect(writer.find('[data-test="register-link"]').exists()).toBe(false);
    writer.unmount();

    signIn("STAFF", "OWN", ["kol:view", "kol:register-link"]);
    vi.mocked(kolApi.getRegisterCode).mockResolvedValue({
      code: "old-code",
      url: "https://kol.example/register?c=old-code",
      status: 1
    });
    vi.mocked(kolApi.rotateRegisterCode).mockResolvedValue({
      code: "new-code",
      url: "https://kol.example/register?c=new-code",
      status: 1
    });
    const wrapper = await mountPage();
    expect(wrapper.find('[data-test="create"]').exists()).toBe(false);
    await wrapper.get('[data-test="register-link"]').trigger("click");
    await flushPromises();
    expect(document.body.textContent).toContain("https://kol.example/register?c=old-code");
    expect(document.body.textContent).toContain("把链接发给达人，达人注册后自动归属到你名下。");

    vi.spyOn(ElMessageBox, "confirm").mockRejectedValueOnce("cancel");
    document.body.querySelector<HTMLButtonElement>('[data-test="rotate-link"]')!.click();
    await flushPromises();
    expect(kolApi.rotateRegisterCode).not.toHaveBeenCalled();

    vi.spyOn(ElMessageBox, "confirm").mockResolvedValueOnce("confirm" as never);
    vi.spyOn(ElMessage, "success").mockImplementation(() => ({}) as never);
    document.body.querySelector<HTMLButtonElement>('[data-test="rotate-link"]')!.click();
    await flushPromises();
    expect(kolApi.rotateRegisterCode).toHaveBeenCalledTimes(1);
    expect(document.body.textContent).toContain("https://kol.example/register?c=new-code");
    expect(ElMessage.success).toHaveBeenCalledWith("已生成新的注册链接");
    wrapper.unmount();
  });
});
