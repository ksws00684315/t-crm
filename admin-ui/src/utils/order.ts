import { translate } from "@/locales";
import type { OrderQuery } from "@/api/order/order";
import type {
  Channel,
  OrderRefundStatus,
  OrderSource,
  OrderStatus,
  RefundStatus,
  RefundType,
  SyncSource
} from "@/api/types";

/** Selectable order status filters (design §8.1). */
export const ORDER_STATUSES: readonly OrderStatus[] = [
  "UNPAID",
  "PAID",
  "SHIPPED",
  "RECEIVED",
  "COMPLETED",
  "CLOSED",
  "UNKNOWN"
];

/** Selectable channels; Weidian is the only one today. */
export const CHANNELS: readonly Channel[] = ["WEIDIAN"];

/** Selectable single-refund statuses (task 40 §3.1). */
export const REFUND_STATUSES: readonly RefundStatus[] = [
  "PROCESSING",
  "SUCCESS",
  "CLOSED",
  "UNKNOWN"
];

/** Selectable refund types (requirement 08.02). */
export const REFUND_TYPES: readonly RefundType[] = ["REFUND_ONLY", "RETURN_REFUND", "UNKNOWN"];

export function orderStatusText(status: OrderStatus | null | undefined): string {
  return status ? translate(`order.status.${status}`) : "-";
}

export function channelText(channel: Channel | null | undefined): string {
  return channel ? translate(`order.channelOption.${channel}`) : "-";
}

/** Order-level refund summary: none / processing / partial / full success (08.02). */
export function orderRefundStatusText(status: OrderRefundStatus | null | undefined): string {
  return status ? translate(`order.refundStatus.${status}`) : "-";
}

/** Refunds are negative for revenue, so every refunded state warns; none stays muted. */
export function orderRefundTagType(
  status: OrderRefundStatus | null | undefined
): "success" | "warning" | "danger" | "info" {
  if (status === "FULL_SUCCESS" || status === "PARTIAL_SUCCESS") return "danger";
  if (status === "PROCESSING") return "warning";
  return "info";
}

export function refundStatusText(status: RefundStatus | null | undefined): string {
  return status ? translate(`order.refund.status.${status}`) : "-";
}

/** Only SUCCESS is a completed refund; PROCESSING must never read as success (08.02). */
export function refundTagType(
  status: RefundStatus | null | undefined
): "success" | "warning" | "danger" | "info" {
  if (status === "SUCCESS") return "success";
  if (status === "PROCESSING") return "warning";
  return "info";
}

export function refundTypeText(type: RefundType | null | undefined): string {
  return type ? translate(`order.refund.type.${type}`) : "-";
}

export function orderSourceText(source: OrderSource | null | undefined): string {
  return source ? translate(`order.source.${source}`) : "-";
}

/** Sync-monitoring provenance: the API stream or a manual import (task 40 §3.5). */
export function syncSourceText(source: SyncSource | null | undefined): string {
  return source ? translate(`order.source.${source}`) : "-";
}

/**
 * Converts the locally picked platform-order interval into UTC query params (task 70 B01). The
 * same inclusive/exclusive bounds as the payment window, but over `crm_order.order_time`.
 */
export function orderTimeRangeToQuery(
  range: [Date, Date] | null
): Pick<OrderQuery, "orderTimeFrom" | "orderTimeTo"> {
  if (!range || range.length !== 2 || !range[0] || !range[1]) return {};
  return { orderTimeFrom: range[0].toISOString(), orderTimeTo: range[1].toISOString() };
}
