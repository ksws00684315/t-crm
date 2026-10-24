import { afterEach, describe, expect, it, vi } from "vitest";
import { getFundSummary } from "../creator";
import { http } from "../http";

vi.mock("../http", () => ({ http: { request: vi.fn() } }));
afterEach(() => vi.clearAllMocks());

describe("fund summary presentation contract", () => {
  it("uses received income and estimated commission as distinct authoritative totals", async () => {
    vi.mocked(http.request).mockResolvedValue({
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
      prepayOutstandingCny: "25.00"
    });
  });

  it("preserves a negative available balance and zero when no advance was received", async () => {
    vi.mocked(http.request).mockResolvedValue({
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
  });
});
