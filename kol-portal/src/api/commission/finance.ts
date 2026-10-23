import { http } from "../http";
import { financePreview, previewContext } from "@/utils/finance";
import { financeError, createFinanceDemo } from "./finance-engine";
import {
  clearPreviewState,
  loadPreviewState,
  savePreviewState,
  withPreviewState
} from "./finance-preview-sync";
import type {
  AccountSnapshot,
  IncomeDetail,
  IncomeQuery,
  Page,
  SettlementConfig,
  Withdrawal,
  WithdrawalRequest
} from "./finance-types";
const demo = financePreview ? createFinanceDemo() : null;
async function call<T>(
  method: "get" | "post" | "put",
  url: string,
  payload: unknown,
  local: () => T
): Promise<T> {
  if (financePreview) {
    if (previewContext.failWrites && method !== "get") financeError(503, "COMMON_INTERNAL_ERROR");
    return withPreviewState(async () => {
      await loadPreviewState(demo!);
      const result = local();
      if (method !== "get") await savePreviewState(demo!);
      return result;
    });
  }
  return http.request<T>({
    url,
    method,
    ...(method === "get" ? { params: payload } : { data: payload })
  });
}
// Creator identity is deliberately absent from every request. The backend must use the session.
export const getMyIncome = (query: IncomeQuery) =>
  call<IncomeDetail>("get", "/commissions/overview", query, () =>
    demo!.incomeDetail("KOL", previewContext.scenario, "102301", query)
  );
export const getWithdrawalConditions = () =>
  call<SettlementConfig>("get", "/withdrawals/conditions", {}, () =>
    demo!.getConfig("KOL", previewContext.scenario)
  );
export const getMyAccounts = () =>
  call<AccountSnapshot[]>("get", "/withdrawals/recipient-options", {}, () => demo!.accounts());
export const listMyWithdrawals = (page: number, size: number) =>
  call<Page<Withdrawal>>("get", "/withdrawals", { page, size }, () =>
    demo!.listWithdrawals("KOL", previewContext.scenario, { page, size })
  );
export const getMyWithdrawal = (id: string) =>
  call<Withdrawal>("get", `/withdrawals/${id}`, {}, () =>
    demo!.withdrawal("KOL", previewContext.scenario, id)
  );
export const applyWithdrawal = (payload: WithdrawalRequest) =>
  call<Withdrawal>("post", "/withdrawals", payload, () =>
    demo!.apply("KOL", previewContext.scenario, payload)
  );
export const cancelWithdrawal = (id: string) =>
  call<Withdrawal>("put", `/withdrawals/${id}/withdraw`, {}, () =>
    demo!.cancel("KOL", previewContext.scenario, id)
  );
export const resetFinanceDemo = async () => {
  if (financePreview) {
    await withPreviewState(async () => {
      await clearPreviewState();
      demo!.reset();
    });
  }
};
export const downloadWithdrawalVoucher = (id: string) =>
  financePreview
    ? Promise.resolve(new Blob(["t1023_demo_payment_voucher"], { type: "text/plain" }))
    : http.download({ url: `/files/${id}`, method: "get" }).then(file => file.blob);

export async function uploadRecipientQr(file: File): Promise<{ fileId: string }> {
  if (financePreview) return { fileId: `t1023_qr_${file.size}` };
  const data = new FormData();
  data.append("file", file);
  data.append("bizType", "PAYOUT_QR");
  return http.request({
    url: "/files",
    method: "post",
    data,
    headers: { "Content-Type": "multipart/form-data" }
  });
}
