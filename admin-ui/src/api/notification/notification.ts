import { http } from "../http";
import type { PageResult } from "../types";

export type NotificationType =
  | "PREPAY_SUBMITTED"
  | "PREPAY_APPROVED"
  | "PREPAY_REJECTED"
  | "PREPAY_PAID"
  | "PAYOUT_ACCOUNT_SUBMITTED"
  | "ORDER_SYNC_FAILED"
  | "BD_TRANSFER_CANCELLED"
  | "KOLS_UNASSIGNED";

/** Render parameters. Only the fields for the message type are present. */
export interface NotificationParams {
  kolCode: string | null;
  accountType: string | null;
  shopName: string | null;
  reason: string | null;
  bdName: string | null;
  count: number | null;
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
