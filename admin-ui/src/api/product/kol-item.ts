import { http } from "../http";
import type { DownloadedFile } from "@/utils/http";
import type { KolItemVO, PageResult } from "../types";

export interface KolItemQuery {
  page?: number;
  size?: number;
  kolCode?: string;
  productCode?: string;
  shopId?: string;
  weidianItemId?: string;
  /** Default true on the backend; false includes historical mappings. */
  current?: boolean;
}

export interface KolItemImportVO {
  taskId: string;
}

export function listKolItems(query: KolItemQuery): Promise<PageResult<KolItemVO>> {
  return http.request<PageResult<KolItemVO>>({
    method: "get",
    url: "/product/kol-items",
    params: query
  });
}

export function downloadKolItemTemplate(): Promise<DownloadedFile> {
  return http.download({ method: "get", url: "/product/kol-item-imports/template" });
}

export function uploadKolItemImport(file: File): Promise<KolItemImportVO> {
  const form = new FormData();
  form.append("file", file);
  return http.request<KolItemImportVO>({
    method: "post",
    url: "/product/kol-item-imports",
    data: form
  });
}
