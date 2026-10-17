import { http } from "../http";
import type { PageResult, SheetMissingVO, SheetReadResultVO, SheetSourceVO } from "../types";

/** Create / edit body; `sheet` is the sheet address or its bare ID. */
export interface SheetSourceRequest {
  sourceName: string;
  sheet: string;
  sheetTitle: string;
  headerRow: number;
  linkColumn: string;
  nameColumn: string;
  priceColumn: string;
  categoryColumn: string | null;
  imageColumn: string | null;
  defaultShopId: string | null;
  priceCurrency: string;
}

export function listSheetSources(): Promise<SheetSourceVO[]> {
  return http.request<SheetSourceVO[]>({ method: "get", url: "/product/sheet-sources" });
}

export function createSheetSource(request: SheetSourceRequest): Promise<SheetSourceVO> {
  return http.request<SheetSourceVO>({
    method: "post",
    url: "/product/sheet-sources",
    data: request
  });
}

export function editSheetSource(id: string, request: SheetSourceRequest): Promise<SheetSourceVO> {
  return http.request<SheetSourceVO>({
    method: "put",
    url: `/product/sheet-sources/${id}`,
    data: request
  });
}

export function updateSheetSourceStatus(id: string, status: 0 | 1): Promise<null> {
  return http.request<null>({
    method: "put",
    url: `/product/sheet-sources/${id}/status`,
    data: { status }
  });
}

export function deleteSheetSource(id: string): Promise<null> {
  return http.request<null>({ method: "delete", url: `/product/sheet-sources/${id}` });
}

/** Reads the sheet now; the products are written by the returned import task. */
export function readSheetSource(id: string): Promise<SheetReadResultVO> {
  return http.request<SheetReadResultVO>({
    method: "post",
    url: `/product/sheet-sources/${id}/reads`
  });
}

export function listMissingProducts(
  id: string,
  page: number,
  size: number
): Promise<PageResult<SheetMissingVO>> {
  return http.request<PageResult<SheetMissingVO>>({
    method: "get",
    url: `/product/sheet-sources/${id}/missing-products`,
    params: { page, size }
  });
}
