import { http } from "./http";

/** `KolPayoutAccountVO` (task 15a §3.1); ids arrive as strings, times as ISO 8601 instants. */
export interface PayoutAccountVO {
  id: string;
  accountType: "PAYPAL" | "USDT";
  account: string;
  holderName: string | null;
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
  network?: "TRC20" | "ERC20" | "BEP20" | "SOL" | "TON";
  qrFileId?: string;
}

export function listPayoutAccounts(): Promise<PayoutAccountVO[]> {
  return http.request<PayoutAccountVO[]>({ method: "GET", url: "/payout-accounts" });
}

export function createPayoutAccount(payload: PayoutAccountPayload): Promise<PayoutAccountVO> {
  return http.request<PayoutAccountVO>({ method: "POST", url: "/payout-accounts", data: payload });
}

/** Resubmits a PENDING or REJECTED account; the backend answers with empty data, so reload after. */
export function updatePayoutAccount(id: string, payload: PayoutAccountPayload): Promise<null> {
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
