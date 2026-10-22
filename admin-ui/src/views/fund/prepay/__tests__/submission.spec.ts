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
  prepayAccounts: vi.fn(),
  getPrepay: vi.fn(),
  payPrepay: vi.fn(),
  prepayRecipient: vi.fn(),
  approvePrepay: vi.fn(),
  cancelPrepay: vi.fn(),
  downloadPrepayFile: vi.fn()
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
  vi.mocked(api.prepayAccounts).mockResolvedValue([
    { id: "2", type: "PAYPAL", maskedAccount: "t***", holderName: "Test" }
  ]);
  vi.spyOn(ElMessageBox, "confirm").mockResolvedValue({ action: "confirm" } as never);
});
afterEach(() => {
  wrappers.splice(0).forEach(w => w.unmount());
  document.body.innerHTML = "";
  vi.restoreAllMocks();
});
async function open(component: typeof ApplyDialog | typeof DetailDrawer) {
  const wrapper = mount(component, {
    props: { modelValue: false, kol: { id: "3", kolCode: "KOL-test", name: "Test" }, id: "9" },
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
  it("retains the application UUID and immutable payload after an ambiguous failure", async () => {
    vi.mocked(api.applyPrepay).mockRejectedValue(new Error("network"));
    const wrapper = await open(ApplyDialog);
    await set(wrapper, "prepay-amount", "100.00");
    await set(wrapper, "prepay-reason", "test");
    wrapper.getComponent({ name: "ElDatePicker" }).vm.$emit("update:modelValue", "2026-10-23");
    wrapper.getComponent({ name: "ElSelect" }).vm.$emit("update:modelValue", "2");
    await flushPromises();
    await wrapper.get('[data-test="prepay-submit"]').trigger("click");
    await flushPromises();
    await wrapper.get('[data-test="prepay-submit"]').trigger("click");
    await flushPromises();
    expect(api.applyPrepay).toHaveBeenCalledTimes(2);
    const calls = vi.mocked(api.applyPrepay).mock.calls;
    expect(calls[0][0].requestId).toMatch(/^[0-9a-f-]{36}$/);
    expect(calls[1][0]).toEqual(calls[0][0]);
    expect(wrapper.getComponent({ name: "ElForm" }).props("disabled")).toBe(true);
  });
  it("allows pay-only users, confirms a difference, refreshes conflict and retries the same UUID", async () => {
    vi.mocked(api.payPrepay)
      .mockRejectedValueOnce(
        new ApiError({ status: 409, errorCode: "PREPAY_STATUS_CONFLICT", traceId: "test" })
      )
      .mockResolvedValueOnce({ ...detail, status: "PAID" });
    const wrapper = await open(DetailDrawer);
    expect(wrapper.find('[data-test="prepay-pay-submit"]').exists()).toBe(true);
    expect(api.prepayRecipient).not.toHaveBeenCalled();
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
    vi.mocked(api.getPrepay).mockResolvedValue({ ...detail, status: "PENDING_APPROVAL" });
    vi.mocked(api.approvePrepay).mockRejectedValue(
      new ApiError({ status: 409, errorCode: "PREPAY_STATUS_CONFLICT", traceId: "test" })
    );
    const wrapper = await open(DetailDrawer);
    expect(wrapper.find('[data-test="prepay-pay-submit"]').exists()).toBe(false);
    await wrapper.get('[data-test="prepay-approve"]').trigger("click");
    await flushPromises();
    expect(api.approvePrepay).toHaveBeenCalledWith("9", "APPROVE", undefined);
    expect(api.getPrepay).toHaveBeenCalledTimes(2);
  });
  it("ignores a previous creator account request after switching creators", async () => {
    let finish!: (value: api.PrepayAccount[]) => void;
    vi.mocked(api.prepayAccounts)
      .mockImplementationOnce(
        () =>
          new Promise(resolve => {
            finish = resolve;
          })
      )
      .mockResolvedValueOnce([
        { id: "new", type: "PAYPAL", maskedAccount: "new***", holderName: "New" }
      ]);
    const wrapper = await open(ApplyDialog);
    await wrapper.setProps({ kol: { id: "4", kolCode: "KOL-new", name: "New" } });
    await flushPromises();
    finish([{ id: "old", type: "PAYPAL", maskedAccount: "old***", holderName: "Old" }]);
    await flushPromises();
    const options = wrapper.findAllComponents({ name: "ElOption" });
    expect(options.map(option => option.attributes("value"))).toEqual(["new"]);
  });
});
