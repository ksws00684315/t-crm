import { beforeEach, describe, expect, it, vi } from "vitest";
import type * as FinanceUtils from "@/utils/finance";
import { http } from "../http";
import { exportWithdrawals, listFinanceTasks } from "../commission/finance";

vi.mock("../http", () => ({ http: { request: vi.fn(), download: vi.fn() } }));
vi.mock("@/utils/finance", async importOriginal => {
  const actual = await importOriginal<typeof FinanceUtils>();
  return { ...actual, financePreview: false, financeApiReady: true };
});

beforeEach(() => vi.clearAllMocks());

describe("finance task API contract", () => {
  it("loads the unified commission and prepayment task endpoint", async () => {
    vi.mocked(http.request).mockResolvedValue({ items: [], total: "0", page: 1, size: 20 });
    const query = { applicationNo: "t1023_W1024", kolId: "t1023_K001", page: 1, size: 20 };

    await listFinanceTasks(query);

    expect(http.request).toHaveBeenCalledWith({
      url: "/commission/finance-tasks",
      method: "get",
      params: query
    });
  });

  it("keeps the creator filter on the withdrawal export (#132)", async () => {
    vi.mocked(http.download).mockResolvedValue({
      blob: new Blob([""], { type: "text/csv" }),
      fileName: "withdrawals.csv"
    });

    await exportWithdrawals({
      applicationNo: "W20261027001",
      kolId: " 9007199254740993 ",
      status: "PENDING_REVIEW",
      type: "COMMISSION",
      page: 2,
      size: 20
    });

    // kolId keeps its backend name (it was renamed to kol and silently dropped) and the
    // surrounding whitespace is trimmed; list-only fields stay off the export request.
    expect(http.download).toHaveBeenCalledWith({
      url: "/commission/withdrawals/export",
      method: "get",
      params: {
        applicationNo: "W20261027001",
        kolId: "9007199254740993",
        status: "PENDING_REVIEW"
      }
    });
  });
});
