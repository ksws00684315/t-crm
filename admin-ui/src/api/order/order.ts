import { http } from "../http";
import type { DownloadedFile } from "@/utils/http";
import type {
  AttributionStatus,
  OrderStatus,
  OrderSyncStateVO,
  OrderVO,
  PageResult
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

export function listSyncStates(): Promise<OrderSyncStateVO[]> {
  return http.request<OrderSyncStateVO[]>({ method: "get", url: "/order/sync-states" });
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

export function uploadOrderImport(file: File): Promise<OrderImportVO> {
  const form = new FormData();
  form.append("file", file);
  return http.request<OrderImportVO>({ method: "post", url: "/order/order-imports", data: form });
}

export function downloadRefundImportTemplate(): Promise<DownloadedFile> {
  return http.download({ method: "get", url: "/order/refund-imports/template" });
}

export function uploadRefundImport(file: File): Promise<OrderImportVO> {
  const form = new FormData();
  form.append("file", file);
  return http.request<OrderImportVO>({ method: "post", url: "/order/refund-imports", data: form });
}
