import { flushPromises, mount, type VueWrapper } from "@vue/test-utils";
import ElementPlus, { ElMessageBox } from "element-plus";
import { createPinia, setActivePinia } from "pinia";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import * as api from "@/api/commission/prepay";
import { i18n } from "@/locales";
import { useUserStore } from "@/stores/user";
import { ApiError } from "@/utils/http/error";
import ApplyDialog from "../ApplyDialog.vue";
import DetailDrawer from "../DetailDrawer.vue";
vi.mock("@/api/commission/prepay", () => ({
  applyPrepay: vi.fn(),
  getActivePrepay: vi.fn(),
  getPrepay: vi.fn(),
  payPrepay: vi.fn(),
  prepayRecipient: vi.fn(),
  approvePrepay: vi.fn(),
  cancelPrepay: vi.fn(),
  downloadPrepayFile: vi.fn(),
  savePrepayApplicationDetails: vi.fn()
}));
const detail: api.PrepayDetail = {
  id: "9",
  prepayNo: "PP-test",
  financeTaskType: "PREPAY",
  financeTaskId: "9",
  kolId: "3",
  kolCode: "KOL-test",
  kolName: "Test",
  applicantId: "7",
  applicantName: "Business operator",
  amountCny: "100.00",
  currency: "CNY",
  status: "APPROVED",
  createdAt: "2026-10-22T00:00:00Z",
  reason: "test",
  expectedPayDate: "2026-10-23",
  deductFromCommission: true,
  payoutAccountSnapshot: { type: "PAYPAL", maskedAccount: "t***", holderName: "Test" },
  attachments: [],
  timeline: [],
  walletBalanceCny: "0.00"
};
const wrappers: VueWrapper[] = [];
beforeEach(() => {
  vi.clearAllMocks();
  setActivePinia(createPinia());
  useUserStore().user = {
    id: "7",
    username: "test",
    nickname: "Test",
    email: null,
    role: "STAFF",
    postTemplate: "BD",
    permissions: ["prepay:apply", "prepay:pay"],
    dataScope: "OWN",
    tenantId: "1",
    tenantName: "Test",
    status: 1,
    lastLoginTime: null,
    createTime: detail.createdAt
  };
  vi.mocked(api.getPrepay).mockResolvedValue(detail);
  vi.mocked(api.getActivePrepay).mockResolvedValue(null);
  vi.mocked(api.prepayRecipient).mockResolvedValue({
    type: "MANUAL",
    account: "Bank\nTest recipient\n123456",
    maskedAccount: "***",
    holderName: null
  });
  vi.spyOn(ElMessageBox, "confirm").mockResolvedValue({ action: "confirm" } as never);
});
afterEach(() => {
  wrappers.splice(0).forEach(w => w.unmount());
  document.body.innerHTML = "";
  vi.useRealTimers();
  vi.restoreAllMocks();
});
async function open(component: typeof ApplyDialog | typeof DetailDrawer) {
  const wrapper = mount(component, {
    props: { modelValue: false, id: "9" },
    attachTo: document.body,
    global: {
      plugins: [ElementPlus, i18n],
      stubs: {
        teleport: true,
        ElSelect: {
          name: "ElSelect",
          props: ["modelValue"],
          emits: ["update:modelValue"],
          template: "<select><slot /></select>"
        },
        ElOption: true
      }
    }
  });
  wrappers.push(wrapper);
  await wrapper.setProps({ modelValue: true });
  await flushPromises();
  return wrapper;
}
/** The apply dialog picks its creator from the remote select (task 82); the stub emits the value. */
async function pickKol(wrapper: VueWrapper, kolId = "3") {
  wrapper.getComponent({ name: "ElSelect" }).vm.$emit("update:modelValue", kolId);
  await flushPromises();
}
async function set(wrapper: VueWrapper, name: string, value: string) {
  const input = wrapper.get(`[data-test="${name}"]`);
  const nested = input.find("input, textarea");
  await (nested.exists() ? nested : input).setValue(value);
}
async function voucher(wrapper: VueWrapper) {
  const input = wrapper.get('[data-test="prepay-voucher"]');
  Object.defineProperty(input.element, "files", {
    value: [new File(["proof"], "proof.pdf", { type: "application/pdf" })]
  });
  await input.trigger("change");
}
describe("prepay safe submission", () => {
  it("ignores recipient responses from a previously opened application", async () => {
    let resolveOld!: (value: Awaited<ReturnType<typeof api.prepayRecipient>>) => void;
    vi.mocked(api.prepayRecipient)
      .mockImplementationOnce(
        () =>
          new Promise(resolve => {
            resolveOld = resolve;
          })
      )
      .mockResolvedValueOnce({
        type: "MANUAL",
        account: "New recipient",
        maskedAccount: "***",
        holderName: null
      });
    vi.mocked(api.getPrepay).mockImplementation(async id => ({
      ...detail,
      id,
      amountCny: id === "10" ? "200.00" : "100.00"
    }));
    const wrapper = await open(DetailDrawer);
    await wrapper.setProps({ id: "10" });
    await flushPromises();
    resolveOld({
      type: "MANUAL",
      account: "Old recipient",
      maskedAccount: "***",
      holderName: null
    });
    await flushPromises();
    expect(wrapper.get('[data-test="prepay-recipient-value"]').text()).toBe("New recipient");
    expect((wrapper.get('[data-test="prepay-pay-amount"]').element as HTMLInputElement).value).toBe(
      "200.00"
    );
  });
  it.each([1000, 1001])("validates the finance reason boundary at %i characters", async length => {
    useUserStore().user!.permissions = ["prepay:apply"];
    vi.mocked(api.getPrepay).mockResolvedValue({
      ...detail,
      status: "WAITING_DETAILS",
      payoutAccountId: "2"
    });
    vi.mocked(api.savePrepayApplicationDetails).mockResolvedValue({
      ...detail,
      status: "WAITING_DETAILS"
    });
    const wrapper = await open(DetailDrawer);
    await set(wrapper, "finance-prepay-reason", "原".repeat(length));
    await wrapper.get('[data-test="finance-prepay-details-save"]').trigger("click");
    await flushPromises();
    expect(api.savePrepayApplicationDetails).toHaveBeenCalledTimes(length === 1000 ? 1 : 0);
    if (length > 1000)
      await vi.waitFor(() =>
        expect(wrapper.text()).toContain(i18n.global.t("prepay.tooLong", { max: 1000 }))
      );
  });
  it.each([
    [100, 1000, true],
    [101, 1000, false],
    [100, 1001, false]
  ])(
    "validates payment method %i and remark %i before submitting",
    async (methodLength, remarkLength, accepted) => {
      vi.mocked(api.payPrepay).mockResolvedValue({ ...detail, status: "PAID" });
      const wrapper = await open(DetailDrawer);
      await set(wrapper, "prepay-pay-amount", "100.00");
      await set(wrapper, "prepay-pay-method", "M".repeat(methodLength as number));
      await set(wrapper, "prepay-pay-remark", "注".repeat(remarkLength as number));
      wrapper
        .getComponent({ name: "ElDatePicker" })
        .vm.$emit("update:modelValue", "2026-10-22T10:00:00+08:00");
      await voucher(wrapper);
      await wrapper.get('[data-test="prepay-pay-submit"]').trigger("click");
      await flushPromises();
      expect(api.payPrepay).toHaveBeenCalledTimes(accepted ? 1 : 0);
      if (!accepted)
        await vi.waitFor(() =>
          expect(wrapper.text()).toContain(
            i18n.global.t("prepay.tooLong", { max: methodLength === 101 ? 100 : 1000 })
          )
        );
    }
  );
  it("retains the application UUID and immutable payload after an ambiguous failure", async () => {
    vi.mocked(api.applyPrepay).mockRejectedValue(new Error("network"));
    const wrapper = await open(ApplyDialog);
    await pickKol(wrapper);
    await set(wrapper, "prepay-amount", "100.00");
    await flushPromises();
    await wrapper.get('[data-test="prepay-submit"]').trigger("click");
    await flushPromises();
    await wrapper.get('[data-test="prepay-submit"]').trigger("click");
    await flushPromises();
    expect(api.applyPrepay).toHaveBeenCalledTimes(2);
    const calls = vi.mocked(api.applyPrepay).mock.calls;
    expect(calls[0][0].requestId).toMatch(/^[0-9a-f-]{36}$/);
    expect(calls[1][0]).toEqual(calls[0][0]);
    expect(wrapper.get('[data-test="prepay-amount"]').attributes("disabled")).toBeDefined();
  });
  it("allows pay-only users, confirms a difference, refreshes conflict and retries the same UUID", async () => {
    vi.mocked(api.payPrepay)
      .mockRejectedValueOnce(
        new ApiError({ status: 409, errorCode: "PREPAY_STATE_CONFLICT", traceId: "test" })
      )
      .mockResolvedValueOnce({ ...detail, status: "PAID" });
    const wrapper = await open(DetailDrawer);
    expect(wrapper.find('[data-test="prepay-pay-submit"]').exists()).toBe(true);
    expect(api.prepayRecipient).toHaveBeenCalledWith("9");
    await set(wrapper, "prepay-pay-amount", "90.00");
    await set(wrapper, "prepay-pay-method", "PayPal");
    await set(wrapper, "prepay-pay-remark", "difference explained");
    wrapper
      .getComponent({ name: "ElDatePicker" })
      .vm.$emit("update:modelValue", "2026-10-22T10:00:00+08:00");
    await voucher(wrapper);
    await flushPromises();
    await wrapper.get('[data-test="prepay-pay-submit"]').trigger("click");
    await flushPromises();
    expect(ElMessageBox.confirm).toHaveBeenCalledTimes(1);
    expect(api.getPrepay).toHaveBeenCalledTimes(2);
    await wrapper.get('[data-test="prepay-pay-submit"]').trigger("click");
    await flushPromises();
    expect(api.payPrepay).toHaveBeenCalledTimes(2);
    expect(vi.mocked(api.payPrepay).mock.calls[1][1]).toEqual(
      vi.mocked(api.payPrepay).mock.calls[0][1]
    );
    expect(ElMessageBox.confirm).toHaveBeenCalledTimes(1);
  });
  it("loads a detail opened directly from a deep link", async () => {
    const wrapper = mount(DetailDrawer, {
      props: { modelValue: true, id: "9" },
      global: { plugins: [ElementPlus, i18n], stubs: { teleport: true } }
    });
    wrappers.push(wrapper);
    await flushPromises();
    expect(api.getPrepay).toHaveBeenCalledWith("9");
  });
  it("requires an explanation before confirming a payment amount difference", async () => {
    const wrapper = await open(DetailDrawer);
    await set(wrapper, "prepay-pay-amount", "90.00");
    await set(wrapper, "prepay-pay-method", "PayPal");
    wrapper
      .getComponent({ name: "ElDatePicker" })
      .vm.$emit("update:modelValue", "2026-10-22T10:00:00+08:00");
    await voucher(wrapper);
    await flushPromises();
    await wrapper.get('[data-test="prepay-pay-submit"]').trigger("click");
    await flushPromises();
    expect(api.payPrepay).not.toHaveBeenCalled();
    expect(ElMessageBox.confirm).not.toHaveBeenCalled();
    await vi.waitFor(() =>
      expect(wrapper.get('[data-test="prepay-pay-remark-field"]').text()).toContain(
        i18n.global.t("prepay.differenceRemark")
      )
    );
  });
  it("shows review actions only to reviewers and refreshes after approval conflict", async () => {
    useUserStore().user!.permissions = ["prepay:review"];
    vi.mocked(api.getPrepay).mockResolvedValue({
      ...detail,
      applicantId: "8",
      status: "PENDING_OPERATIONS_REVIEW"
    });
    vi.mocked(api.approvePrepay).mockRejectedValue(
      new ApiError({ status: 409, errorCode: "PREPAY_STATE_CONFLICT", traceId: "test" })
    );
    const wrapper = await open(DetailDrawer);
    expect(wrapper.find('[data-test="prepay-pay-submit"]').exists()).toBe(false);
    await wrapper.get('[data-test="prepay-approve"]').trigger("click");
    await flushPromises();
    expect(api.approvePrepay).toHaveBeenCalledWith("9", "APPROVE", undefined);
    expect(api.getPrepay).toHaveBeenCalledTimes(2);
  });
  it.each([
    ["PENDING_OPERATIONS_REVIEW", "prepay:review", "7", false],
    ["PENDING_OPERATIONS_REVIEW", "prepay:review", "8", true],
    ["PENDING_OPERATIONS_REVIEW", "prepay:finance-review", "8", false],
    ["PENDING_FINANCE_REVIEW", "prepay:finance-review", "7", false],
    ["PENDING_FINANCE_REVIEW", "prepay:finance-review", "8", true],
    ["PENDING_FINANCE_REVIEW", "prepay:review", "8", false]
  ] as const)(
    "controls review buttons for %s with %s and applicant %s",
    async (status, permission, applicantId, allowed) => {
      useUserStore().user!.permissions = [permission];
      vi.mocked(api.getPrepay).mockResolvedValue({ ...detail, status, applicantId });
      const wrapper = await open(DetailDrawer);
      // A stale approval action or direct detail link must never expose self-review.
      await wrapper.setProps({ initialAction: "approve" });
      expect(wrapper.find('[data-test="prepay-approve"]').exists()).toBe(allowed);
      expect(wrapper.find('[data-test="prepay-reject"]').exists()).toBe(allowed);
      expect(api.approvePrepay).not.toHaveBeenCalled();
    }
  );
  it("starts without a creator and submits a minimal application for the picked creator", async () => {
    vi.mocked(api.applyPrepay).mockResolvedValue({ ...detail, status: "WAITING_DETAILS" });
    useUserStore().user!.nickname = "Business operator";
    const wrapper = await open(ApplyDialog);
    // No creator yet: submit is disabled and no application is looked up.
    expect(wrapper.get('[data-test="prepay-submit"]').attributes("disabled")).toBeDefined();
    expect(api.getActivePrepay).not.toHaveBeenCalled();
    expect(wrapper.get('[data-test="prepay-application-status"]').text()).toContain("待申请");
    expect(wrapper.text()).not.toContain("Business operator");
    for (const field of [
      "prepay-reason",
      "prepay-date",
      "prepay-account",
      "prepay-attachments",
      "prepay-deduct"
    ]) {
      expect(wrapper.find('[data-test="' + field + '"]').exists()).toBe(false);
    }
    await pickKol(wrapper, "3");
    expect(api.getActivePrepay).toHaveBeenCalledWith("3");
    await set(wrapper, "prepay-amount", "100.00");
    await wrapper.get('[data-test="prepay-submit"]').trigger("click");
    await flushPromises();
    expect(wrapper.props("modelValue")).toBe(true);
    expect(wrapper.get('[data-test="prepay-amount"]').attributes("disabled")).toBeDefined();
    expect(wrapper.text()).toContain("PP-test");
    expect(wrapper.text()).toContain(i18n.global.t("prepay.completeInFinance"));
    expect(api.applyPrepay).toHaveBeenCalledExactlyOnceWith({
      requestId: expect.stringMatching(/^[0-9a-f-]{36}$/),
      kolId: "3",
      amountCny: "100.00"
    });
  });
  it("lets the business switch creator after picking one with an open application", async () => {
    vi.mocked(api.getActivePrepay).mockResolvedValueOnce({
      ...detail,
      kolId: "3",
      status: "WAITING_DETAILS"
    });
    const wrapper = await open(ApplyDialog);
    await pickKol(wrapper, "3");
    expect(wrapper.get('[data-test="prepay-amount"]').attributes("disabled")).toBeDefined();
    expect(wrapper.text()).toContain("PP-test");

    await pickKol(wrapper, "4");
    expect(api.getActivePrepay).toHaveBeenLastCalledWith("4");
    expect(wrapper.get('[data-test="prepay-amount"]').attributes("disabled")).toBeUndefined();
    expect(wrapper.get('select[data-test="prepay-kol"]').attributes("disabled")).toBeUndefined();
    expect(wrapper.find('[data-test="prepay-application-status"]').text()).toContain("待申请");
  });
  it("keeps the typed amount when polling finds no open application", async () => {
    vi.useFakeTimers({ toFake: ["setInterval", "clearInterval"] });
    const wrapper = await open(ApplyDialog);
    await pickKol(wrapper, "3");
    await set(wrapper, "prepay-amount", "1.23");
    await vi.advanceTimersByTimeAsync(10_000);
    await flushPromises();
    expect(api.getActivePrepay).toHaveBeenCalledTimes(2);
    expect((wrapper.get('[data-test="prepay-amount"]').element as HTMLInputElement).value).toBe(
      "1.23"
    );
  });
  it("keeps the same request id for a retry after polling", async () => {
    vi.useFakeTimers({ toFake: ["setInterval", "clearInterval"] });
    vi.mocked(api.applyPrepay)
      .mockRejectedValueOnce(new Error("network"))
      .mockResolvedValueOnce({ ...detail, status: "WAITING_DETAILS" });
    const wrapper = await open(ApplyDialog);
    await pickKol(wrapper, "3");
    await set(wrapper, "prepay-amount", "100.00");
    await wrapper.get('[data-test="prepay-submit"]').trigger("click");
    await flushPromises();
    await vi.advanceTimersByTimeAsync(10_000);
    await flushPromises();
    await wrapper.get('[data-test="prepay-submit"]').trigger("click");
    await flushPromises();
    const calls = vi.mocked(api.applyPrepay).mock.calls;
    expect(calls).toHaveLength(2);
    expect(calls[1][0]).toEqual(calls[0][0]);
  });
  it("clears the previous creator's amount when switching to another creator", async () => {
    vi.mocked(api.getActivePrepay).mockResolvedValueOnce({
      ...detail,
      kolId: "3",
      amountCny: "100.00",
      status: "WAITING_DETAILS"
    });
    const wrapper = await open(ApplyDialog);
    await pickKol(wrapper, "3");
    expect((wrapper.get('[data-test="prepay-amount"]').element as HTMLInputElement).value).toBe(
      "100.00"
    );
    await pickKol(wrapper, "4");
    expect((wrapper.get('[data-test="prepay-amount"]').element as HTMLInputElement).value).toBe("");
    expect(wrapper.get('[data-test="prepay-amount"]').attributes("disabled")).toBeUndefined();
  });
  it("requires a positive CNY amount", async () => {
    const wrapper = await open(ApplyDialog);
    await pickKol(wrapper);
    for (const value of ["", "0", "-1", "1.001"]) {
      await set(wrapper, "prepay-amount", value);
      await wrapper.get('[data-test="prepay-submit"]').trigger("click");
      await flushPromises();
      expect(api.applyPrepay).not.toHaveBeenCalled();
    }
  });
  it("requires finance details and an explicit deduction choice before approval", async () => {
    useUserStore().user!.permissions = ["prepay:apply"];
    vi.mocked(api.getPrepay).mockResolvedValue({
      ...detail,
      status: "WAITING_DETAILS",
      deductFromCommission: null,
      payoutAccountSnapshot: null,
      reason: null,
      expectedPayDate: null
    });
    const wrapper = await open(DetailDrawer);
    expect(wrapper.find('[data-test="prepay-approve"]').exists()).toBe(false);
    const supplement = wrapper.get('[data-test="prepay-application-details"]');
    expect(supplement.find('[data-test="finance-prepay-deduct"]').exists()).toBe(true);
    await wrapper.get('[data-test="finance-prepay-details-save"]').trigger("click");
    await flushPromises();
    expect(api.savePrepayApplicationDetails).not.toHaveBeenCalled();
    expect(api.approvePrepay).not.toHaveBeenCalled();
  });
  it("saves the deduction and account from finance before allowing approval", async () => {
    useUserStore().user!.permissions = ["prepay:apply"];
    vi.mocked(api.getPrepay).mockResolvedValue({
      ...detail,
      status: "WAITING_DETAILS",
      deductFromCommission: null,
      payoutAccountSnapshot: null,
      reason: null,
      expectedPayDate: null
    });
    vi.mocked(api.savePrepayApplicationDetails).mockResolvedValue({
      ...detail,
      status: "PENDING_OPERATIONS_REVIEW",
      deductFromCommission: false,
      payoutAccountId: "2"
    });
    const wrapper = await open(DetailDrawer);
    await set(wrapper, "finance-prepay-reason", "finance details");
    wrapper.getComponent({ name: "ElDatePicker" }).vm.$emit("update:modelValue", "2026-10-23");
    await set(wrapper, "finance-prepay-recipient", "Bank\nTest recipient\n123456");
    wrapper.getComponent({ name: "ElRadioGroup" }).vm.$emit("update:modelValue", false);
    await flushPromises();
    await wrapper.get('[data-test="finance-prepay-details-save"]').trigger("click");
    await flushPromises();
    expect(api.savePrepayApplicationDetails).toHaveBeenCalledExactlyOnceWith(
      "9",
      {
        reason: "finance details",
        expectedPayDate: "2026-10-23",
        recipientText: "Bank\nTest recipient\n123456",
        deductFromCommission: false
      },
      []
    );
    expect(wrapper.find('[data-test="prepay-approve"]').exists()).toBe(false);
    expect(wrapper.props("modelValue")).toBe(true);
    expect(wrapper.find('[data-test="finance-prepay-recipient"]').exists()).toBe(false);
    expect(wrapper.get('[data-test="prepay-recipient-value"]').text()).toBe(
      "Bank\nTest recipient\n123456"
    );
    expect(wrapper.find('[data-test="finance-prepay-details-save"]').exists()).toBe(false);
    expect(wrapper.findAll('[data-test="prepay-application-details"]')).toHaveLength(1);
  });
  it.each([0, 1000, 1001])(
    "validates manual recipient boundary %i before submission",
    async length => {
      useUserStore().user!.permissions = ["prepay:apply"];
      vi.mocked(api.getPrepay).mockResolvedValue({
        ...detail,
        status: "WAITING_DETAILS",
        payoutAccountSnapshot: null
      });
      vi.mocked(api.savePrepayApplicationDetails).mockResolvedValue({
        ...detail,
        status: "PENDING_OPERATIONS_REVIEW"
      });
      const wrapper = await open(DetailDrawer);
      await set(wrapper, "finance-prepay-recipient", "收".repeat(length));
      await wrapper.get('[data-test="finance-prepay-details-save"]').trigger("click");
      await flushPromises();
      expect(api.savePrepayApplicationDetails).toHaveBeenCalledTimes(length === 1000 ? 1 : 0);
    }
  );
  it("uses one activity tab and names with handling roles without internal ID fallback", async () => {
    vi.mocked(api.getPrepay).mockResolvedValue({
      ...detail,
      applicantName: null,
      applicantId: "2111789912836734977",
      financeTaskId: "2111789912836734978",
      timeline: [
        {
          action: "OPERATIONS_APPROVED",
          operatorId: "2111789912836734979",
          operatorName: "张三",
          operatorRole: "OPERATIONS_REVIEW",
          time: detail.createdAt
        },
        { action: "REJECTED", operatorId: "2111789912836734980", time: detail.createdAt },
        { action: "REJECTED", operatorId: "0", operatorRole: "SYSTEM", time: detail.createdAt }
      ]
    });
    const wrapper = await open(DetailDrawer);
    expect(wrapper.getComponent({ name: "ElDialog" }).props("alignCenter")).toBe(true);
    expect(wrapper.findComponent({ name: "ElDrawer" }).exists()).toBe(false);
    expect(wrapper.text()).toContain("张三 · 运营审核");
    expect(wrapper.text()).toContain("历史人员");
    expect(wrapper.text()).toContain("系统");
    expect(wrapper.text()).not.toMatch(/21117899128367349/);
    expect(wrapper.text()).not.toContain("操作轨迹");
    expect(wrapper.text()).not.toContain("审核记录");
  });
});
