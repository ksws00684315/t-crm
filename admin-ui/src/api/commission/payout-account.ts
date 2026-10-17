import { http } from "../http";
import type { PageResult } from "../types";

/** `AdminPayoutAccountVO` (task 15a §3.2): the list shows the masked account only. */
export interface PayoutAccountRowVO {
  id: string;
  kolId: string;
  kolCode: string;
  kolName: string | null;
  accountType: "PAYPAL" | "USDT";
  accountMasked: string;
  holderName: string | null;
  network: "TRC20" | "ERC20" | "BEP20" | "SOL" | "TON" | null;
  qrFileId: string | null;
  status: "PENDING" | "APPROVED" | "REJECTED" | "DISABLED";
  defaultAccount: boolean;
  rejectReason: string | null;
  lastSubmittedAt: string;
  reviewedTime: string | null;
}

/** Reviewer detail: the decrypted account replaces the masked value. */
export interface PayoutAccountDetailVO extends PayoutAccountRowVO {
  account: string;
}

export interface PayoutAccountQuery {
  /** `ALL` lists every status; the review queue `PENDING` is the default. */
  status?: "PENDING" | "APPROVED" | "REJECTED" | "DISABLED" | "ALL";
  type?: "PAYPAL" | "USDT";
  kolCode?: string;
  page: number;
  size: number;
}

export function listPayoutAccounts(
  query: PayoutAccountQuery
): Promise<PageResult<PayoutAccountRowVO>> {
  return http.request<PageResult<PayoutAccountRowVO>>({
    method: "get",
    url: "/commission/payout-accounts",
    params: {
      status: query.status,
      type: query.type,
      kolCode: query.kolCode || undefined,
      page: query.page,
      size: query.size
    }
  });
}

/** Accounts the caller may review; feeds the menu badge (0 renders no badge). */
export async function fetchPayoutPendingCount(): Promise<number> {
  const result = await http.request<{ count: string } | null>({
    method: "get",
    url: "/commission/payout-accounts/pending-count"
  });
  return Number(result?.count) || 0;
}

export function getPayoutAccount(id: string): Promise<PayoutAccountDetailVO> {
  return http.request<PayoutAccountDetailVO>({
    method: "get",
    url: `/commission/payout-accounts/${id}`
  });
}

export interface ReviewPayoutAccountPayload {
  action: "APPROVE" | "REJECT";
  reason?: string;
  /** Version the reviewer saw, sent back exactly as received; a mismatch is a 409. */
  lastSubmittedAt: string;
}

export function reviewPayoutAccount(
  id: string,
  payload: ReviewPayoutAccountPayload
): Promise<null> {
  return http.request<null>({
    method: "put",
    url: `/commission/payout-accounts/${id}/review`,
    data: payload
  });
}

/** The QR image attached to an account; every download is audited server-side. */
export function downloadPayoutQr(id: string): Promise<Blob> {
  return http.download({ method: "get", url: `/files/${id}` }).then(file => file.blob);
}
