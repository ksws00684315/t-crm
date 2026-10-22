import { DOMWrapper, flushPromises, mount } from "@vue/test-utils";
import ElementPlus, { ElMessage, ElMessageBox } from "element-plus";
import { createPinia, setActivePinia } from "pinia";
import QRCode from "qrcode";
import { beforeEach, describe, expect, it, vi } from "vitest";
import * as bdApi from "@/api/system/bd-user";
import * as bdTransferApi from "@/api/kol/bd-transfer";
import * as kolApi from "@/api/kol/kol";
import type { KolVO, Role } from "@/api/types";
import { i18n } from "@/locales";
import { useUserStore } from "@/stores/user";
import KolPage from "../index.vue";

vi.mock("@/api/kol/kol");
vi.mock("@/api/commission/prepay", () => ({ prepayAccounts: vi.fn().mockResolvedValue([]) }));
vi.mock("@/api/kol/promotion");
vi.mock("@/api/kol/bd-transfer");
vi.mock("@/api/link/link");
vi.mock("@/api/link/template");
vi.mock("@/api/system/bd-user");
// jsdom has no canvas; the dialog only needs the data URL the library returns.
vi.mock("qrcode", () => ({
  default: { toDataURL: vi.fn().mockResolvedValue("data:image/png;base64,UVI=") }
}));

/**
 * The creator list after task 32 §2.2: three query conditions, the 04.01 columns in order,
 * the row actions with their enable rules, and the super-administrator transfer tab.
 */
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
  updateTime: "2026-10-05T01:02:03Z",
  autoCleanupDays: 14
};

