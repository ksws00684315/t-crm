import { http } from "./http";

export interface PrepayResult {
  id: string;
  prepayNo: string;
  status: "PAID";
  payAmountCny: string;
  payCurrency: "CNY";
  payTime: string;
  payMethod: string;
  voucherFileId: string;
  deductFromCommission: boolean;
  deduction?: {
    postedAmountCny: string;
    recoveredAmountCny: string;
    remainingAmountCny: string;
  } | null;
  walletBalanceCny: string;
}
export interface PrepayEntry {
  entryId: string;
  prepayId: string;
  prepayNo: string;
  entryType: "PREPAY_PAID" | "PREPAY_DEDUCT";
  amountCny: string;
  occurredAt: string;
  balanceAfterCny?: string | null;
}
interface PrepayPage<T> {
  items: T[];
  total: string;
  page: number;
  size: number;
}
export const listPrepays = (page: number, size: number) =>
  http.request<PrepayPage<PrepayResult>>({
    url: "/prepays",
    method: "get",
    params: { page, size }
  });
export const getPrepay = (id: string) =>
  http.request<PrepayResult>({ url: `/prepays/${id}`, method: "get" });
export const prepayLedger = (page: number, size: number) =>
  http.request<PrepayPage<PrepayEntry>>({
    url: "/prepays/ledger",
    method: "get",
    params: { page, size }
  });
export const downloadPrepayVoucher = (id: string) =>
  http.download({ url: `/files/${id}`, method: "get" });
