import type * as FinanceUtils from "@/utils/finance";
import { describe, expect, it, vi } from "vitest";
import { http } from "../http";
import * as api from "../commission/finance";
vi.mock("../http", () => ({ http: { request: vi.fn(), download: vi.fn() } }));
vi.mock("@/utils/finance", async original => ({
  ...(await original<typeof FinanceUtils>()),
  financePreview: true
}));
describe("creator preview transport isolation", () => {
  it("never sends creator applications, withdrawals or QR files to real APIs", async () => {
    await api.getMyIncome({});
    const accounts = await api.getMyAccounts();
    const row = await api.applyWithdrawal({
      amount: "200.00",
      account: accounts[0],
      note: "t1023_isolation",
      idempotencyKey: "t1023_iso"
    });
    await api.cancelWithdrawal(row.id);
    await api.uploadRecipientQr(new File(["demo"], "t1023.png", { type: "image/png" }));
    expect(http.request).not.toHaveBeenCalled();
    expect(http.download).not.toHaveBeenCalled();
  });
});
