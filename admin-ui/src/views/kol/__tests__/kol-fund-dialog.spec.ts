import { DOMWrapper, flushPromises, mount } from "@vue/test-utils";
import ElementPlus from "element-plus";
import { beforeEach, describe, expect, it, vi } from "vitest";
import * as payoutApi from "@/api/commission/payout-account";
import type { KolVO } from "@/api/types";
import DataTable from "@/components/DataTable.vue";
import { i18n } from "@/locales";
import KolFundDialog from "../KolFundDialog.vue";

vi.mock("@/api/commission/payout-account");

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
  defaultRate: null,
  status: 1,
  remark: null,
  whatsapp: null,
  phone: null,
  linkTemplateId: null,
  linkTemplateName: null,
  inviteCode: null,
  sheetUrl: null,
  registerSource: 2,
  reviewStatus: "APPROVED",
  relationStatus: "PENDING_PROMOTION",
  displayRelationStatus: "PENDING_PROMOTION",
  loginStatus: "NORMAL",
  platforms: [],
  jumpTemplateId: null,
  configVersion: null,
  inviteTime: "2026-04-01T00:00:00Z",
  createTime: "2026-04-01T00:00:00Z",
  updateTime: "2026-04-01T00:00:00Z",
  autoCleanupDays: 14
};

const paypal: payoutApi.PayoutAccountRowVO = {
  id: "101",
  kolId: kol.id,
  kolCode: kol.kolCode,
  kolName: kol.name,
  accountType: "PAYPAL",
  accountMasked: "a***@example.com",
  holderName: "Alice Holder",
  currency: "USD",
  network: null,
  qrFileId: null,
  status: "APPROVED",
  defaultAccount: true,
  rejectReason: null,
  lastSubmittedAt: "2026-10-20T08:00:00Z",
  reviewedTime: null
};

const usdt: payoutApi.PayoutAccountRowVO = {
  id: "102",
  kolId: kol.id,
  kolCode: kol.kolCode,
  kolName: kol.name,
  accountType: "USDT",
  accountMasked: "TQ9x...8f2k",
  holderName: null,
  currency: null,
  network: "TRC20",
  qrFileId: null,
  status: "DISABLED",
  defaultAccount: false,
  rejectReason: null,
  lastSubmittedAt: "2026-10-21T08:00:00Z",
  reviewedTime: null
};

function element(testId: string): DOMWrapper<Element> {
  const el = document.body.querySelector(`[data-test="${testId}"]`);
  if (!el) throw new Error(`missing ${testId}`);
  return new DOMWrapper(el);
}

/** Element Plus renders a hidden column-measuring copy outside any row; keep real rows only. */
function elements(testId: string): string[] {
  return [...document.body.querySelectorAll(`[data-test="${testId}"]`)]
    .filter(item => item.closest("tr") !== null)
    .map(item => item.textContent?.trim() ?? "");
}

async function openDialog(items: payoutApi.PayoutAccountRowVO[], total = items.length) {
  vi.mocked(payoutApi.listPayoutAccounts).mockResolvedValue({
    items,
    total: String(total),
    page: 1,
    size: 20
  });
  const wrapper = mount(KolFundDialog, {
    props: { modelValue: false, kol },
    global: { plugins: [ElementPlus, i18n] },
    attachTo: document.body
  });
  await wrapper.setProps({ modelValue: true });
  await flushPromises();
  return wrapper;
}

describe("creator fund dialog (task 82)", () => {
  beforeEach(() => {
    document.body.innerHTML = "";
    i18n.global.locale.value = "zh-CN";
    vi.mocked(payoutApi.listPayoutAccounts).mockReset();
    vi.mocked(payoutApi.getPayoutAccount).mockReset();
  });

  it("lists the creator's accounts with masked values, default and status", async () => {
    const wrapper = await openDialog([paypal, usdt]);
    expect(payoutApi.listPayoutAccounts).toHaveBeenCalledWith({
      kolId: kol.id,
      page: 1,
      size: 20
    });
    expect(elements("fund-account-masked")).toEqual(["a***@example.com", "TQ9x...8f2k"]);
    expect(elements("fund-status")).toEqual(["正常", "已停用"]);
    expect(document.body.querySelector('[data-test="fund-empty"]')).toBeNull();
    // The default account carries the 默认 tag; the other row shows a dash.
    const rows = [...document.body.querySelectorAll<HTMLElement>("tr")].filter(row =>
      row.querySelector('[data-test="fund-status"]')
    );
    expect(rows).toHaveLength(2);
    expect(rows[0]?.textContent).toContain("默认");
    expect(rows[1]?.textContent).not.toContain("默认");
    // The list shows the masked value only; the full account waits behind 查看完整.
    expect(document.body.textContent).not.toContain("alice@example.com");
    wrapper.unmount();
  });

  it("shows the empty state when the creator has no account", async () => {
    const wrapper = await openDialog([]);
    expect(element("fund-empty").text()).toContain("达人尚未添加收款账户");
    expect(document.body.querySelector('[data-test="fund-account-masked"]')).toBeNull();
    wrapper.unmount();
  });

  it("pages the creator's accounts instead of fetching one oversized page", async () => {
    const wrapper = await openDialog([paypal], 45);
    wrapper.findComponent(DataTable).vm.$emit("update:page", 3);
    await flushPromises();
    expect(payoutApi.listPayoutAccounts).toHaveBeenLastCalledWith({
      kolId: kol.id,
      page: 3,
      size: 20
    });
    wrapper.unmount();
  });

  it("shows a load error instead of the empty state when the request fails", async () => {
    vi.mocked(payoutApi.listPayoutAccounts).mockRejectedValue(new Error("network"));
    const wrapper = mount(KolFundDialog, {
      props: { modelValue: false, kol },
      global: { plugins: [ElementPlus, i18n] },
      attachTo: document.body
    });
    await wrapper.setProps({ modelValue: true });
    await flushPromises();
    expect(element("fund-load-failed").text()).toContain("收款账户加载失败");
    expect(document.body.querySelector('[data-test="fund-empty"]')).toBeNull();
    wrapper.unmount();
  });

  it("titles the dialog with the creator name and code", async () => {
    const wrapper = await openDialog([paypal]);
    const title = document.body.querySelector(".el-dialog__title")?.textContent ?? "";
    expect(title).toBe("资金信息 - Alice（KOL-ASD001）");
    wrapper.unmount();
  });

  it("opens the read-only detail drawer with no approve or reject controls", async () => {
    vi.mocked(payoutApi.getPayoutAccount).mockResolvedValue({
      ...paypal,
      account: "alice@example.com"
    });
    const wrapper = await openDialog([paypal]);
    await element("fund-view-101").trigger("click");
    await flushPromises();

    expect(payoutApi.getPayoutAccount).toHaveBeenCalledWith("101");
    const drawer = document.body.querySelector<HTMLElement>(".el-drawer");
    expect(drawer).not.toBeNull();
    expect(drawer?.textContent).toContain("收款账户详情");
    expect(drawer?.textContent).toContain("alice@example.com");
    const buttonTexts = [...(drawer?.querySelectorAll("button") ?? [])].map(
      item => item.textContent?.trim() ?? ""
    );
    expect(buttonTexts.join("|")).not.toMatch(/通过|驳回|Approve|Reject/);
    // The drawer is read-only: the review API is gone from the module entirely.
    expect(Object.keys(payoutApi)).not.toContain("reviewPayoutAccount");
    wrapper.unmount();
  });
});
