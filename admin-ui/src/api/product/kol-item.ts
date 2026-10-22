import { http } from "../http";
import type { DownloadedFile } from "@/utils/http";
import type { KolItemDetailVO, KolItemVO, PageResult } from "../types";

export interface KolItemQuery {
  page?: number;
  size?: number;
  /** Exact link code. */
  linkCode?: string;
  /** Creator name (fuzzy) or code / email (exact). The page sends the selected creator's code. */
  kolKeyword?: string;
  /** Target shop id. */
  shopId?: string;
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

export function getKolItem(id: string): Promise<KolItemDetailVO> {
  return http.request<KolItemDetailVO>({
    method: "get",
    url: `/product/kol-items/${id}`
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
