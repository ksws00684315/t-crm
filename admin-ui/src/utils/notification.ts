import type { NotificationParams, NotificationType } from "@/api/notification/notification";
import { translate } from "@/locales";
import type { AccessProfile } from "@/utils/permission";
import { formatInstant, formatInstantToMinute } from "@/utils/format";

export const NOTIFICATION_POLL_MS = 60_000;

const MINUTE = 60_000;
const HOUR = 60 * MINUTE;
const DAY = 24 * HOUR;

export interface NotificationView {
  type: NotificationType | string;
  params: Partial<NotificationParams> | null;
  bizId: string | null;
}

/** Unread badge text. `null` hides the badge; values above 99 render as 99+. */
export function unreadBadge(count: number): string | null {
  if (!Number.isFinite(count) || count <= 0) return null;
  if (count > 99) return "99+";
  return String(Math.floor(count));
}

/** Relative time within 7 days; older messages use the shared instant format. */
export function relativeNotificationTime(iso: string, now = Date.now()): string {
  const then = new Date(iso).getTime();
  if (Number.isNaN(then)) return formatInstant(iso);
  const delta = Math.max(0, now - then);
  if (delta < MINUTE) return translate("notification.justNow");
  if (delta < HOUR) return translate("notification.minutes", { n: Math.floor(delta / MINUTE) });
  if (delta < DAY) return translate("notification.hours", { n: Math.floor(delta / HOUR) });
  if (delta < 7 * DAY) return translate("notification.days", { n: Math.floor(delta / DAY) });
  return formatInstant(iso);
}

/**
 * Named interpolation values of a message, shared by the plain-text renderer and the sentence
 * component so both always agree. `accountType` stays the raw enum name.
 */
export function notificationValues(item: NotificationView): Record<string, string | number> {
  const params = item.params ?? {};
  const reason = params.reason ? translate(`notification.reason.${params.reason}`) : "";
  // Departure messages carry codes for the plan type and its outcome; both become words here so
  // the sentence switches language as a whole.
  const kind = params.planType ? translate(`notification.planType.${params.planType}`) : "";
  const result = params.result
    ? translate(`notification.result.${params.result}`, { heir: params.heirName ?? "" })
    : "";
  return {
    kolCode: params.kolCode ?? "",
    accountType: params.accountType ?? "",
    shopName: params.shopName ?? "",
    reason,
    bdName: params.bdName ?? "",
    count: params.count ?? 0,
    prepayNo: params.prepayNo ?? "",
    applicationNo: params.applicationNo ?? "",
    time: params.effectiveAt ? formatInstantToMinute(params.effectiveAt) : "",
    kind,
    result
  };
}

/** Message text in the current language, with the creator code inline. */
export function notificationText(item: NotificationView): string {
  return translate(`notification.type.${item.type}`, notificationValues(item));
}

/** Target route. Missing pages render the existing 404. */
export function notificationHref(item: NotificationView): string {
  switch (item.type) {
    case "PREPAY_SUBMITTED":
    case "PREPAY_APPROVED":
    case "PREPAY_REJECTED":
    case "PREPAY_PAID":
      return `/fund/prepays?id=${encodeURIComponent(item.bizId ?? "")}`;
    case "WITHDRAWAL_SUBMITTED":
    case "WITHDRAWAL_APPROVED":
    case "WITHDRAWAL_REJECTED":
    case "WITHDRAWAL_PAID":
      // withdrawalId opens the task drawer; `id` stays the payout-account jump below.
      return `/fund/payouts?withdrawalId=${encodeURIComponent(item.bizId ?? "")}`;
    case "PAYOUT_ACCOUNT_SUBMITTED":
      return `/fund/payouts?id=${encodeURIComponent(item.bizId ?? "")}`;
    case "ORDER_SYNC_FAILED":
      return "/order/orders";
    case "BD_TRANSFER_CANCELLED":
      // Transfer records live in the audit log since task 32 §2 (decision 5); the kol code is
      // the keyword, so the link lands on that creator's transfer rows.
      return `/system/oper-logs?module=KOL&keyword=${encodeURIComponent(item.params?.kolCode ?? "")}`;
    case "KOLS_UNASSIGNED":
      return "/kol/kols";
    case "DEPARTURE_PLAN_SUBMITTED":
    case "DEPARTURE_PLAN_EXECUTED":
      // The plan and its outcome live in the account list (task 41 §2.4).
      return "/system/users";
    default:
      return "/notifications";
  }
}

/** Whether the signed-in account may open the message's target page. */
export function canOpenNotification(type: string, access: AccessProfile): boolean {
  switch (type) {
    case "PREPAY_SUBMITTED":
    case "PREPAY_APPROVED":
    case "PREPAY_REJECTED":
    case "PREPAY_PAID":
      return (
        !access.isPlatformAdmin &&
        (access.can("commission:view") || access.can("prepay:review") || access.can("prepay:pay"))
      );
    case "WITHDRAWAL_SUBMITTED":
    case "WITHDRAWAL_APPROVED":
    case "WITHDRAWAL_REJECTED":
    case "WITHDRAWAL_PAID":
      // Same gate as the /fund/payouts route the jump lands on (any one of the codes).
      return (
        !access.isPlatformAdmin &&
        (access.can("payout-account:review") || access.can("commission:view"))
      );
    case "PAYOUT_ACCOUNT_SUBMITTED":
      return access.can("payout-account:review") || access.can("commission:view");
    case "ORDER_SYNC_FAILED":
      return access.can("order:view");
    case "BD_TRANSFER_CANCELLED":
      return access.isSuperAdmin;
    case "KOLS_UNASSIGNED":
      return access.can("kol:view");
    case "DEPARTURE_PLAN_SUBMITTED":
    case "DEPARTURE_PLAN_EXECUTED":
      return access.isSuperAdmin;
    default:
      return false;
  }
}
