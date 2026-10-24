import { afterEach, describe, expect, it, vi } from "vitest";
import { getFundSummary, getWithdrawalConditions, submitWithdrawal } from "../creator";
import { http } from "../http";

vi.mock("../http", () => ({ http: { request: vi.fn() } }));
afterEach(() => vi.clearAllMocks());

describe("fund summary presentation contract", () => {
  it("uses received income and estimated commission as distinct authoritative totals", async () => {
    vi.mocked(http.request).mockResolvedValue({
      asOf: "2026-10-24T00:00:00Z",
      income: {
        paid: "350.10",
        commissionPaid: "300.10",
        prepayPaid: "50.00",
        estimated: "900.30",
        cumulativePaid: "300.10",
        occupied: "200.10",
        frozen: "100.10",
        available: "250.00",
        deducted: "50.00",
        pendingDeduction: "25.00"
      }
    });
    const { fundSummary } = await getFundSummary();
    expect(http.request).toHaveBeenCalledWith({ method: "GET", url: "/commissions/overview" });
    expect(fundSummary).toMatchObject({
      totalIncomeCny: "350.10",
      commissionIncomeCny: "300.10",
      receivedPrepayCny: "50.00",
      estimatedCommissionCny: "900.30",
      totalCommissionCny: "600.30",
      withdrawingCny: "200.10",
      availableCny: "250.00",
      prepayDeductionCny: "50.00",
      prepayOutstandingCny: "25.00",
      asOf: "2026-10-24T00:00:00Z"
    });
  });

  it("preserves a negative available balance and zero when no advance was received", async () => {
    vi.mocked(http.request).mockResolvedValue({
      asOf: "2026-10-24T00:00:00Z",
      income: {
        paid: "0.00",
        commissionPaid: "0.00",
        prepayPaid: "0.00",
        estimated: "0.00",
        cumulativePaid: "0.00",
        occupied: "0.00",
        frozen: "0.00",
        available: "-125.25",
        deducted: "125.25",
        pendingDeduction: "125.25"
      }
    });
    const { fundSummary } = await getFundSummary();
    expect(fundSummary.availableCny).toBe("-125.25");
    expect(fundSummary.receivedPrepayCny).toBe("0.00");
    expect(fundSummary.totalIncomeCny).toBe("0.00");
    expect(fundSummary.asOf).toBe("2026-10-24T00:00:00Z");
  });

  it("uses all withdrawal-condition fields from the commission contract", async () => {
    const conditions = {
      currency: "CNY",
      minimum: "200.00",
      version: 3,
      available: "900.00",
      occupied: "100.00",
      frozen: "50.00",
      estimated: "1050.00",
      withdrawalFrozen: false,
      canApply: false,
      unverifiedCount: 2
    };
    vi.mocked(http.request).mockResolvedValue(conditions);

    await expect(getWithdrawalConditions()).resolves.toEqual(conditions);
    expect(http.request).toHaveBeenCalledWith({
      method: "GET",
      url: "/withdrawals/conditions"
    });
  });

  it("sends previousId when resubmitting a rejected withdrawal", async () => {
    const data = {
      amount: "250.00",
      payoutAccountId: "account-1",
      note: "Updated request",
      previousId: "withdrawal-previous",
      idempotencyKey: "request-key"
    };
    vi.mocked(http.request).mockResolvedValue({
      id: "withdrawal-new",
      applicationNo: "W20261024000001",
      amount: data.amount,
      currency: "CNY",
      status: "PENDING_REVIEW",
      createdAt: "2026-10-24T00:00:00Z",
      account: {
        id: data.payoutAccountId,
        type: "PAYPAL",
        account: "p***@mail.com",
        holder: "Creator",
        currency: "USD",
        network: null
      }
    });

    await submitWithdrawal(data);

    expect(http.request).toHaveBeenCalledWith({ method: "POST", url: "/withdrawals", data });
  });
});
