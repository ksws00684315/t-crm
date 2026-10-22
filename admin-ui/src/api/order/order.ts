import { http } from "../http";
import type { DownloadedFile } from "@/utils/http";
import type {
  AttributionStatus,
  Channel,
  MatchEvidenceVO,
  OrderChainVO,
  OrderDetailVO,
  OrderStatus,
  OrderSyncStateVO,
  OrderVO,
  PageResult,
  RefundDetailVO,
  RefundStatus,
  RefundType,
  RefundVO,
  SyncFailureVO
} from "../types";

/**
 * `OrderQuery`; omitted fields mean "no filter". `payFrom` is inclusive and `payTo` exclusive;
 * both are UTC ISO 8601 instants (the page converts from the browser time zone).
 */
export interface OrderQuery {
  page?: number;
  size?: number;
  shopId?: string;
  payFrom?: string;
  payTo?: string;
  kolCode?: string;
  orderStatus?: OrderStatus;
  attributionStatus?: AttributionStatus;
  /** Task 40 §3.2: exact Weidian order number. */
  orderNo?: string;
  channel?: Channel;
}

/** `GET /order/refunds` filters (task 40 §3.3). */
export interface RefundQuery {
  page?: number;
  size?: number;
  refundNo?: string;
  orderNo?: string;
  channel?: Channel;
  refundStatus?: RefundStatus;
  refundType?: RefundType;
}

/** `PUT /order/orders/{id}/attribution`: exactly one of `kolId` and `nonKol`. */
export interface AssignAttributionRequest {
  kolId?: string;
  nonKol?: boolean;
  reason: string;
}

/** Accepted order/refund import (HTTP 202, task 18 contract). */
export interface OrderImportVO {
  taskId: string;
}

export function listOrders(query: OrderQuery): Promise<PageResult<OrderVO>> {
  return http.request<PageResult<OrderVO>>({ method: "get", url: "/order/orders", params: query });
}

/** The manual matching pool: CONFLICT / UNMATCHED orders with their lines. */
export function listUnmatchedOrders(query: OrderQuery): Promise<PageResult<OrderVO>> {
  return http.request<PageResult<OrderVO>>({
    method: "get",
    url: "/order/unmatched",
    params: query
  });
}

/** Order detail with merchant info and product rows (task 40 §3.2). */
export function getOrder(id: string): Promise<OrderDetailVO> {
  return http.request<OrderDetailVO>({ method: "get", url: `/order/orders/${id}` });
}

/** The per-line chain: copy job → KOL item link → order line → refunds (task 40 §3.4). */
export function getOrderChain(id: string): Promise<OrderChainVO> {
  return http.request<OrderChainVO>({ method: "get", url: `/order/orders/${id}/chain` });
}

export function listRefunds(query: RefundQuery): Promise<PageResult<RefundVO>> {
  return http.request<PageResult<RefundVO>>({
    method: "get",
    url: "/order/refunds",
    params: query
  });
}

export function getRefund(id: string): Promise<RefundDetailVO> {
  return http.request<RefundDetailVO>({ method: "get", url: `/order/refunds/${id}` });
}

export function listSyncStates(): Promise<OrderSyncStateVO[]> {
  return http.request<OrderSyncStateVO[]>({ method: "get", url: "/order/sync-states" });
}

/** Recent sync and import failures across the shops (task 40 §3.5). */
export function listSyncFailures(): Promise<SyncFailureVO[]> {
  return http.request<SyncFailureVO[]>({ method: "get", url: "/order/sync-failures" });
}

/** order:manage; delete company sync failures older than the given days. Returns deleted count. */
export function deleteSyncFailures(olderThanDays: number): Promise<number> {
  return http.request<number>({
    method: "delete",
    url: "/order/sync-failures",
    params: { olderThanDays }
  });
}

/** Matching-pool evidence for one order item (task 40 §3.6). */
export function getMatchEvidence(orderItemId: string): Promise<MatchEvidenceVO> {
  return http.request<MatchEvidenceVO>({
    method: "get",
    url: `/order/unmatched/${orderItemId}/evidence`
  });
}

/** 202 once the background worker starts; 409 ORDER_SYNC_RUNNING while the shop lock is held. */
export function triggerOrderSync(shopId: string, type?: "ORDER" | "REFUND"): Promise<null> {
  return http.request<null>({
    method: "post",
    url: "/order/syncs",
    data: { shopId, ...(type ? { type } : {}) }
  });
}

export function assignOrderAttribution(
  id: string,
  request: AssignAttributionRequest
): Promise<null> {
  return http.request<null>({
    method: "put",
    url: `/order/orders/${id}/attribution`,
    data: request
  });
}

export function reattributeOrder(id: string): Promise<null> {
  return http.request<null>({ method: "post", url: `/order/orders/${id}/reattribution` });
}

export function downloadOrderImportTemplate(): Promise<DownloadedFile> {
  return http.download({ method: "get", url: "/order/order-imports/template" });
}

/**
 * `dataCutoffTime` (task 40 §3.5) is when the file's data ends, UTC ISO 8601. ImportDialog
 * enforces it before enabling the upload; the parameter stays optional so the shared dialog
 * prop type also covers imports without a cutoff.
 */
export function uploadOrderImport(file: File, dataCutoffTime?: string): Promise<OrderImportVO> {
  const form = new FormData();
  form.append("file", file);
  if (dataCutoffTime) form.append("dataCutoffTime", dataCutoffTime);
  return http.request<OrderImportVO>({ method: "post", url: "/order/order-imports", data: form });
}

export function downloadRefundImportTemplate(): Promise<DownloadedFile> {
  return http.download({ method: "get", url: "/order/refund-imports/template" });
}

export function uploadRefundImport(file: File, dataCutoffTime?: string): Promise<OrderImportVO> {
  const form = new FormData();
  form.append("file", file);
  if (dataCutoffTime) form.append("dataCutoffTime", dataCutoffTime);
  return http.request<OrderImportVO>({ method: "post", url: "/order/refund-imports", data: form });
}
