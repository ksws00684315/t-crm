import { flushPromises, mount, type VueWrapper } from "@vue/test-utils";
import ElementPlus, { ElMessage, ElMessageBox } from "element-plus";
import { createPinia, setActivePinia } from "pinia";
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from "vitest";
import * as api from "@/api/commission/payout-account";
import type { PayoutAccountDetailVO } from "@/api/commission/payout-account";
import { i18n } from "@/locales";
import { ApiError } from "@/utils/http/error";
import ReviewDrawer from "../ReviewDrawer.vue";

vi.mock("@/api/commission/payout-account", () => ({
  listPayoutAccounts: vi.fn(),
  fetchPayoutPendingCount: vi.fn(),
  getPayoutAccount: vi.fn(),
  reviewPayoutAccount: vi.fn(),
  downloadPayoutQr: vi.fn()
}));

const wrappers: VueWrapper[] = [];

beforeAll(() => {
  // jsdom has no blob URL support; plain functions so resetAllMocks cannot wipe them.
  Object.defineProperty(URL, "createObjectURL", {
    configurable: true,
    value: () => "blob:qr-image"
  });
  Object.defineProperty(URL, "revokeObjectURL", { configurable: true, value: () => undefined });
});

function detail(overrides: Partial<PayoutAccountDetailVO> = {}): PayoutAccountDetailVO {
  return {
    id: "9007199254740993",
    kolId: "31",
    kolCode: "KOL0001",
    kolName: "Alice",
    accountType: "USDT",
    account: "TQ5xK9aZPn7vQm4Rt",
    accountMasked: "TQ5x…m4Rt",
    holderName: null,
    currency: null,
    network: "TRC20",
    qrFileId: "66",
    status: "PENDING",
    defaultAccount: false,
    rejectReason: null,
    lastSubmittedAt: "2026-10-16T08:00:00Z",
    reviewedTime: null,
    ...overrides
  };
}

async function openDrawer(current: PayoutAccountDetailVO) {
  vi.mocked(api.getPayoutAccount).mockResolvedValue(current);
  vi.mocked(api.downloadPayoutQr).mockResolvedValue(new Blob([new Uint8Array([1])]));
  const pinia = createPinia();
  setActivePinia(pinia);
  const wrapper = mount(ReviewDrawer, {
    props: { modelValue: true, accountId: current.id },
    attachTo: document.body,
    global: { plugins: [ElementPlus, i18n, pinia] }
  });
  wrappers.push(wrapper);
  await flushPromises();
  return wrapper;
}

async function clickApprove(): Promise<void> {
  (document.querySelector('[data-test="approve"]') as HTMLElement).click();
  await new Promise(resolve => setTimeout(resolve, 0));
}

function failure(errorCode: string, status: number) {
  return new ApiError({ errorCode, status, traceId: "test-trace" });
}

beforeEach(() => {
  document.body.innerHTML = "";
  vi.clearAllMocks();
});

afterEach(() => {
  wrappers.splice(0).forEach(wrapper => wrapper.unmount());
  vi.restoreAllMocks();
});

