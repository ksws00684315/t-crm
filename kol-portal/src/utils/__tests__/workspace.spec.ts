import { describe, expect, it } from "vitest";
import type { FundSummary } from "@/api/creator";
import { isNegativeAmount, rateLabel, workspaceFigures } from "../workspace";

function summary(overrides: Partial<FundSummary>): FundSummary {
  return {
    totalIncomeCny: "0.00",
    commissionIncomeCny: "0.00",
    estimatedCommissionCny: "0.00",
    totalCommissionCny: "0.00",
    withdrawnCny: "0.00",
    withdrawingCny: "0.00",
    frozenCny: "0.00",
    receivedPrepayCny: "0.00",
    availableCny: "0.00",
    prepayDeductionCny: "0.00",
    prepayOutstandingCny: "0.00",
    asOf: "2026-11-01T00:00:00Z",
    ...overrides
  };
}

/** Every case keeps the four commission cards equal to the estimated commission. */
function cardsEqualEstimate(figures: ReturnType<typeof workspaceFigures>) {
  expect(figures.cardsTotal).toBe(figures.estimatedCommission);
}

describe("workspace figures (task 92 section 2.1)", () => {
  it("has no advance: total income equals the estimated commission and shows 0.00 advances", () => {
    // estimated 1000 = settled 400 + frozen 300 + withdrawing 100 + withdrawn 200
    const figures = workspaceFigures(
      summary({
        estimatedCommissionCny: "1000.00",
        availableCny: "400.00",
        frozenCny: "300.00",
        withdrawingCny: "100.00",
        withdrawnCny: "200.00"
      })
    );
    expect(figures.commissionIncome).toBe("1000.00");
    expect(figures.prepayPaid).toBe("0.00");
    expect(figures.totalIncome).toBe("1000.00");
    expect(figures.available).toBe("400.00");
    expect(figures.settled).toBe("400.00");
    cardsEqualEstimate(figures);
  });

  it("keeps an advance that is not deducted in the total income only", () => {
    const figures = workspaceFigures(
      summary({
        estimatedCommissionCny: "1000.00",
        availableCny: "1000.00",
        receivedPrepayCny: "500.00"
      })
    );
    expect(figures.commissionIncome).toBe("1000.00");
    expect(figures.totalIncome).toBe("1500.00");
    cardsEqualEstimate(figures);
  });

  it("subtracts the repaid and the outstanding advance from the commission income", () => {
    // 1000 commission, 500 advanced: 200 already repaid, 300 still to repay.
    // Wallet released 1000 - 200 repaid = 800 available; settled = available + repaid = 1000.
    const figures = workspaceFigures(
      summary({
        estimatedCommissionCny: "1000.00",
        availableCny: "800.00",
        receivedPrepayCny: "500.00",
        prepayDeductionCny: "200.00",
        prepayOutstandingCny: "300.00"
      })
    );
    expect(figures.commissionIncome).toBe("500.00");
    expect(figures.totalIncome).toBe("1000.00");
    // The withdrawable amount is the settled commission minus the repaid advance, not minus the
    // outstanding part.
    expect(figures.settled).toBe("1000.00");
    expect(figures.available).toBe("800.00");
    cardsEqualEstimate(figures);
  });

  it("shows a negative commission income when the commission cannot cover the advance", () => {
    const figures = workspaceFigures(
      summary({
        estimatedCommissionCny: "100.00",
        availableCny: "-50.00",
        frozenCny: "0.00",
        receivedPrepayCny: "400.00",
        prepayDeductionCny: "150.00",
        prepayOutstandingCny: "250.00"
      })
    );
    expect(figures.commissionIncome).toBe("-300.00");
    expect(figures.totalIncome).toBe("100.00");
    expect(isNegativeAmount(figures.commissionIncome)).toBe(true);
    expect(figures.available).toBe("-50.00");
    expect(isNegativeAmount(figures.available)).toBe(true);
    cardsEqualEstimate(figures);
  });

  it("works in cents for long amounts and sub-unit negatives", () => {
    const figures = workspaceFigures(
      summary({
        estimatedCommissionCny: "12345678.90",
        availableCny: "12345678.90",
        receivedPrepayCny: "0.10"
      })
    );
    expect(figures.totalIncome).toBe("12345679.00");
    expect(isNegativeAmount("-0.50")).toBe(true);
    expect(isNegativeAmount("0.00")).toBe(false);
  });

  it("formats the profile rate and reports an unset one as null", () => {
    expect(rateLabel("12")).toBe("12%");
    expect(rateLabel("12.5")).toBe("12.5%");
    expect(rateLabel(null)).toBeNull();
    expect(rateLabel(undefined)).toBeNull();
    expect(rateLabel("")).toBeNull();
  });
});
