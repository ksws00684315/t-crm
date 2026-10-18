import { http } from "./http";

/** One row of the creator's item list (task 26). */
export interface KolItem {
  weidianItemId: string;
  itemName: string | null;
  imageUrl: string | null;
  transitUrl: string;
  createTime: string;
}

export interface KolItemPage {
  items: KolItem[];
  total: number | string;
  page: number;
  size: number;
}

export interface KolItemQuery {
  keyword?: string;
  page: number;
  size: number;
}

export const getKolItems = (params: KolItemQuery) =>
  http.request<KolItemPage>({ method: "GET", url: "/items", params });
