import { http } from "./http";
import { isDemoMode as demoMode } from "@/utils/demo-mode";
let demoAccounts: PayoutAccountVO[] = [
  {
    id: "1",
    accountType: "PAYPAL",
    account: "a***@mail.com",
    holderName: "Lin Jianfeng",
    currency: "USD",
    network: null,
    qrFileId: null,
    status: "APPROVED",
    defaultAccount: true,
    rejectReason: null,
    lastSubmittedAt: "2026-10-12T08:00:00Z",
    reviewedTime: "2026-10-13T08:00:00Z"
  }
];

/** `KolPayoutAccountVO` (task 15a §3.1); ids arrive as strings, times as ISO 8601 instants. */
export interface PayoutAccountVO {
  id: string;
  accountType: "PAYPAL" | "USDT";
  account: string;
  holderName: string | null;
  /** PayPal only (task 25, R18): ISO 4217 currency; null for USDT. */
  currency: string | null;
  network: "TRC20" | "ERC20" | "BEP20" | "SOL" | "TON" | null;
  qrFileId: string | null;
  status: "PENDING" | "APPROVED" | "REJECTED" | "DISABLED";
  defaultAccount: boolean;
  rejectReason: string | null;
  lastSubmittedAt: string;
  reviewedTime: string | null;
}

/** Create and edit share one request body; only the fields of the chosen type are sent. */
export interface PayoutAccountPayload {
  accountType: "PAYPAL" | "USDT";
  account: string;
  holderName?: string;
  /** PayPal only (task 25, R18): ISO 4217; the form always sends one. */
  currency?: string;
  network?: "TRC20" | "ERC20" | "BEP20" | "SOL" | "TON";
  qrFileId?: string;
}

export function listPayoutAccounts(): Promise<PayoutAccountVO[]> {
  if (demoMode) return Promise.resolve(demoAccounts);
  return http.request<PayoutAccountVO[]>({ method: "GET", url: "/payout-accounts" });
}

export function createPayoutAccount(payload: PayoutAccountPayload): Promise<PayoutAccountVO> {
  if (demoMode) {
    const created: PayoutAccountVO = {
      id: `demo-${Date.now()}`,
      accountType: payload.accountType,
      account: payload.account,
      holderName: payload.holderName ?? null,
      currency: payload.currency ?? null,
      network: payload.network ?? null,
      qrFileId: payload.qrFileId ?? null,
      status: "APPROVED",
      defaultAccount: false,
      rejectReason: null,
      lastSubmittedAt: new Date().toISOString(),
      reviewedTime: null
    };
    demoAccounts = [...demoAccounts, created];
    return Promise.resolve(created);
  }
  return http.request<PayoutAccountVO>({ method: "POST", url: "/payout-accounts", data: payload });
}

/** Edits any account that is not disabled; the backend answers with empty data, so reload after. */
export function updatePayoutAccount(id: string, payload: PayoutAccountPayload): Promise<null> {
  if (demoMode) {
    demoAccounts = demoAccounts.map(account =>
      account.id === id
        ? {
            ...account,
            accountType: payload.accountType,
            account: payload.account,
            holderName: payload.holderName ?? null,
            currency: payload.currency ?? null,
            network: payload.network ?? null,
            qrFileId: payload.qrFileId ?? null,
            status: "APPROVED",
            lastSubmittedAt: new Date().toISOString()
          }
        : account
    );
    return Promise.resolve(null);
  }
  return http.request<null>({ method: "PUT", url: `/payout-accounts/${id}`, data: payload });
}

export function setDefaultPayoutAccount(id: string): Promise<null> {
  return http.request<null>({ method: "PUT", url: `/payout-accounts/${id}/default` });
}

export function disablePayoutAccount(id: string): Promise<null> {
  return http.request<null>({ method: "PUT", url: `/payout-accounts/${id}/disable` });
}

/** Uploads a QR image first; the returned id is referenced when the account is saved. */
export function uploadQrFile(file: File): Promise<{ fileId: string }> {
  if (demoMode) return Promise.resolve({ fileId: `demo-file-${file.size}` });
  const form = new FormData();
  form.append("file", file);
  form.append("bizType", "PAYOUT_QR");
  return http.request<{ fileId: string }>({
    method: "POST",
    url: "/files",
    data: form,
    // Without this, axios stringifies the FormData to JSON because the instance default
    // Content-Type is application/json (axios transformRequest, hasJSONContentType branch).
    headers: { "Content-Type": "multipart/form-data" },
    timeout: 120000
  });
}

/** The QR image of one of my own uploads as a blob; the token never goes into the URL. */
export function downloadQrFile(id: string): Promise<Blob> {
  return http.download({ method: "GET", url: `/files/${id}` }).then(file => file.blob);
}
