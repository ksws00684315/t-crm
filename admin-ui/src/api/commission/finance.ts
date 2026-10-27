import { translate } from "@/locales";
import { http } from "../http";
import {
  financePreview,
  financeApiReady,
  previewContext,
  currentFinanceRole,
  currentFinanceIdentity,
  financePreviewAllows
} from "@/utils/finance";
import { ApiError } from "@/utils/http/error";
import type { FinanceDemo } from "../../../preview/commission/finance-engine";
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
  WithdrawalQuery,
  WithdrawalOrder,
  FinanceTask
} from "./finance-types";

type Demo = FinanceDemo;
const previewBuild = financePreview;
let demo: Demo | undefined;
function financeError(status: number, errorCode = "COMMON_INTERNAL_ERROR"): never {
  throw new ApiError({ status, errorCode, traceId: "finance-api-unavailable" });
}
async function previewModules() {
  if (!previewBuild || !financePreview) financeError(503);
  const [{ createFinanceDemo }, sync] = await Promise.all([
    import("../../../preview/commission/finance-engine"),
    import("../../../preview/commission/finance-preview-sync")
  ]);
  demo ??= createFinanceDemo();
  return { demo, sync };
}
async function call<T>(
  method: "get" | "put",
  url: string,
  payload: unknown,
  local: (preview: Demo) => T
): Promise<T> {
  if (previewBuild && financePreview) {
    if (!financePreviewAllows("view")) financeError(403, "AUTH_FORBIDDEN");
    if (previewContext.failWrites && method !== "get") financeError(503, "COMMON_INTERNAL_ERROR");
    const { demo: preview, sync } = await previewModules();
    return sync.withPreviewState(async () => {
      await sync.loadPreviewState(preview);
      const result = local(preview);
      if (method !== "get") await sync.savePreviewState(preview);
      return result;
    });
  }
  if (!financeApiReady) financeError(503);
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
  call<IncomeResult>("get", "/commission/incomes", query, preview =>
    preview.listIncome(role(), scenario(), query)
  );
export const getIncome = (id: string, query: IncomeQuery) =>
  call<IncomeDetail>("get", `/commission/incomes/${id}`, query, preview =>
    preview.incomeDetail(role(), scenario(), id, query)
  );
export const listFinanceTasks = (query: WithdrawalQuery) => {
  return call<Page<FinanceTask>>("get", "/commission/finance-tasks", query, preview => {
    const result = preview.listWithdrawals(role(), scenario(), query);
    return {
      ...result,
      items: result.items.map(row => ({ ...row, type: "COMMISSION" as const }))
    } as Page<FinanceTask>;
  });
};
export const getWithdrawal = (id: string) =>
  call<Withdrawal>("get", `/commission/withdrawals/${id}`, {}, preview =>
    preview.withdrawal(role(), scenario(), id)
  );
export const getWithdrawalOrders = (id: string, query: { page: number; size: number }) =>
  call<Page<WithdrawalOrder>>("get", `/commission/withdrawals/${id}/orders`, query, preview =>
    preview.withdrawalOrders(role(), scenario(), id, query)
  );
export const reviewWithdrawal = (id: string, decision: "APPROVE" | "REJECT", note: string) =>
  call<Withdrawal>("put", `/commission/withdrawals/${id}/review`, { decision, note }, preview =>
    previewAction("review", () =>
      preview.review(role(), scenario(), id, decision, note, currentFinanceIdentity())
    )
  );
export const payWithdrawal = (id: string, payload: PaymentRegistration, idempotencyKey: string) =>
  call<Withdrawal>(
    "put",
    `/commission/withdrawals/${id}/payment`,
    { ...payload, idempotencyKey },
    preview =>
      previewAction("pay", () => preview.pay(role(), scenario(), id, payload, idempotencyKey))
  );
export const getConfig = () =>
  call<SettlementConfig>("get", "/commission/settlement-configs/current", {}, preview =>
    preview.getConfig(role(), scenario())
  );
export const saveConfig = (payload: SettlementConfig) =>
  call<SettlementConfig>("put", "/commission/settlement-configs/current", payload, preview =>
    previewAction("configure", () => preview.saveConfig(role(), scenario(), payload))
  );
export const listSettlements = (query: SettlementQuery) =>
  call<Page<Settlement>>("get", "/commission/settlement-details", query, preview =>
    preview.settlements(role(), scenario(), query)
  );
export const resetFinanceDemo = async () => {
  if (previewBuild && financePreview) {
    const { demo: preview, sync } = await previewModules();
    await sync.withPreviewState(async () => {
      await sync.clearPreviewState();
      preview.reset();
    });
  }
};
export async function uploadWithdrawalVoucher(file: File): Promise<{ fileId: string }> {
  if (previewBuild && financePreview) return { fileId: `t1023_preview_voucher_${file.size}` };
  if (!financeApiReady) financeError(503);
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
  previewBuild
    ? Promise.resolve({
        blob: new Blob(["t1023_demo_payment_voucher"], { type: "text/plain" }),
        fileName: "t1023_voucher.txt"
      })
    : financeApiReady
      ? http.download({ url: `/files/${id}`, method: "get" })
      : Promise.reject(
          new ApiError({
            status: 503,
            errorCode: "COMMON_INTERNAL_ERROR",
            traceId: "finance-api-unavailable"
          })
        );

export async function exportWithdrawals(query: WithdrawalQuery) {
  if (!previewBuild) {
    if (!financeApiReady) financeError(503);
    // Keep the list filters (including kolId, #132): the export endpoint accepts the same query
    // parameters as the list; type/page/size are list-only and not sent.
    const { applicationNo, kolId, status } = query;
    return http.download({
      url: "/commission/withdrawals/export",
      method: "get",
      params: {
        ...(applicationNo ? { applicationNo } : {}),
        ...(kolId?.trim() ? { kolId: kolId.trim() } : {}),
        ...(status ? { status } : {})
      }
    });
  }
  if (!financePreviewAllows("export")) financeError(403, "AUTH_FORBIDDEN");
  const { demo: preview, sync } = await previewModules();
  await sync.withPreviewState(() => sync.loadPreviewState(preview));
  const rows: Withdrawal[] = [];
  let current = 1;
  let total = 0;
  do {
    const result = preview.listWithdrawals(role(), scenario(), {
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
