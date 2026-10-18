import { http } from "../http";
import type { ShopVO } from "../types";

export interface CreateShopRequest {
  shopName: string;
  weidianShopId: string;
  remark?: string | null;
}

export interface EditShopRequest {
  shopName: string;
  remark?: string | null;
}

export function listShops(): Promise<ShopVO[]> {
  return http.request<ShopVO[]>({ method: "get", url: "/product/shops" });
}

export function createShop(request: CreateShopRequest): Promise<ShopVO> {
  return http.request<ShopVO>({ method: "post", url: "/product/shops", data: request });
}

export function editShop(id: string, request: EditShopRequest): Promise<null> {
  return http.request<null>({ method: "put", url: `/product/shops/${id}`, data: request });
}

export function updateShopStatus(id: string, status: 0 | 1): Promise<null> {
  return http.request<null>({
    method: "put",
    url: `/product/shops/${id}/status`,
    data: { status }
  });
}

export function setCopyTarget(id: string): Promise<null> {
  return http.request<null>({ method: "put", url: `/product/shops/${id}/copy-target` });
}

export function clearCopyTarget(id: string): Promise<null> {
  return http.request<null>({ method: "delete", url: `/product/shops/${id}/copy-target` });
}
