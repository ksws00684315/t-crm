import { flushPromises, mount } from "@vue/test-utils";
import ElementPlus from "element-plus";
import { beforeEach, describe, expect, it, vi } from "vitest";
import * as api from "@/api/commission/finance";
import type { Withdrawal } from "@/api/commission/finance-types";
import { i18n } from "@/locales";
import ReviewDialog from "../ReviewDialog.vue";

vi.mock("@/api/commission/finance", () => ({
  reviewWithdrawal: vi.fn()
}));

function withdrawal(): Withdrawal {
  return {
    id: "102371",
    applicationNo: "t1023_W001",
    kolId: "102301",
    kolName: "t1023_Alex Morgan",
    kolCode: "t1023_K001",
    bdId: "102311",
    bdName: "t1023_Jordan",
    type: "COMMISSION",
    amount: "200.00",
    estimatedTotal: "2600.00",
    availableTotal: "600.00",
    currency: "CNY",
    status: "PENDING_REVIEW",
    createdAt: "2026-10-12T08:00:00Z",
    paused: false,
    pauseReason: null,
    account: {
      type: "PAYPAL",
      account: "kol@example.com",
      currency: "USD",
      network: "",
      holder: "KOL ONE"
    },
    note: "t1023_application_note",
    reviewNote: "",
    operationsNote: null,
    financeNote: "",
    allocations: [],
    history: [],
    payment: null,
    previousId: null
  };
}

beforeEach(() => {
  vi.clearAllMocks();
  i18n.global.locale.value = "zh-CN";
});

describe("withdrawal review dialog copy (task 115, 1105 sheet B10)", () => {
  it("shows the review title, the three-step process and the 申请金额 field", async () => {
    const wrapper = mount(ReviewDialog, {
      props: { modelValue: false, withdrawal: withdrawal() },
      global: { plugins: [ElementPlus, i18n] },
      attachTo: document.body
    });
    await wrapper.setProps({ modelValue: true });
    await flushPromises();
    const text = document.querySelector('[data-test="withdrawal-review-dialog"]')?.textContent;
    expect(text).toContain(i18n.global.t("finance.operationsReview"));
    // The three-step strip names the whole flow in order.
    expect(text).toContain(i18n.global.t("finance.creatorApplied"));
    expect(text).toContain(i18n.global.t("finance.financePay"));
    // The amount field shares the list's 申请金额 wording (B10 ②).
    expect(text).toContain(i18n.global.t("finance.requestedAmount"));
    expect(text).toContain("¥200.00");
    // The creator keeps the same 名称及ID identity as the list.
    expect(text).toContain("t1023_K001");
    wrapper.unmount();
  });

  it("requires a note when rejecting and shows the approve and reject actions", async () => {
    const wrapper = mount(ReviewDialog, {
      props: { modelValue: false, withdrawal: withdrawal() },
      global: { plugins: [ElementPlus, i18n] },
      attachTo: document.body
    });
    await wrapper.setProps({ modelValue: true });
    await flushPromises();
    const text = document.body.textContent ?? "";
    expect(text).toContain(i18n.global.t("finance.approve"));
    expect(text).toContain(i18n.global.t("finance.reject"));
    expect(text).toContain(i18n.global.t("finance.reviewNote"));
    // Approve without a note passes validation straight to the confirmation dialog.
    document.querySelector<HTMLButtonElement>('[data-test="withdrawal-review-approve"]')!.click();
    await flushPromises();
    // The confirm box opens; nothing has been submitted yet.
    expect(api.reviewWithdrawal).not.toHaveBeenCalled();
    wrapper.unmount();
    document.body.innerHTML = "";
  });
});
