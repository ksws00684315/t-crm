import { http } from "../http";
import type { CopyChannelVO, PageResult, ShopChannel, ShopOptionVO, ShopVO } from "../types";

export interface ShopQuery {
  shopCode?: string;
  shopName?: string;
  channel?: ShopChannel;
  page?: number;
  size?: number;
}

export interface CreateShopRequest {
  shopName: string;
  channel: ShopChannel;
  weidianShopId?: string | null;
  maxItems: number;
  remark?: string | null;
}

export interface EditShopRequest {
  shopName: string;
  channel: ShopChannel;
  weidianShopId?: string | null;
  maxItems: number;
  remark?: string | null;
}

export function pageShops(query: ShopQuery): Promise<PageResult<ShopVO>> {
  return http.request<PageResult<ShopVO>>({
    method: "get",
    url: "/product/shops",
    params: query
  });
}

/** Full company shop list for selectors (not the paginated shop table). */
export function listShops(): Promise<ShopOptionVO[]> {
  return http.request<ShopOptionVO[]>({ method: "get", url: "/product/shops/options" });
}

export function listCopyChannels(): Promise<CopyChannelVO[]> {
  return http.request<CopyChannelVO[]>({ method: "get", url: "/product/shops/copy-channels" });
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

export function setSourceShop(id: string): Promise<null> {
  return http.request<null>({ method: "put", url: `/product/shops/${id}/source` });
}

export function clearSourceShop(id: string): Promise<null> {
  return http.request<null>({ method: "delete", url: `/product/shops/${id}/source` });
}

export function joinCopyPool(id: string): Promise<null> {
  return http.request<null>({ method: "put", url: `/product/shops/${id}/copy-pool` });
}

export function exitCopyPool(id: string): Promise<null> {
  return http.request<null>({ method: "delete", url: `/product/shops/${id}/copy-pool` });
}

export function refreshShopCapacity(id: string): Promise<ShopVO> {
  return http.request<ShopVO>({
    method: "post",
    url: `/product/shops/${id}/capacity-refreshes`
  });
}

export function checkShopApis(id: string): Promise<ShopVO> {
  return http.request<ShopVO>({ method: "post", url: `/product/shops/${id}/api-checks` });
}
