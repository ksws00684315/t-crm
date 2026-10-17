import { translate } from "@/locales";
import type { AssignAttributionRequest, OrderQuery } from "@/api/order/order";
import type { AttributionStatus, ItemAttributionType, OrderStatus } from "@/api/types";

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

export function orderStatusText(status: OrderStatus | null | undefined): string {
  return status ? translate(`order.status.${status}`) : "-";
}

export function attributionStatusText(status: AttributionStatus | null | undefined): string {
  return status ? translate(`order.attribution.${status}`) : "-";
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
