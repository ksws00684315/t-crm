import { translate } from "@/locales";
import type { AssignAttributionRequest, OrderQuery } from "@/api/order/order";
import type {
  AttributionStatus,
  Channel,
  ItemAttributionType,
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

/** Selectable attribution status filters (design §4.6). */
export const ATTRIBUTION_STATUSES: readonly AttributionStatus[] = [
  "PENDING",
  "ATTRIBUTED",
  "NON_KOL",
  "CONFLICT",
  "UNMATCHED"
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

export function attributionStatusText(status: AttributionStatus | null | undefined): string {
  return status ? translate(`order.attribution.${status}`) : "-";
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

export function syncSourceText(source: SyncSource | null | undefined): string {
  return source ? translate(`order.source.${source}`) : "-";
}

/** Tag colour per attribution status: settled greens, warn on manual work, muted otherwise. */
export function attributionTagType(
  status: AttributionStatus | null | undefined
): "success" | "warning" | "danger" | "info" {
  switch (status) {
    case "ATTRIBUTED":
    case "NON_KOL":
      return "success";
    case "CONFLICT":
      return "danger";
    case "UNMATCHED":
      return "warning";
    default:
      return "info";
  }
}

export function attributionTypeText(type: ItemAttributionType | null | undefined): string {
  return type == null ? "-" : translate(`order.line.attributionType.${type}`);
}

/**
 * Builds the manual attribution request body, or null when the reason is missing/overlong or the
 * target is invalid (exactly one of "assign a KOL" and "mark non-KOL"; reason ≤ 255 characters).
 */
export function manualAttributionRequest(
  target: { kolId: string } | { nonKol: true } | null,
  reason: string
): AssignAttributionRequest | null {
  const trimmed = reason.trim();
  if (!target || trimmed.length === 0 || trimmed.length > 255) return null;
  return "kolId" in target
    ? { kolId: target.kolId, reason: trimmed }
    : { nonKol: true, reason: trimmed };
}

/**
 * Converts the locally picked payment interval into UTC query params. The backend treats
 * `payFrom` as inclusive and `payTo` as exclusive, so the picked end is sent unchanged.
 */
export function payRangeToQuery(range: [Date, Date] | null): Pick<OrderQuery, "payFrom" | "payTo"> {
  if (!range || range.length !== 2 || !range[0] || !range[1]) return {};
  return { payFrom: range[0].toISOString(), payTo: range[1].toISOString() };
}
