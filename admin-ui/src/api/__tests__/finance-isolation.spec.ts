import type * as FinanceUtils from "@/utils/finance";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { http } from "../http";
import * as api from "../commission/finance";
vi.mock("../http", () => ({ http: { request: vi.fn(), download: vi.fn() } }));
vi.mock("@/utils/finance", async original => ({
  ...(await original<typeof FinanceUtils>()),
  financePreview: true
}));
beforeEach(() => vi.clearAllMocks());
describe("isolated financial preview transport", () => {
  it("keeps queries, simulated saves, reviews, voucher uploads and payments off the real HTTP client", async () => {
    await api.listIncome({});
    await api.getIncome("102301", {});
    const config = await api.getConfig();
    await api.saveConfig({ ...config, minimum: "250.00" });
    await api.reviewWithdrawal("102371", "APPROVE", "t1023_approved");
    const voucher = await api.uploadWithdrawalVoucher(
      new File(["demo"], "t1023.png", { type: "image/png" })
    );
    await api.payWithdrawal(
      "102372",
      {
        actualAmount: "28.57",
        actualCurrency: "USD",
        amountCny: "200.00",
        paidAt: "2026-10-23T01:00:00Z",
        method: "PAYPAL",
        financeNote: "",
        conversionNote: "t1023_manual",
        voucher: voucher.fileId
      },
      "isolation1"
    );
    expect(http.request).not.toHaveBeenCalled();
    expect(http.download).not.toHaveBeenCalled();
  });
});
