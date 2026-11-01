import { flushPromises, mount } from "@vue/test-utils";
import ElementPlus from "element-plus";
import { describe, expect, it } from "vitest";
import type { Withdrawal } from "@/api/commission/finance-types";
import { i18n } from "@/locales";
import PaymentDialog from "../PaymentDialog.vue";

const withdrawal = (paused: boolean) =>
  ({
    id: "12",
    applicationNo: "W20261101000001",
    kolId: "22",
    kolCode: "KOL-1",
    kolName: "Creator",
    type: "COMMISSION",
    amount: "300.00",
    currency: "CNY",
    status: "PENDING_PAYMENT",
    paused,
    pauseReason: paused ? "t90 blocked" : null,
    account: {
      id: "7",
      type: "PAYPAL",
      account: "a***@example.test",
      holder: "T***",
      currency: "USD",
      network: null
    },
    note: null,
    reviewNote: null,
    financeNote: null,
    allocations: [],
    history: [],
    payment: null,
    previousId: null
  }) as unknown as Withdrawal;

const mountDialog = async (paused: boolean) => {
  const wrapper = mount(PaymentDialog, {
    props: { modelValue: true, withdrawal: withdrawal(paused) },
    global: { plugins: [ElementPlus, i18n], stubs: { teleport: true } }
  });
  await flushPromises();
  return wrapper;
};

describe("payment dialog", () => {
  it("shows the refund-pause alert and disables the confirm button for a paused task", async () => {
    const wrapper = await mountDialog(true);
    expect(wrapper.find('[data-test="payment-paused"]').exists()).toBe(true);
    expect(wrapper.find('[data-test="pay-submit"]').attributes("disabled")).toBeDefined();
  });

  it("keeps the confirm enabled and hides the alert when the task is not paused", async () => {
    const wrapper = await mountDialog(false);
    expect(wrapper.find('[data-test="payment-paused"]').exists()).toBe(false);
    expect(wrapper.find('[data-test="pay-submit"]').attributes("disabled")).toBeUndefined();
  });
});
