import { translate } from "@/locales";
import { http } from "../http";
import {
  financePreview,
  previewContext,
  currentFinanceRole,
  financePreviewAllows
} from "@/utils/finance";
import { financeError, createFinanceDemo } from "./finance-engine";
import {
  clearPreviewState,
  loadPreviewState,
  savePreviewState,
  withPreviewState
} from "./finance-preview-sync";
import type {
  IncomeDetail,
  IncomeQuery,
  IncomeResult,
  Page,
  PaymentRegistration,
  Settlement,
  SettlementConfig,
  SettlementQuery,
  Withdrawal,
  WithdrawalQuery
} from "./finance-types";

const demo = financePreview ? createFinanceDemo() : null;
async function call<T>(
  method: "get" | "put",
  url: string,
  payload: unknown,
  local: () => T
): Promise<T> {
  if (financePreview) {
    if (!financePreviewAllows("view")) financeError(403, "AUTH_FORBIDDEN");
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
function previewAction<T>(capability: "review" | "pay" | "configure", action: () => T): T {
  if (!financePreviewAllows(capability)) financeError(403, "AUTH_FORBIDDEN");
  return action();
}
const role = () => currentFinanceRole();
const scenario = () => previewContext.scenario;
export const listIncome = (query: IncomeQuery) =>
  call<IncomeResult>("get", "/commission/incomes", query, () =>
    demo!.listIncome(role(), scenario(), query)
  );
export const getIncome = (id: string, query: IncomeQuery) =>
  call<IncomeDetail>("get", `/commission/incomes/${id}`, query, () =>
    demo!.incomeDetail(role(), scenario(), id, query)
  );
export const listWithdrawals = (query: WithdrawalQuery) =>
  call<Page<Withdrawal>>("get", "/commission/withdrawals", query, () =>
    demo!.listWithdrawals(role(), scenario(), query)
  );
export const getWithdrawal = (id: string) =>
  call<Withdrawal>("get", `/commission/withdrawals/${id}`, {}, () =>
    demo!.withdrawal(role(), scenario(), id)
  );
export const reviewWithdrawal = (id: string, decision: "APPROVE" | "REJECT", note: string) =>
  call<Withdrawal>("put", `/commission/withdrawals/${id}/review`, { decision, note }, () =>
    previewAction("review", () => demo!.review(role(), scenario(), id, decision, note))
  );
export const payWithdrawal = (id: string, payload: PaymentRegistration, idempotencyKey: string) =>
  call<Withdrawal>(
    "put",
    `/commission/withdrawals/${id}/payment`,
    { ...payload, idempotencyKey },
    () => previewAction("pay", () => demo!.pay(role(), scenario(), id, payload, idempotencyKey))
  );
export const getConfig = () =>
  call<SettlementConfig>("get", "/commission/settlement-configs/current", {}, () =>
    demo!.getConfig(role(), scenario())
  );
export const saveConfig = (payload: SettlementConfig) =>
  call<SettlementConfig>("put", "/commission/settlement-configs/current", payload, () =>
    previewAction("configure", () => demo!.saveConfig(role(), scenario(), payload))
  );
export const listSettlements = (query: SettlementQuery) =>
  call<Page<Settlement>>("get", "/commission/settlement-details", query, () =>
    demo!.settlements(role(), scenario(), query)
  );
export const resetFinanceDemo = async () => {
  if (financePreview) {
    await withPreviewState(async () => {
      await clearPreviewState();
      demo!.reset();
    });
  }
};
export async function uploadWithdrawalVoucher(file: File): Promise<{ fileId: string }> {
  if (financePreview) return { fileId: `t1023_preview_voucher_${file.size}` };
  const data = new FormData();
  data.append("file", file);
  data.append("bizType", "WITHDRAWAL_VOUCHER");
  return http.request({
    url: "/files",
    method: "post",
    data,
    headers: { "Content-Type": "multipart/form-data" }
  });
}
export const downloadWithdrawalVoucher = (id: string) =>
  financePreview
    ? Promise.resolve({
        blob: new Blob(["t1023_demo_payment_voucher"], { type: "text/plain" }),
        fileName: "t1023_voucher.txt"
      })
    : http.download({ url: `/files/${id}`, method: "get" });

export async function exportWithdrawals(query: WithdrawalQuery) {
  if (!financePreview)
    return http.download({ url: "/commission/withdrawals/export", method: "get", params: query });
  if (!financePreviewAllows("export")) financeError(403, "AUTH_FORBIDDEN");
  await withPreviewState(() => loadPreviewState(demo!));
  const rows: Withdrawal[] = [];
  let current = 1;
  let total = 0;
  do {
    const result = demo!.listWithdrawals(role(), scenario(), {
      ...query,
      page: current,
      size: 100
    });
    rows.push(...result.items);
    total = Number(result.total);
    if (!result.items.length) break;
    current++;
  } while (rows.length < total);
  const quote = (value: string) => `"${value.replaceAll('"', '""').replace(/^[=+@-]/, "'$&")}"`;
  const content = [
    ["applicationNo", "creator", "amount", "currency", "status"]
      .map(key => quote(translate(`finance.${key}`)))
      .join(","),
    ...rows.map(row =>
      [row.applicationNo, row.kolName, row.amount, row.currency, translate(`finance.${row.status}`)]
        .map(quote)
        .join(",")
    )
  ].join("\r\n");
  return {
    blob: new Blob(["\ufeff", content], { type: "text/csv;charset=utf-8" }),
    fileName: "t1023_withdrawals.csv"
  };
}
