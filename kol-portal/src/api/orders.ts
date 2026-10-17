import { http } from "./http";

export type OrderStatus =
  "UNPAID" | "PAID" | "SHIPPED" | "RECEIVED" | "COMPLETED" | "CLOSED" | "UNKNOWN";

export interface KolOrderItem {
  itemName: string;
  skuName: string;
  quantity: number;
  payAmount: string;
  refundAmount: string;
}

export interface KolOrder {
  orderNo: string;
  payTime: string;
  orderStatus: OrderStatus;
  payAmount: string;
  refundAmount: string;
  items: KolOrderItem[];
}

export interface KolOrderPage {
  items: KolOrder[];
  total: number | string;
  page: number;
  size: number;
}

export interface KolOrderQuery {
  payFrom: string;
  payTo: string;
  orderStatus?: OrderStatus;
  page: number;
  size: number;
}

export const getKolOrders = (params: KolOrderQuery) =>
  http.request<KolOrderPage>({ method: "GET", url: "/orders", params });
