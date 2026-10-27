import { http } from "../http";
import type { PageResult } from "../types";

export type NotificationType =
  | "PREPAY_SUBMITTED"
  | "PREPAY_APPROVED"
  | "PREPAY_REJECTED"
  | "PREPAY_PAID"
  | "WITHDRAWAL_SUBMITTED"
  | "WITHDRAWAL_APPROVED"
  | "WITHDRAWAL_REJECTED"
  | "WITHDRAWAL_PAID"
  | "PAYOUT_ACCOUNT_SUBMITTED"
  | "ORDER_SYNC_FAILED"
  | "BD_TRANSFER_CANCELLED"
  | "KOLS_UNASSIGNED"
  | "DEPARTURE_PLAN_SUBMITTED"
  | "DEPARTURE_PLAN_EXECUTED";

/** Render parameters. Only the fields for the message type are present. */
export interface NotificationParams {
  kolCode: string | null;
  /**
   * Creator display name (task 71, B06): present on newly written messages, null on history that
   * only carries the code and when the creator no longer resolved at write time.
   */
  kolName?: string | null;
  accountType: string | null;
  shopName: string | null;
  reason: string | null;
  bdName: string | null;
  count: number | null;
  prepayNo?: string | null;
  /** Withdrawal messages (task 63): the application number, e.g. W20261027... */
  applicationNo?: string | null;
  /** Departure messages: heir display name, ISO instant, result and plan type of the plan. */
  heirName: string | null;
  effectiveAt: string | null;
  result: string | null;
  planType: string | null;
}

export interface NotificationVO {
  id: string;
  type: NotificationType;
  params: NotificationParams;
  bizType: string | null;
  bizId: string | null;
  readTime: string | null;
  createTime: string;
}

export function listNotifications(
  page: number,
  size: number,
  unread = false
): Promise<PageResult<NotificationVO>> {
  return http.request<PageResult<NotificationVO>>({
    method: "get",
    url: "/notification/notifications",
    params: { page, size, ...(unread ? { unread: true } : {}) }
  });
}

export async function unreadNotificationCount(): Promise<number> {
  const data = await http.request<{ count: string }>({
    method: "get",
    url: "/notification/notifications/unread-count"
  });
  return Number(data.count);
}

export function markNotificationRead(id: string): Promise<void> {
  return http.request<void>({
    method: "put",
    url: `/notification/notifications/${id}/read`
  });
}

export function markAllNotificationsRead(): Promise<void> {
  return http.request<void>({
    method: "put",
    url: "/notification/notifications/read-all"
  });
}