describe("payout review drawer", () => {
  it("shows the full account, the audit note and the QR image", async () => {
    await openDrawer(detail());
    // The drawer body teleports to document.body, so the assertions read the document.
    const text = document.body.textContent ?? "";
    expect(text).toContain("KOL0001");
    expect(text).toContain("TQ5xK9aZPn7vQm4Rt");
    expect(text).toContain("查看详情会记录操作日志。");
    expect(text).toContain("待审核");
    expect(api.downloadPayoutQr).toHaveBeenCalledWith("66");
    expect(document.querySelector('[data-test="qr-image"]')).not.toBeNull();
  });

  it("approves with confirmation and the exact lastSubmittedAt", async () => {
    vi.spyOn(ElMessageBox, "confirm").mockResolvedValue({ action: "confirm" } as never);
    const success = vi.spyOn(ElMessage, "success").mockImplementation(() => ({}) as never);
    vi.mocked(api.reviewPayoutAccount).mockResolvedValue(null);
    const approved = detail({ status: "APPROVED" });
    vi.mocked(api.getPayoutAccount).mockResolvedValueOnce(detail()).mockResolvedValueOnce(approved);
    const wrapper = await openDrawer(detail());

    await clickApprove();
    await flushPromises();
    expect(api.reviewPayoutAccount).toHaveBeenCalledWith("9007199254740993", {
      action: "APPROVE",
      lastSubmittedAt: "2026-10-16T08:00:00Z"
    });
    expect(success).toHaveBeenCalledWith("已通过。");
    expect(wrapper.emitted("reviewed")).toHaveLength(1);
    // The drawer reloads: the decision buttons are gone for the approved account.
    expect(document.querySelector('[data-test="approve"]')).toBeNull();
  });

  it("requires a rejection reason before sending it", async () => {
    await openDrawer(detail());
    (document.querySelector('[data-test="reject"]') as HTMLElement).click();
    await flushPromises();

    const dialog = document.querySelector(".el-dialog") as HTMLElement;
    expect(dialog).not.toBeNull();
    (document.querySelector('[data-test="reject-submit"]') as HTMLElement).click();
    await flushPromises();
    expect(document.body.textContent).toContain("请填写驳回原因");
    expect(api.reviewPayoutAccount).not.toHaveBeenCalled();

    // Element Plus puts fall-through attributes on the native textarea itself.
    const reason = document.querySelector('[data-test="reject-reason"]') as HTMLTextAreaElement;
    reason.value = "Holder name does not match";
    reason.dispatchEvent(new Event("input", { bubbles: true }));
    await flushPromises();
    vi.mocked(api.reviewPayoutAccount).mockResolvedValue(null);
    vi.mocked(api.getPayoutAccount).mockResolvedValue(
      detail({ status: "REJECTED", rejectReason: "Holder name does not match" })
    );
    (document.querySelector('[data-test="reject-submit"]') as HTMLElement).click();
    await flushPromises();
    expect(api.reviewPayoutAccount).toHaveBeenCalledWith("9007199254740993", {
      action: "REJECT",
      reason: "Holder name does not match",
      lastSubmittedAt: "2026-10-16T08:00:00Z"
    });
  });

  it("reloads the detail and refreshes the list after a 409 conflict", async () => {
    const warning = vi.spyOn(ElMessage, "warning").mockImplementation(() => ({}) as never);
    vi.mocked(api.reviewPayoutAccount).mockRejectedValue(
      failure("PAYOUT_ACCOUNT_NOT_PENDING", 409)
    );
    vi.spyOn(ElMessageBox, "confirm").mockResolvedValue({ action: "confirm" } as never);
    vi.mocked(api.getPayoutAccount)
      .mockResolvedValueOnce(detail())
      .mockResolvedValueOnce(detail({ status: "APPROVED" }));
    const wrapper = await openDrawer(detail());

    await clickApprove();
    await flushPromises();
    expect(warning).toHaveBeenCalledWith("账户已被修改或已审核，请刷新后重试");
    // Initial load plus the automatic reload after the conflict.
    expect(api.getPayoutAccount).toHaveBeenCalledTimes(2);
    expect(wrapper.emitted("reviewed")).toHaveLength(1);
  });

  it("shows no decision buttons for an account that is not pending", async () => {
    await openDrawer(detail({ status: "REJECTED", rejectReason: "No" }));
    expect(document.querySelector('[data-test="approve"]')).toBeNull();
    expect(document.querySelector('[data-test="reject"]')).toBeNull();
    expect(document.body.textContent).toContain("No");
  });

  it("shows the PayPal currency in the review detail (task 25b §3.6)", async () => {
    const wrapper = await openDrawer(
      detail({ accountType: "PAYPAL", currency: "EUR", network: null })
    );
    expect(wrapper.find('[data-test="payout-currency"]').text()).toBe("EUR");
    wrapper.unmount();

    const usdt = await openDrawer(detail({ accountType: "USDT", currency: null }));
    expect(usdt.find('[data-test="payout-currency"]').exists()).toBe(false);
    usdt.unmount();
  });
});
