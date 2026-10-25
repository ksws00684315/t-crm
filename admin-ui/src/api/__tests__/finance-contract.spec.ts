import { beforeEach, describe, expect, it, vi } from "vitest";
import type * as FinanceUtils from "@/utils/finance";
import { http } from "../http";
import { listFinanceTasks } from "../commission/finance";

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
});