function signIn(role: Role, dataScope: "ALL" | "OWN", permissions: string[] = []) {
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
  vi.mocked(kolApi.getCleanupRemaining).mockResolvedValue({ [row.id]: 7 });
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

function element(testId: string): DOMWrapper<Element> {
  const matches = [...document.body.querySelectorAll(`[data-test="${testId}"]`)];
  // Prefer the real table row over Element Plus's hidden column-measuring copy.
  const el = matches.find(item => item.closest("tr") !== null) ?? matches[0];
  if (!el) throw new Error(`missing ${testId}`);
  return new DOMWrapper(el);
}

/** Element Plus renders a hidden column-measuring copy outside any row; keep real rows only. */
function rowButtons(id: string): HTMLButtonElement[] {
  return [...document.body.querySelectorAll<HTMLButtonElement>(`[data-test="${id}"]`)].filter(
    item => item.closest("tr") !== null
  );
}

function relationTags(): HTMLElement[] {
  return [...document.body.querySelectorAll<HTMLElement>('[data-test="relation-status"]')].filter(
    item => item.closest("tr") !== null
  );
}

const SUPER = ["kol:view", "kol:edit", "kol:register-link", "kol:copy-token"];
const BD = ["kol:view", "kol:edit", "kol:register-link"];

describe("creator list page (task 32 §2.2)", () => {
  beforeEach(() => {
    document.body.innerHTML = "";
    localStorage.removeItem("tcrm.admin.table.kol");
    localStorage.removeItem("tcrm.admin.locale");
    vi.mocked(bdTransferApi.createBdTransfer).mockReset();
    setActivePinia(createPinia());
  });

  it("offers exactly the three 03.02 query conditions", async () => {
    signIn("SUPER_ADMIN", "ALL", SUPER);
    const wrapper = await mountPage();
    const labels = wrapper.findAll(".list-search .el-form-item__label").map(item => item.text());
    expect(labels).toEqual(["达人ID", "达人名称", "邮箱"]);
    wrapper.unmount();
  });

  it("sends the three 03.02 conditions together as an AND query", async () => {
    signIn("SUPER_ADMIN", "ALL", SUPER);
    const wrapper = await mountPage();
    await element("filter-kol-id").setValue("KOL-ASD001");
    wrapper.getComponent({ name: "ListSearch" }).vm.$emit("search");
    await flushPromises();
    expect(kolApi.listKols).toHaveBeenLastCalledWith(
      expect.objectContaining({ kolCode: "KOL-ASD001" })
    );
    await element("filter-name").setValue("Alice");
    await element("filter-email").setValue("alice@example.com");
    wrapper.getComponent({ name: "ListSearch" }).vm.$emit("search");
    await flushPromises();
    // The backend combines the three parameters with AND (2026-10-20 review item 2).
    expect(kolApi.listKols).toHaveBeenLastCalledWith(
      expect.objectContaining({
        kolCode: "KOL-ASD001",
        name: "Alice",
        email: "alice@example.com"
      })
    );
    wrapper.unmount();
  });

  it("renders the 04.01 columns in order with the 05.01 placeholder", async () => {
    signIn("SUPER_ADMIN", "ALL", SUPER);
    const wrapper = await mountPage();
    const headers = wrapper
      .findAll(".el-table__header th .cell")
      .map(item => item.text())
      .filter(Boolean);
    expect(headers).toEqual([
      "达人ID",
      "达人名称",
      "Phone/WhatsApp",
      "邮箱",
      "社媒平台/ID",
      "佣金比例",
      "预付金额",
      "邀请时间",
      "邀请人",
      "审核状态",
      "登录状态",
      "关系状态",
      "操作"
    ]);
    expect(wrapper.text()).toContain("开发中");
    // The name is plain text, not a link any more.
    const name = element("kol-name");
    expect(name.find("a, button").exists()).toBe(false);
    // The grid retains widths that keep the labels readable; it scrolls inside the table.
    // Element Plus carries each configured width on the corresponding colgroup <col> element.
    const fixed = wrapper
      .findAll(".el-table__header colgroup col")
      .map(col => Number(col.element.getAttribute("width") ?? 0))
      .filter(w => w > 0);
    expect(fixed.length).toBe(13);
    expect(fixed.reduce((sum, width) => sum + width, 0)).toBeGreaterThan(1160);
    wrapper.unmount();
  });

  it("shows the phone, or WhatsApp without a phone, or the dash", async () => {
    signIn("SUPER_ADMIN", "ALL", SUPER);
    const wrapper = await mountPage("3", [
      row,
      { ...row, id: "2", phone: null, whatsapp: "+8613900000000" },
      { ...row, id: "3", phone: null, whatsapp: null }
    ]);
    const contacts = new Set(
      [...document.body.querySelectorAll("tr td")]
        .map(td => td.textContent ?? "")
        .filter(text => ["+8613800000000", "+8613900000000", "-"].includes(text))
    );
    // Phone first, WhatsApp only without a phone, the dash when neither.
    expect(contacts.size).toBe(3);
    expect(contacts.has("+8613800000000")).toBe(true);
    expect(contacts.has("+8613900000000")).toBe(true);
    expect(contacts.has("-")).toBe(true);
    wrapper.unmount();
  });

  it("moves review into its status and opens authorized prepay applications", async () => {
    signIn("SUPER_ADMIN", "ALL", SUPER);
    const wrapper = await mountPage("3", [
      { ...row, reviewStatus: "PENDING", id: "1" },
      { ...row, id: "2" },
      { ...row, reviewStatus: "APPROVED", id: "3", bdUserId: "1", bdUserName: "Me" }
    ]);
    expect(rowButtons("action-review")).toHaveLength(0);
    expect(document.body.querySelectorAll('[data-test="review-entry"]')).toHaveLength(1);
    // Commission only for an approved creator.
    expect(rowButtons("action-commission").map(item => item.disabled)).toEqual([
      true,
      false,
      false
    ]);
    const prepay = ["1", "2", "3"].map(id => rowButtons(`action-prepay-${id}`)[0]);
    expect(prepay.every(item => !item?.classList.contains("is-disabled"))).toBe(true);
    await element("action-prepay-2").trigger("click");
    await flushPromises();
    expect(document.querySelector('[data-test="prepay-apply"]')).not.toBeNull();
    expect(wrapper.getComponent({ name: "ApplyDialog" }).props("kol")).toMatchObject({ id: "2" });
    wrapper.unmount();
  });

  it("keeps relation status read-only for every row", async () => {
    signIn("SUPER_ADMIN", "ALL", SUPER);
    const wrapper = await mountPage("3", [
      { ...row, id: "1", displayRelationStatus: "PENDING_PROMOTION", reviewStatus: "APPROVED" },
      { ...row, id: "2", displayRelationStatus: "PROMOTING", relationStatus: "PROMOTING" },
      { ...row, id: "3", displayRelationStatus: "PENDING_UPDATE" }
    ]);
    expect(relationTags()).toHaveLength(3);
    expect(document.body.querySelectorAll(".relation--clickable")).toHaveLength(0);
    wrapper.unmount();
  });

  it("disables everything but profile edit and review for an unbound creator a BD found", async () => {
    signIn("STAFF", "OWN", BD);
    const wrapper = await mountPage("2", [
      { ...row, id: "1", bdUserId: "1", bdUserName: "Me" },
      { ...row, id: "2", bdUserId: null, bdUserName: null }
    ]);
    const disabled = (id: string) => rowButtons(id).map(item => item.disabled);
    const menuDisabled = (id: string, rowId: string) =>
      document.body
        .querySelector(`[data-test="${id}-${rowId}"]`)
        ?.classList.contains("is-disabled");
    expect(disabled("action-edit")).toEqual([false, false]);
    expect(disabled("action-review")).toEqual([]);
    expect(disabled("action-commission")).toEqual([false, true]);
    expect(menuDisabled("action-cleanup", "1")).toBe(false);
    expect(menuDisabled("action-cleanup", "2")).toBe(true);
    expect(menuDisabled("action-freeze", "1")).toBe(false);
    expect(menuDisabled("action-freeze", "2")).toBe(true);
    wrapper.unmount();
  });

  it("shows the invite button to the super administrator and a qualifying BD only", async () => {
    signIn("SUPER_ADMIN", "ALL", SUPER);
    const superWrapper = await mountPage();
    expect(superWrapper.find('[data-test="invite-kol"]').exists()).toBe(true);
    superWrapper.unmount();

    signIn("STAFF", "OWN", ["kol:view", "kol:edit"]);
    const bdWrapper = await mountPage();
    expect(bdWrapper.find('[data-test="invite-kol"]').exists()).toBe(false);
    bdWrapper.unmount();
  });

  it("opens the invite dialog with the registration QR code and the super-administrator hint", async () => {
    signIn("SUPER_ADMIN", "ALL", SUPER);
    vi.mocked(kolApi.getRegisterCode).mockResolvedValue({
      code: "ABCD1234",
      url: "https://kol.example/register?code=ABCD1234"
    });
    const wrapper = await mountPage();
    await element("invite-kol").trigger("click");
    await flushPromises();
    expect(document.body.textContent).toContain("商务专属邀请码");
    expect(document.body.textContent).toContain("ABCD1234");
    expect(element("register-qr-image").attributes("src")).toBe("data:image/png;base64,UVI=");
    expect(QRCode.toDataURL).toHaveBeenCalledWith(
      expect.stringContaining("ABCD1234"),
      expect.anything()
    );
    // Super administrator sees only the "not bound to a BD" note (review item 3)…
    expect(element("super-invite-hint").text()).toContain("不绑定商务");
    expect(document.body.textContent).not.toContain("自动归属到你名下");
    wrapper.unmount();

    // …while a qualifying BD sees the auto-assign hint.
    signIn("STAFF", "OWN", BD);
    vi.mocked(kolApi.listKols).mockResolvedValue({ items: [], total: "0", page: 1, size: 20 });
    const bdWrapper = await mountPage();
    await element("invite-kol").trigger("click");
    await flushPromises();
    expect(document.body.textContent).toContain("自动归属到你名下");
    expect(document.body.querySelector('[data-test="super-invite-hint"]')).toBeNull();
    bdWrapper.unmount();
  });

  it("keeps the manage tab for the super administrator only", async () => {
    signIn("SUPER_ADMIN", "ALL", SUPER);
    const superWrapper = await mountPage();
    expect(superWrapper.find("#tab-manage").text()).toContain("达人管理");
    superWrapper.unmount();

    signIn("STAFF", "OWN", BD);
    const bdWrapper = await mountPage();
    expect(bdWrapper.find("#tab-manage").exists()).toBe(false);
    bdWrapper.unmount();
  });

  it("submits a transfer of chosen creators from the manage tab", async () => {
    signIn("SUPER_ADMIN", "ALL", SUPER);
    vi.mocked(kolApi.listKols).mockResolvedValue({ items: [row], total: "1", page: 1, size: 20 });
    vi.mocked(bdTransferApi.createBdTransfer).mockResolvedValue({
      immediate: 1,
      scheduled: 0,
      skipped: 0
    });
    const success = vi.spyOn(ElMessage, "success").mockImplementation(() => ({}) as never);
    const wrapper = await mountPage();
    await wrapper.get("#tab-manage").trigger("click");
    await flushPromises();
    const manage = wrapper.getComponent({ name: "KolManageTab" });
    manage.vm.$emit("changed");
    await flushPromises();
    expect(document.body.textContent).toContain("转移记录请到 系统管理 → 操作日志 查询");
    expect(success).not.toHaveBeenCalledWith(expect.stringContaining("立即完成"));
    wrapper.unmount();
  });

  it("freezes through the account status endpoint after the confirm", async () => {
    signIn("SUPER_ADMIN", "ALL", SUPER);
    vi.mocked(kolApi.updateKolStatus).mockResolvedValue(null);
    vi.spyOn(ElMessageBox, "confirm").mockResolvedValue({ action: "confirm" } as never);
    const success = vi.spyOn(ElMessage, "success").mockImplementation(() => ({}) as never);
    const wrapper = await mountPage();
    await element(`action-freeze-${row.id}`).trigger("click");
    await flushPromises();
    expect(kolApi.updateKolStatus).toHaveBeenCalledWith(row.id, 0);
    expect(success).toHaveBeenCalledWith("达人已冻结");
    wrapper.unmount();
  });

  it("shows the review entry only while pending and opens the shared profile dialog", async () => {
    signIn("SUPER_ADMIN", "ALL", SUPER);
    const wrapper = await mountPage("1", [{ ...row, reviewStatus: "PENDING" }]);
    await element("review-entry").trigger("click");
    await flushPromises();
    expect(document.body.textContent).toContain("审核达人 KOL-ASD001");
    // Email and creator ID are read-only in the dialog.
    expect(element("profile-email").attributes("disabled")).toBeDefined();
    expect(element("profile-code").attributes("disabled")).toBeDefined();
    wrapper.unmount();
  });

  it("opens the commission dialog from the approved row", async () => {
    signIn("SUPER_ADMIN", "ALL", SUPER);
    const wrapper = await mountPage();
    await element("action-commission").trigger("click");
    await flushPromises();
    expect(document.body.textContent).toContain("佣金信息 - KOL-ASD001");
    wrapper.unmount();
  });

  it("opens the per-creator cleanup dialog from the row", async () => {
    signIn("SUPER_ADMIN", "ALL", SUPER);
    const wrapper = await mountPage();
    expect(element(`action-cleanup-${row.id}`).text()).toContain("剩余7天");
    await element(`action-cleanup-${row.id}`).trigger("click");
    await flushPromises();
    expect(document.body.textContent).toContain("清理配置");
    expect(document.body.textContent).toContain("立即清理");
    wrapper.unmount();
  });

  it("shows the settings label when automatic cleanup is off", async () => {
    signIn("SUPER_ADMIN", "ALL", SUPER);
    const wrapper = await mountPage("1", [{ ...row, autoCleanupDays: null }]);
    expect(element(`action-cleanup-${row.id}`).text()).toContain("清理配置");
    wrapper.unmount();
  });
});
