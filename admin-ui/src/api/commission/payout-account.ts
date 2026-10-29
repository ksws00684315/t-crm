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
  /** PayPal only (task 25, R18): ISO 4217, null for USDT. */
  currency: string | null;
  network: "TRC20" | "ERC20" | "BEP20" | "SOL" | "TON" | null;
  qrFileId: string | null;
  /** Accounts are in effect at once (task 82); PENDING and REJECTED are history only. */
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
  /** Unset or `ALL` lists every status, disabled accounts included. */
  status?: "PENDING" | "APPROVED" | "REJECTED" | "DISABLED" | "ALL";
  type?: "PAYPAL" | "USDT";
  kolCode?: string;
  /** Exact creator id, used by the creator's fund dialog. */
  kolId?: string;
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
      kolId: query.kolId || undefined,
      page: query.page,
      size: query.size
    }
  });
}

export function getPayoutAccount(id: string): Promise<PayoutAccountDetailVO> {
  return http.request<PayoutAccountDetailVO>({
    method: "get",
    url: `/commission/payout-accounts/${id}`
  });
}

/** The QR image attached to an account; every download is audited server-side. */
export function downloadPayoutQr(id: string): Promise<Blob> {
  return http.download({ method: "get", url: `/files/${id}` }).then(file => file.blob);
}
