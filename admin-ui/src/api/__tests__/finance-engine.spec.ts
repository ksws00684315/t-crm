import { describe, expect, it } from "vitest";
import { createFinanceDemo } from "../../../preview/commission/finance-engine";
import { account } from "../../../preview/commission/finance-demo";
import type { PaymentRegistration } from "../commission/finance-types";
import { cents, decimal, financeAbilities, previewSubject, sumMoney } from "@/utils/finance";

describe("commission finance amounts and query semantics", () => {
  it("uses exact decimal arithmetic, including negative balances and zero", () => {
    expect(sumMoney(["0.10", "0.20", "-0.05"])).toBe("0.25");
    expect(decimal(cents("-40.00"))).toBe("-40.00");
    expect(() => cents("1.001")).toThrow();
  });
  it("reconciles all current metrics independently from period payments", () => {
    const api = createFinanceDemo();
    const all = api.listIncome("SUPER_ADMIN", "data", {});
    const period = api.listIncome("SUPER_ADMIN", "data", {
      start: "2026-10-20T00:00:00Z",
      end: "2026-10-23T00:00:00Z"
    });
    for (const row of all.items) {
      // Alex:2600-400-600-700-300=600. Emma:360-100-0-300-0=-40.
      expect(cents(row.available)).toBe(
        cents(row.estimated) -
          cents(row.frozen) -
          cents(row.occupied) -
          cents(row.cumulativePaid) -
          cents(row.deducted)
      );
      expect(cents(row.paid)).toBe(cents(row.commissionPaid) + cents(row.prepayPaid));
      expect(period.items.find(item => item.id === row.id)?.available).toBe(row.available);
    }
    expect(period.summary.paid).toBe("1320.00");
    expect(period.summary.commissionPaid).toBe("700.00");
    expect(period.summary.prepayPaid).toBe("620.00");
  });
  it("deduplicates multi-creator orders and does not multiply units by payments", () => {
    const api = createFinanceDemo();
    const all = api.listIncome("SUPER_ADMIN", "data", {});
    expect(all.summary.orders).toBe(5);
    expect(all.summary.quantity).toBe(12);
    const selected = api.listIncome("SUPER_ADMIN", "data", { paymentType: "PREPAY" });
    expect(selected.summary.creators).toBe(2);
    expect(selected.summary.orders).toBe(4);
    expect(selected.summary.quantity).toBe(9);
    expect(selected.summary.commissionPaid).toBe("0.00");
  });
  it("reads each paid prepayment once and never treats non-deductible amounts as offsets", () => {
    const api = createFinanceDemo();
    const first = api.incomeDetail("SUPER_ADMIN", "data", "102301", {});
    for (let count = 0; count < 5; count++)
      expect(api.incomeDetail("SUPER_ADMIN", "data", "102301", {})).toEqual(first);
    expect(first.income.prepayPaid).toBe("620.00");
    expect(first.income.deducted).toBe("300.00");
    expect(first.income.pendingDeduction).toBe("200.00");
    expect(first.payments.find(row => row.amountCny === "120.00")).toMatchObject({
      deduct: false,
      deducted: "0.00",
      pending: "0.00"
    });
  });
  it("keeps period detail amounts on later list pages and handles offset instants correctly", () => {
    const api = createFinanceDemo();
    expect(api.incomeDetail("SUPER_ADMIN", "data", "102301", { page: 3 }).income.paid).toBe(
      "1320.00"
    );
    expect(
      api.listIncome("SUPER_ADMIN", "data", {
        start: "2026-10-21T17:00:01+08:00",
        end: "2026-10-23T00:00:00+08:00"
      }).summary.paid
    ).toBe("120.00");
  });
  it("keeps unknown bases and rates null while allowing an explicit zero rate", () => {
    const api = createFinanceDemo();
    const alex = api.incomeDetail("SUPER_ADMIN", "data", "102301", {});
    expect(alex.lines.find(row => row.base === null)?.commission).toBeNull();
    const sam = api.incomeDetail("SUPER_ADMIN", "data", "102303", {});
    expect(sam.lines.find(row => row.rate === "0.0000")?.commission).toBe("0.00");
    expect(sam.lines.filter(row => row.commission === null)).toHaveLength(2);
  });
});
describe("finance permissions and state transitions", () => {
  it("pages distinct orders, totals their allocations and uses only explicit order snapshots", () => {
    const api = createFinanceDemo();
    const saved = api.snapshot();
    const application = saved.withdrawals[0];
    application.allocations[0].remainingSnapshot = "3.00";
    application.allocations[1].remainingSnapshot = "4.00";
    application.allocations[0].orderRemainingSnapshot = "8.25";
    application.allocations[1].orderRemainingSnapshot = "8.25";
    application.allocations[2].orderRemainingSnapshot = undefined;
    api.hydrate(saved);
    const first = api.withdrawalOrders("SUPER_ADMIN", "data", application.id, {
      page: 1,
      size: 10
    });
    expect(first.total).toBe("11");
    expect(first.items).toHaveLength(10);
    expect(first.items[0].products).toHaveLength(2);
    expect(first.items[0].occupied).toBe("40.00");
    expect(first.items[0].remainingSnapshot).toBe("8.25");
    expect(first.items[1].remainingSnapshot).toBeNull();
    expect(
      api.withdrawalOrders("SUPER_ADMIN", "data", application.id, { page: 2, size: 10 }).items
    ).toHaveLength(1);
    expect(() => api.withdrawalOrders("PLATFORM_ADMIN", "data", application.id, {})).toThrow();
  });
  it("requires separate identities for operations and finance review", () => {
    const api = createFinanceDemo();
    const operations = { id: "t1023_operator_1", name: "t1023_Operations" };
    const finance = { id: "t1023_finance_2", name: "t1023_Finance" };
    expect(api.review("SUPER_ADMIN", "data", "102371", "APPROVE", "", operations).status).toBe(
      "PENDING_FINANCE_REVIEW"
    );
    expect(() => api.review("SUPER_ADMIN", "data", "102371", "APPROVE", "", operations)).toThrow();
    expect(api.review("SUPER_ADMIN", "data", "102371", "APPROVE", "", finance).status).toBe(
      "PENDING_PAYMENT"
    );
  });

  it("returns legacy pending-payment preview rows to operations review", () => {
    const source = createFinanceDemo();
    const saved = source.snapshot();
    const legacy = saved.withdrawals.find(row => row.id === "102373")!;
    legacy.history = [legacy.history[0]];
    const restored = createFinanceDemo();
    restored.hydrate(saved);
    expect(restored.withdrawal("SUPER_ADMIN", "data", "102373").status).toBe("PENDING_REVIEW");
    expect(restored.withdrawal("SUPER_ADMIN", "data", "102373").history.at(-1)?.action).toBe(
      "LEGACY_REVIEW_RESET"
    );
  });

  it("redacts the response of a successful business-development review", () => {
    const api = createFinanceDemo();
    const result = api.review("BD", "data", "102371", "APPROVE", "t1023_review");
    expect(result.account.account).toContain("***");
    expect(result.financeNote).toBe("");
  });

  it("denies platform access and limits business development to current owned creators", () => {
    const api = createFinanceDemo();
    expect(() => api.listIncome("PLATFORM_ADMIN", "data", {})).toThrow();
    expect(api.listIncome("BD", "data", {}).items.map(row => row.id)).toEqual(["102301", "102303"]);
    expect(() => api.incomeDetail("BD", "data", "102302", {})).toThrow();
    expect(() => api.incomeDetail("KOL", "data", "102302", {})).toThrow();
    expect(
      api.settlements("BD", "data", {}).items.find(row => row.orderNo === "t1023_WD002")?.kolNames
    ).toEqual(["t1023_Alex Morgan"]);
  });
  it("masks ordinary recipient queries and hides internal finance notes from creators", () => {
    const api = createFinanceDemo();
    expect(api.withdrawal("OPERATOR", "data", "102375").account.account).toContain("***");
    expect(api.withdrawal("KOL", "data", "102375").payment?.financeNote).toBe("");
    expect(api.withdrawal("SUPER_ADMIN", "data", "102375").account.account).toBe(account.account);
  });
  it("exposes review and payment abilities only to authorized company identities", () => {
    expect(financeAbilities(previewSubject("BD"))).toMatchObject({
      view: true,
      review: true,
      pay: false,
      own: true
    });
    expect(financeAbilities(previewSubject("OPERATOR"))).toMatchObject({
      view: true,
      review: false,
      pay: false,
      configure: false
    });
    expect(financeAbilities(previewSubject("SUPER_ADMIN"))).toMatchObject({
      pay: true,
      configure: true
    });
    expect(financeAbilities(previewSubject("PLATFORM_ADMIN")).view).toBe(false);
  });
  it("requires rejection reason, releases reservations once and preserves historical snapshots", () => {
    const api = createFinanceDemo();
    const before = api.withdrawal("SUPER_ADMIN", "data", "102371");
    expect(() => api.review("BD", "data", "102371", "REJECT", " ")).toThrow();
    api.review("BD", "data", "102371", "REJECT", "t1023_invalid_account");
    expect(api.incomeDetail("SUPER_ADMIN", "data", "102301", {}).income.available).toBe("800.00");
    expect(() => api.review("BD", "data", "102371", "REJECT", "again")).toThrow();
    expect(api.withdrawal("SUPER_ADMIN", "data", "102371").allocations).toEqual(before.allocations);
  });
  const payment: PaymentRegistration = {
    actualAmount: "28.57",
    actualCurrency: "USD",
    amountCny: "200.00",
    paidAt: "2026-10-23T10:00:00+08:00",
    method: "PAYPAL",
    financeNote: "t1023_note",
    conversionNote: "t1023_manual_200CNY_to_28.57USD",
    voucher: "t1023_voucher"
  };
  it("blocks refund-paused payments, currency mismatch and inconsistent CNY write-off", () => {
    const api = createFinanceDemo();
    expect(() => api.pay("SUPER_ADMIN", "data", "102374", payment, "k1")).toThrow();
    expect(() => api.pay("FINANCE", "data", "102373", payment, "k1")).toThrow();
    expect(() =>
      api.pay("SUPER_ADMIN", "data", "102373", { ...payment, amountCny: "199.99" }, "k1")
    ).toThrow();
    expect(() =>
      api.pay("SUPER_ADMIN", "data", "102373", { ...payment, actualCurrency: "USDT" }, "k1")
    ).toThrow();
    expect(() =>
      api.pay("SUPER_ADMIN", "data", "102373", { ...payment, conversionNote: "" }, "k1")
    ).toThrow();
  });
  it("registers a payment once, normalizes UTC and leaves available balance unchanged", () => {
    const api = createFinanceDemo();
    const first = api.pay("SUPER_ADMIN", "data", "102373", payment, "k1");
    expect(api.pay("SUPER_ADMIN", "data", "102373", payment, "k1")).toEqual(first);
    expect(first.payment?.paidAt).toBe("2026-10-23T02:00:00.000Z");
    const wallet = api.incomeDetail("SUPER_ADMIN", "data", "102301", {}).income;
    expect(wallet.available).toBe("600.00");
    expect(wallet.occupied).toBe("400.00");
    expect(wallet.cumulativePaid).toBe("900.00");
    expect(() =>
      api.pay("SUPER_ADMIN", "data", "102373", { ...payment, actualAmount: "30.00" }, "k1")
    ).toThrow();
    expect(() => api.pay("SUPER_ADMIN", "data", "102373", payment, "new")).toThrow();
  });
  it("validates the threshold and balance and snapshots a creator application idempotently", () => {
    const api = createFinanceDemo();
    const request = {
      amount: "200.00",
      account: { ...account },
      note: "t1023_note",
      idempotencyKey: "a1"
    };
    expect(() => api.apply("KOL", "data", { ...request, amount: "199.99" })).toThrow();
    expect(() => api.apply("KOL", "data", { ...request, amount: "600.01" })).toThrow();
    const row = api.apply("KOL", "data", request);
    request.account.holder = "t1023_changed";
    expect(row.account.holder).toBe("t1023_Alex");
    expect(() => api.apply("KOL", "data", request)).toThrow();
    expect(api.incomeDetail("KOL", "data", "102301", {}).income.available).toBe("400.00");
    api.cancel("KOL", "data", row.id);
    expect(() => api.cancel("KOL", "data", row.id)).toThrow();
    expect(api.incomeDetail("KOL", "data", "102301", {}).income.available).toBe("600.00");
  });
  it("retains the rejected application when a creator reapplies", () => {
    const api = createFinanceDemo();
    const original = api.withdrawal("KOL", "data", "102376");
    const row = api.apply("KOL", "data", {
      amount: "200.00",
      account: { ...account },
      note: "t1023_reapply",
      previousId: original.id,
      idempotencyKey: "r1"
    });
    expect(row.previousId).toBe(original.id);
    expect(api.withdrawal("KOL", "data", original.id)).toEqual(original);
  });
  it("saves versioned configuration without rewriting historical commission rules", () => {
    const api = createFinanceDemo();
    const before = api.incomeDetail("SUPER_ADMIN", "data", "102301", {}).lines;
    const config = api.getConfig("SUPER_ADMIN", "data");
    expect(config.minimum).toBe("200.00");
    expect(config.channels[0].days).toBe(7);
    expect(() => api.saveConfig("OPERATOR", "data", config)).toThrow();
    api.saveConfig("SUPER_ADMIN", "data", {
      ...config,
      minimum: "300.00",
      channels: [{ ...config.channels[0], days: 10 }]
    });
    expect(api.incomeDetail("SUPER_ADMIN", "data", "102301", {}).lines).toEqual(before);
    expect(() => api.saveConfig("SUPER_ADMIN", "data", config)).toThrow();
  });
  it("supports explicit empty and failure states without retaining stale records", () => {
    const api = createFinanceDemo();
    expect(api.listIncome("SUPER_ADMIN", "empty", {}).items).toEqual([]);
    expect(api.incomeDetail("KOL", "empty", "102301", {}).lines).toEqual([]);
    expect(() => api.listWithdrawals("SUPER_ADMIN", "error", {})).toThrow();
  });
});

describe("preview creator identity snapshot", () => {
  it("uses session display identity for search and submission history", () => {
    const api = createFinanceDemo();
    const identity = { name: "t1023_Commission Preview", kolCode: "KOL-LMD004" };
    const row = api.apply(
      "KOL",
      "data",
      {
        amount: "200.00",
        account,
        note: "t1023_identity",
        idempotencyKey: "identity"
      },
      identity
    );
    expect(row.kolName).toBe(identity.name);
    expect(row.kolCode).toBe(identity.kolCode);
    expect(row.history[0].actor).toBe(identity.name);
    expect(row.allocations[0].orderRemainingSnapshot).toBeNull();
    expect(api.withdrawalOrders("KOL", "data", row.id, {}).items[0].remainingSnapshot).toBeNull();
    expect(
      api.listWithdrawals("SUPER_ADMIN", "data", { kolId: identity.kolCode }).items
    ).toContainEqual(row);
    expect(
      api.listWithdrawals("SUPER_ADMIN", "data", { kolId: identity.name }).items
    ).not.toContainEqual(row);
    identity.name = "changed";
    expect(api.withdrawal("KOL", "data", row.id).kolName).toBe("t1023_Commission Preview");
  });
});
