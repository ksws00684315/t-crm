import { DOMWrapper, flushPromises, mount } from "@vue/test-utils";
import { nextTick } from "vue";
import ElementPlus, { ElMessage, ElMessageBox } from "element-plus";
import { createPinia, setActivePinia } from "pinia";
import { beforeEach, describe, expect, it, vi } from "vitest";
import * as kolApi from "@/api/kol/kol";
import * as promotionApi from "@/api/kol/promotion";
import * as templateApi from "@/api/link/template";
import * as bdApi from "@/api/system/bd-user";
import type { DataScope, KolVO, Role } from "@/api/types";
import { i18n } from "@/locales";
import { useLocaleStore } from "@/stores/locale";
import { useUserStore } from "@/stores/user";
import { ApiError } from "@/utils/http/error";
import KolPage from "../index.vue";

vi.mock("@/api/kol/kol");
vi.mock("@/api/kol/promotion");
vi.mock("@/api/link/link");
vi.mock("@/api/link/template");
vi.mock("@/api/system/bd-user");

const row: KolVO = {
  id: "9007199254740993",
  kolCode: "KOL-ASD001",
  name: "Alice",
  email: "alice@example.com",
  country: "US",
  socialAccounts: [
    { platform: "TikTok", handle: "@alice", url: null, followers: null, primary: true }
  ],
  bdUserId: null,
  bdUserName: null,
  defaultRate: "0.1550",
  status: 1,
  remark: null,
  whatsapp: "+8613800000000",
  phone: null,
  linkTemplateId: "7",
  linkTemplateName: "AgentOne",
  inviteCode: "ABC123",
  sheetUrl: "https://docs.google.com/spreadsheets/d/abc",
  registerSource: 1,
  accountStatus: 0,
  reviewStatus: "APPROVED",
  relationStatus: "PENDING_PROMOTION",
  displayRelationStatus: "PENDING_PROMOTION",
  loginStatus: "NORMAL",
  platforms: [],
  jumpTemplateId: null,
  configVersion: 1,
  inviteTime: "2026-10-05T01:02:03Z",
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
    expect(text).toContain("KOL-ASD001");
    expect(text).toContain("15.5%");
    expect(text).toContain("未分配");
    expect(text).toContain("正常");
  });

  it("renders the creator list in English", async () => {
    useLocaleStore().setLocale("en-US");
    signIn("SUPER_ADMIN", "ALL");
    const wrapper = await mountPage();
    const text = wrapper.text();
    expect(text).toContain("Creator Code");
    expect(text).toContain("Unassigned");
    expect(text).toContain("Normal");
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

  it("shows contact and primary platform, and can hide the platform column", async () => {
    signIn("SUPER_ADMIN", "ALL");
    const wrapper = await mountPage();
    expect(wrapper.text()).toContain("+8613800000000");
    expect(wrapper.text()).toContain("TikTok / @alice");
    await wrapper.get('[data-test="column-settings"]').trigger("click");
    await nextTick();
    const box = [...document.body.querySelectorAll(".el-checkbox")].find(element =>
      element.textContent?.includes("核心社媒平台及 ID")
    );
    box?.querySelector("input")?.click();
    await nextTick();
    expect(wrapper.text()).not.toContain("TikTok / @alice");
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
    expect(
      activated.findAll('[data-test="login-status"]').some(tag => tag.text().includes("正常"))
    ).toBe(true);
    activated.unmount();

    const selfRegistered = await mountPage("1", [{ ...row, registerSource: 2 }]);
    expect(selfRegistered.find('[data-test="invite"]').exists()).toBe(false);
    selfRegistered.unmount();

    const missing = await mountPage("1", [{ ...row, accountStatus: undefined }]);
    expect(missing.find('[data-test="invite"]').exists()).toBe(false);
    expect(missing.find('[data-test="login-status"]').exists()).toBe(true);
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
    const other = { ...row, id: "9007199254740994", kolCode: "KOL-QWE002" };
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

  it("shows the registration link only to an enabled OWN-scope BD with kol:edit and kol:register-link", async () => {
    signIn("STAFF", "ALL", ["kol:view", "kol:edit", "kol:register-link"]);
    const writer = await mountPage();
    expect(writer.find('[data-test="register-link"]').exists()).toBe(false);
    writer.unmount();

    signIn("SUPER_ADMIN", "ALL");
    const admin = await mountPage();
    expect(admin.find('[data-test="register-link"]').exists()).toBe(false);
    admin.unmount();

    signIn("STAFF", "OWN", ["kol:view", "kol:edit"]);
    const noPerm = await mountPage();
    expect(noPerm.find('[data-test="register-link"]').exists()).toBe(false);
    noPerm.unmount();

    signIn("STAFF", "OWN", ["kol:view", "kol:edit", "kol:register-link"]);
    vi.mocked(kolApi.getRegisterCode).mockResolvedValue({
      code: "old-code",
      url: "https://kol.example/register?code=old-code"
    });
    vi.mocked(kolApi.rotateRegisterCode).mockResolvedValue({
      code: "new-code",
      url: "https://kol.example/register?code=new-code"
    });
    const wrapper = await mountPage();
    expect(wrapper.find('[data-test="create"]').exists()).toBe(false);
    await wrapper.get('[data-test="register-link"]').trigger("click");
    await flushPromises();
    expect(document.body.textContent).toContain("old-code");
    expect(document.body.textContent).toContain("https://kol.example/register?code=old-code");
    expect(document.body.textContent).toContain("把链接发给达人，达人注册后自动归属到你名下。");
    const codeRow = wrapper.find('[data-test="register-code"]');
    const linkRow = wrapper.find('[data-test="register-url"]');
    expect(codeRow.exists()).toBe(true);
    expect(linkRow.exists()).toBe(true);
    expect(codeRow.find('[data-test="copy"]').exists()).toBe(true);
    expect(linkRow.find('[data-test="copy"]').exists()).toBe(true);

    vi.spyOn(ElMessageBox, "confirm").mockRejectedValueOnce("cancel");
    document.body.querySelector<HTMLButtonElement>('[data-test="rotate-link"]')!.click();
    await flushPromises();
    expect(kolApi.rotateRegisterCode).not.toHaveBeenCalled();

    vi.spyOn(ElMessageBox, "confirm").mockResolvedValueOnce("confirm" as never);
    vi.spyOn(ElMessage, "success").mockImplementation(() => ({}) as never);
    document.body.querySelector<HTMLButtonElement>('[data-test="rotate-link"]')!.click();
    await flushPromises();
    expect(kolApi.rotateRegisterCode).toHaveBeenCalledTimes(1);
    expect(document.body.textContent).toContain("https://kol.example/register?code=new-code");
    expect(ElMessage.success).toHaveBeenCalledWith("已生成新的注册链接");
    wrapper.unmount();
  });

  it("shows every display status with its own tag", async () => {
    signIn("SUPER_ADMIN", "ALL");
    const statuses = ["PENDING_PROMOTION", "PROMOTING", "INVALID", "PENDING_UPDATE"] as const;
    const items = statuses.map((status, index) => ({
      ...row,
      id: `900719925474099${index}`,
      reviewStatus: "APPROVED" as const,
      relationStatus: status === "PENDING_UPDATE" ? ("PROMOTING" as const) : status,
      displayRelationStatus: status
    }));
    const wrapper = await mountPage("5", items);
    const tags = wrapper.findAll('[data-test="relation-status"]').filter(tag => tag.text() !== "-");
    expect(tags.map(tag => tag.text())).toEqual(["待推广", "推广中", "无效", "待更新"]);
    const expectedTypes = ["primary", "success", "info", "danger"];
    expectedTypes.forEach((type, index) => {
      expect(tags[index].find(".el-tag").classes()).toContain(`el-tag--${type}`);
    });
    // The account enabled/disabled column is renamed so only one column is called 状态.
    expect(wrapper.text()).toContain("审核状态");
    wrapper.unmount();
  });

  it("sends the relation status filter once applied and drops it with the chip", async () => {
    signIn("SUPER_ADMIN", "ALL");
    const wrapper = await mountPage();
    await wrapper.get('[data-test="search-expand"]').trigger("click");
    await nextTick();
    await wrapper.get('[data-test="relation-filter"]').trigger("click");
    await nextTick();
    const option = [...document.body.querySelectorAll(".el-select-dropdown__item")].find(item =>
      item.textContent?.includes("推广中")
    );
    await new DOMWrapper(option!).trigger("click");
    await nextTick();
    vi.mocked(kolApi.listKols).mockClear();
    await wrapper.get('[data-test="search"]').trigger("click");
    await flushPromises();
    expect(kolApi.listKols).toHaveBeenLastCalledWith(
      expect.objectContaining({ relationStatus: "PROMOTING" })
    );
    expect(wrapper.text()).toContain("推广中");

    const chip = wrapper
      .findAll('[data-test="search-chip"]')
      .find(element => element.text().includes("推广中"));
    expect(chip).toBeDefined();
    chip!.get("button").trigger("click");
    await flushPromises();
    expect(kolApi.listKols).toHaveBeenLastCalledWith(
      expect.objectContaining({ relationStatus: undefined })
    );
    wrapper.unmount();
  });

  it("filters by PENDING_UPDATE and FROZEN using their independent query parameters", async () => {
    signIn("SUPER_ADMIN", "ALL");
    const wrapper = await mountPage();
    await wrapper.get('[data-test="search-expand"]').trigger("click");
    await nextTick();

    await wrapper.get('[data-test="relation-filter"] .el-select__wrapper').trigger("click");
    await flushPromises();
    const pendingUpdate = [...document.body.querySelectorAll(".el-select-dropdown__item")].find(
      item => item.textContent?.includes("待更新")
    );
    await new DOMWrapper(pendingUpdate!).trigger("click");
    await wrapper.get('[data-test="login-filter"] .el-select__wrapper').trigger("click");
    await flushPromises();
    const frozen = [...document.body.querySelectorAll(".el-select-dropdown__item")].find(item =>
      item.textContent?.includes("冻结")
    );
    await new DOMWrapper(frozen!).trigger("click");

    vi.mocked(kolApi.listKols).mockClear();
    await wrapper.get('[data-test="search"]').trigger("click");
    await flushPromises();
    expect(kolApi.listKols).toHaveBeenLastCalledWith(
      expect.objectContaining({ relationStatus: "PENDING_UPDATE", loginStatus: "FROZEN" })
    );
    wrapper.unmount();
  });

  it("opens the review screen when clicking 待审核", async () => {
    signIn("STAFF", "OWN", ["kol:view", "kol:edit"]);
    const wrapper = await mountPage("1", [{ ...row, reviewStatus: "PENDING" }]);
    const reviewTag = wrapper
      .findAll('[data-test="review-status"]')
      .filter(tag => tag.text().includes("待审核"))
      .at(-1);
    await new DOMWrapper(reviewTag!.element.parentElement!).trigger("click");
    await flushPromises();
    const dialog = wrapper.findComponent({ name: "KolFormDialog" });
    expect(dialog.props("modelValue")).toBe(true);
    expect(dialog.props("review")).toBe(true);
    expect(dialog.props("kol")?.id).toBe(row.id);
    wrapper.unmount();
  });

  it("opens the promotion settings when clicking 待推广", async () => {
    signIn("STAFF", "OWN", ["kol:view", "kol:edit"]);
    vi.mocked(promotionApi.getPromotionConfig).mockResolvedValue({
      defaultRate: null,
      platforms: [],
      inviteCode: null,
      sheetUrl: null,
      jumpTemplateId: null,
      remark: null
    });
    vi.mocked(promotionApi.listPromotionRecords).mockResolvedValue({
      items: [],
      total: "0",
      page: 1,
      size: 10
    });
    const wrapper = await mountPage("1", [{ ...row, displayRelationStatus: "PENDING_PROMOTION" }]);
    const pendingTag = wrapper
      .findAll('[data-test="relation-status"]')
      .filter(tag => tag.text().includes("待推广"))
      .at(-1);
    await new DOMWrapper(pendingTag!.element.parentElement!).trigger("click");
    await flushPromises();
    const drawer = wrapper.findComponent({ name: "PromotionConfigDrawer" });
    expect(drawer.props("modelValue")).toBe(true);
    expect(drawer.props("kol")?.id).toBe(row.id);
    wrapper.unmount();
  });

  it("opens read-only promotion settings for an unreviewed 待推广 creator", async () => {
    signIn("STAFF", "OWN", ["kol:view", "kol:edit"]);
    vi.mocked(promotionApi.getPromotionConfig).mockResolvedValue({
      defaultRate: null,
      platforms: [],
      inviteCode: null,
      sheetUrl: null,
      jumpTemplateId: null,
      remark: null
    });
    vi.mocked(promotionApi.listPromotionRecords).mockResolvedValue({
      items: [],
      total: "0",
      page: 1,
      size: 10
    });
    const wrapper = await mountPage("1", [
      { ...row, reviewStatus: "PENDING", displayRelationStatus: "PENDING_PROMOTION" }
    ]);
    const pendingTag = wrapper
      .findAll('[data-test="relation-status"]')
      .filter(tag => tag.text().includes("待推广"))
      .at(-1);
    await new DOMWrapper(pendingTag!.element.parentElement!).trigger("click");
    await flushPromises();
    const drawer = wrapper.findComponent({ name: "PromotionConfigDrawer" });
    expect(drawer.props("modelValue")).toBe(true);
    expect(drawer.props("readOnly")).toBe(true);
    wrapper.unmount();
  });

  it("opens read-only promotion records for 推广中 and 无效", async () => {
    signIn("SUPER_ADMIN", "ALL");
    vi.mocked(promotionApi.getPromotionConfig).mockResolvedValue({
      defaultRate: "0.1550",
      platforms: [],
      inviteCode: "ADA",
      sheetUrl: "https://docs.google.com/spreadsheets/d/abc",
      jumpTemplateId: null,
      remark: null
    });
    vi.mocked(promotionApi.listPromotionRecords).mockResolvedValue({
      items: [],
      total: "0",
      page: 1,
      size: 10
    });
    const promoting = await mountPage("1", [{ ...row, displayRelationStatus: "PROMOTING" }]);
    const promotingTag = promoting
      .findAll('[data-test="relation-status"]')
      .filter(tag => tag.text() === "推广中")
      .at(-1);
    await new DOMWrapper(promotingTag!.element.parentElement!).trigger("click");
    await flushPromises();
    const promotingConfig = promoting.findComponent({ name: "PromotionConfigDrawer" });
    expect(promotingConfig.props("modelValue")).toBe(true);
    expect(promotingConfig.props("readOnly")).toBe(true);
    promoting.unmount();

    const ended = await mountPage("1", [{ ...row, displayRelationStatus: "INVALID" }]);
    const endedTag = ended
      .findAll('[data-test="relation-status"]')
      .filter(tag => tag.text() === "无效")
      .at(-1);
    await new DOMWrapper(endedTag!.element.parentElement!).trigger("click");
    await flushPromises();
    const invalidConfig = ended.findComponent({ name: "PromotionConfigDrawer" });
    expect(invalidConfig.props("modelValue")).toBe(true);
    expect(invalidConfig.props("readOnly")).toBe(true);
    expect(promotionApi.getPromotionConfig).toHaveBeenCalledWith(row.id);
    ended.unmount();
  });

  it("routes 待更新 to the BD transfer for a super admin, and to a hint for a BD", async () => {
    signIn("SUPER_ADMIN", "ALL");
    const admin = await mountPage("1", [{ ...row, displayRelationStatus: "PENDING_UPDATE" }]);
    const adminTag = admin
      .findAll('[data-test="relation-status"]')
      .filter(tag => tag.text() === "待更新")
      .at(-1);
    await new DOMWrapper(adminTag!.element.parentElement!).trigger("click");
    await flushPromises();
    expect(admin.findComponent({ name: "BdTransferDialog" }).props("modelValue")).toBe(true);
    admin.unmount();

    const info = vi.spyOn(ElMessage, "info").mockImplementation(() => ({}) as never);
    signIn("STAFF", "OWN", ["kol:view", "kol:edit"]);
    const bd = await mountPage("1", [{ ...row, displayRelationStatus: "PENDING_UPDATE" }]);
    const bdTag = bd
      .findAll('[data-test="relation-status"]')
      .filter(tag => tag.text() === "待更新")
      .at(-1);
    await new DOMWrapper(bdTag!.element.parentElement!).trigger("click");
    await flushPromises();
    expect(info).toHaveBeenCalledWith(expect.stringContaining("未绑定在职商务"));
    expect(bd.findComponent({ name: "BdTransferDialog" }).props("modelValue")).toBe(false);
    bd.unmount();
  });

  it("opens promotion records read-only for viewers and blocks review editing", async () => {
    const info = vi.spyOn(ElMessage, "info").mockImplementation(() => ({}) as never);
    signIn("STAFF", "ALL", ["kol:view"]);
    const pending = await mountPage("1", [{ ...row, reviewStatus: "PENDING" }]);
    const reviewTag = pending
      .findAll('[data-test="review-status"]')
      .filter(tag => tag.text().includes("待审核"))
      .at(-1)!;
    await new DOMWrapper(reviewTag.element.parentElement!).trigger("click");
    expect(pending.findComponent({ name: "KolFormDialog" }).props("modelValue")).toBe(false);
    expect(info).toHaveBeenCalledWith("当前账号只有查看权限，不能进入可编辑的审核或推广设置。");
    pending.unmount();

    vi.mocked(promotionApi.getPromotionConfig).mockResolvedValue({
      defaultRate: "0.1550",
      platforms: [],
      inviteCode: "ADA",
      sheetUrl: "https://docs.google.com/spreadsheets/d/abc",
      jumpTemplateId: null,
      remark: null
    });
    vi.mocked(promotionApi.listPromotionRecords).mockResolvedValue({
      items: [],
      total: "0",
      page: 1,
      size: 10
    });
    const promoting = await mountPage("1", [
      { ...row, reviewStatus: "APPROVED", displayRelationStatus: "PROMOTING" }
    ]);
    const relationTag = promoting
      .findAll('[data-test="relation-status"]')
      .filter(tag => tag.text() === "推广中")
      .at(-1)!;
    await new DOMWrapper(relationTag.element.parentElement!).trigger("click");
    await flushPromises();
    const config = promoting.findComponent({ name: "PromotionConfigDrawer" });
    expect(config.props("modelValue")).toBe(true);
    expect(config.props("readOnly")).toBe(true);
    expect(config.find('[data-test="config-save"]').exists()).toBe(false);
    promoting.unmount();
  });

  it("requires a reason up to 200 characters before manual cleanup", async () => {
    signIn("STAFF", "ALL", ["kol:view", "kol:edit"]);
    vi.mocked(promotionApi.cleanupKol).mockResolvedValue(null);
    const prompt = vi
      .spyOn(ElMessageBox, "prompt")
      .mockResolvedValue({ value: "客户要求移除" } as never);
    const wrapper = await mountPage();
    await wrapper.get('tbody [data-test="manual-cleanup"]').trigger("click");
    await flushPromises();
    const options = prompt.mock.calls[0][2] as { inputValidator: (value: string) => boolean };
    expect(prompt.mock.calls[0][0]).toContain("无法恢复");
    expect(options.inputValidator(" ")).toBe(false);
    expect(options.inputValidator("a".repeat(201))).toBe(false);
    expect(options.inputValidator("有效原因")).toBe(true);
    expect(promotionApi.cleanupKol).toHaveBeenCalledWith(row.id, "客户要求移除");
    prompt.mockRestore();
    wrapper.unmount();
  });

  it("offers the status change row action to editors only", async () => {
    signIn("STAFF", "ALL", ["kol:view"]);
    const viewer = await mountPage();
    expect(viewer.find('[data-test="change-status"]').exists()).toBe(false);
    viewer.unmount();

    signIn("STAFF", "OWN", ["kol:view", "kol:edit"]);
    const wrapper = await mountPage();
    await wrapper.get('tbody [data-test="change-status"]').trigger("click");
    await flushPromises();
    const dialog = wrapper.findComponent({ name: "PromotionStatusDialog" });
    expect(dialog.props("modelValue")).toBe(true);
    expect(dialog.props("kol")?.id).toBe(row.id);
    wrapper.unmount();
  });

  it("opens the settings for the creator the status dialog was about", async () => {
    signIn("SUPER_ADMIN", "ALL");
    const bob = {
      ...row,
      id: "9007199254740994",
      kolCode: "KOL-QWE002",
      displayRelationStatus: "PENDING_PROMOTION" as const
    };
    const wrapper = await mountPage("2", [row, bob]);
    // The 422 shortcut must target the creator of THIS dialog, not a previously opened one.
    const rows = wrapper.findAll("tbody tr");
    const bobRow = rows.find(item => item.text().includes("KOL-QWE002"));
    await bobRow!.get('[data-test="change-status"]').trigger("click");
    await flushPromises();
    const dialog = wrapper.findComponent({ name: "PromotionStatusDialog" });
    expect(dialog.props("kol")?.id).toBe(bob.id);
    dialog.vm.$emit("openConfig");
    await nextTick();
    const config = wrapper.findComponent({ name: "PromotionConfigDrawer" });
    expect(config.props("modelValue")).toBe(true);
    expect(config.props("kol")?.id).toBe(bob.id);
    wrapper.unmount();
  });

  it("shows claim only to an available BD and cleanup only to ALL-scope editors", async () => {
    signIn("SUPER_ADMIN", "ALL");
    const superAdmin = await mountPage();
    expect(superAdmin.find('[data-test="claim-kol"]').exists()).toBe(false);
    expect(superAdmin.find('[data-test="cleanup-config"]').exists()).toBe(true);
    expect(
      superAdmin.findAll(".el-tabs__item").some(item => item.text().includes("清理记录"))
    ).toBe(true);
    superAdmin.unmount();

    signIn("STAFF", "ALL", ["kol:view", "kol:edit"]);
    const operator = await mountPage();
    expect(operator.find('[data-test="claim-kol"]').exists()).toBe(false);
    expect(operator.find('[data-test="cleanup-config"]').exists()).toBe(true);
    expect(operator.findAll(".el-tabs__item").some(item => item.text().includes("清理记录"))).toBe(
      true
    );
    operator.unmount();

    signIn("STAFF", "OWN", ["kol:view", "kol:edit"]);
    const bd = await mountPage();
    expect(bd.find('[data-test="claim-kol"]').exists()).toBe(true);
    expect(bd.find('[data-test="cleanup-config"]').exists()).toBe(false);
    expect(bd.findAll(".el-tabs__item").some(item => item.text().includes("清理记录"))).toBe(true);
    bd.unmount();

    signIn("STAFF", "ALL", ["kol:view"]);
    const viewer = await mountPage();
    expect(viewer.find('[data-test="claim-kol"]').exists()).toBe(false);
    expect(viewer.find('[data-test="cleanup-config"]').exists()).toBe(false);
    expect(viewer.findAll(".el-tabs__item").some(item => item.text().includes("清理记录"))).toBe(
      true
    );
    viewer.unmount();
  });
});
