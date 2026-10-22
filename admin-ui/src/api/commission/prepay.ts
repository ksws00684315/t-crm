import { http } from "../http";
import type { PageResult } from "../types";

export type PrepayStatus = "PENDING_APPROVAL" | "APPROVED" | "PAID" | "REJECTED" | "CANCELLED";
export interface PrepayAccount {
  id: string;
  type: "PAYPAL" | "USDT";
  currency?: string | null;
  network?: string | null;
  maskedAccount: string;
  holderName: string | null;
}
export interface PrepayRow {
  id: string;
  prepayNo: string;
  financeTaskType: "PREPAY";
  financeTaskId: string;
  kolId: string;
  kolCode: string;
  kolName: string | null;
  applicantId: string;
  amountCny: string;
  currency: "CNY";
  status: PrepayStatus;
  createdAt: string;
}
export interface PrepayDetail extends PrepayRow {
  reason: string;
  expectedPayDate: string;
  deductFromCommission: boolean;
  payoutAccountSnapshot: Omit<PrepayAccount, "id">;
  attachments: { id: string; name: string; mimeType: string; size: string }[];
  approval?: {
    decision: "APPROVE" | "REJECT";
    operatorId: string;
    time: string;
    reason?: string;
  } | null;
  payment?: {
    payAmountCny: string;
    payCurrency: "CNY";
    payTime: string;
    payMethod: string;
    payRemark?: string;
    voucherFileId: string;
    operatorId: string;
  } | null;
  timeline: { action: string; operatorId: string; time: string; reason?: string }[];
  deduction?: {
    postedAmountCny: string;
    recoveredAmountCny: string;
    remainingAmountCny: string;
  } | null;
  walletBalanceCny: string;
}
export interface PrepayQuery {
  prepayNo?: string;
  kolCode?: string;
  status?: PrepayStatus;
  page: number;
  size: number;
}
export interface ApplyPrepay {
  requestId: string;
  kolId: string;
  amountCny: string;
  reason: string;
  expectedPayDate: string;
  payoutAccountId: string;
  deductFromCommission: boolean;
}
export interface PayPrepay {
  requestId: string;
  payAmountCny: string;
  payTime: string;
  payMethod: string;
  payRemark?: string;
}
const root = "/commission/prepays";
export const listPrepays = (params: PrepayQuery) =>
  http.request<PageResult<PrepayRow>>({ url: root, method: "get", params });
export const getPrepay = (id: string) =>
  http.request<PrepayDetail>({ url: `${root}/${id}`, method: "get" });
export const prepayAccounts = (kolId: string) =>
  http.request<PrepayAccount[]>({
    url: `${root}/payout-accounts`,
    method: "get",
    params: { kolId }
  });
/** Deliberately requested on demand only; never persisted in a store. */
export const prepayRecipient = (id: string) =>
  http.request<Omit<PrepayAccount, "id"> & { account: string }>({
    url: `${root}/${id}/recipient`,
    method: "get"
  });
export function applyPrepay(payload: ApplyPrepay, attachments: readonly File[]) {
  const data = new FormData();
  data.append("payload", new Blob([JSON.stringify(payload)], { type: "application/json" }));
  attachments.forEach(file => data.append("attachments", file));
  return http.request<PrepayDetail>({ url: root, method: "post", data });
}
export const cancelPrepay = (id: string, reason?: string) =>
  http.request<PrepayDetail>({ url: `${root}/${id}/cancel`, method: "put", data: { reason } });
export const approvePrepay = (id: string, decision: "APPROVE" | "REJECT", reason?: string) =>
  http.request<PrepayDetail>({
    url: `${root}/${id}/approval`,
    method: "put",
    data: { decision, reason }
  });
export function payPrepay(id: string, payload: PayPrepay, voucher: File) {
  const data = new FormData();
  data.append("payload", new Blob([JSON.stringify(payload)], { type: "application/json" }));
  data.append("voucher", voucher);
  return http.request<PrepayDetail>({ url: `${root}/${id}/paid`, method: "put", data });
}
export const exportPrepays = (params: Omit<PrepayQuery, "page" | "size">) =>
  http.download({ url: `${root}/export`, method: "get", params });
export const downloadPrepayFile = (id: string) =>
  http.download({ url: `/files/${id}`, method: "get" });
