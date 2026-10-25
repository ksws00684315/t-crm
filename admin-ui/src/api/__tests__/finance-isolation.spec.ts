import { beforeEach, describe, expect, it, vi } from "vitest";
import type * as FinanceUtils from "@/utils/finance";
import { http } from "../http";
import * as api from "../commission/finance";
vi.mock("@/utils/finance", async importOriginal => ({
  ...(await importOriginal<typeof FinanceUtils>()),
  financePreview: true,
  financeApiReady: true
}));
vi.mock("../../../preview/commission/finance-preview-sync", () => ({
  loadPreviewState: vi.fn(async () => undefined),
  savePreviewState: vi.fn(async () => undefined),
  clearPreviewState: vi.fn(async () => undefined),
  withPreviewState: <T>(action: () => Promise<T>) => action()
}));
vi.mock("../http", () => ({ http: { request: vi.fn(), download: vi.fn() } }));
beforeEach(() => vi.clearAllMocks());
describe("isolated financial preview transport", () => {
  it("keeps preview saves, reviews, voucher uploads and payments off the real HTTP client", async () => {
    await api.listIncome({});
    await api.getIncome("102301", {});
    const config = await api.getConfig();
    await api.saveConfig({ ...config, minimum: "250.00" });
    await api.reviewWithdrawal("102371", "APPROVE", "t1023_approved");
    const voucher = await api.uploadWithdrawalVoucher(
      new File(["demo"], "t1023.png", { type: "image/png" })
    );
    await api.payWithdrawal(
      "102373",
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
