import { describe, expect, it, vi } from "vitest";
import { http } from "../http";
import { downloadPrepayVoucher, getPrepay, listPrepays, prepayLedger } from "../prepay";
vi.mock("../http", () => ({ http: { request: vi.fn(), download: vi.fn() } }));
describe("creator prepay read-only API", () => {
  it("requests own result and ledger pages without accepting a creator selector", () => {
    listPrepays(2, 20);
    expect(http.request).toHaveBeenLastCalledWith({
      url: "/prepays",
      method: "get",
      params: { page: 2, size: 20 }
    });
    prepayLedger(1, 20);
    expect(http.request).toHaveBeenLastCalledWith({
      url: "/prepays/ledger",
      method: "get",
      params: { page: 1, size: 20 }
    });
    getPrepay("9007199254740993");
    expect(http.request).toHaveBeenLastCalledWith({
      url: "/prepays/9007199254740993",
      method: "get"
    });
    downloadPrepayVoucher("9007199254740995");
    expect(http.download).toHaveBeenCalledWith({ url: "/files/9007199254740995", method: "get" });
  });
});
