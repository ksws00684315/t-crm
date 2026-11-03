import { http } from "../http";
import type { DownloadedFile } from "@/utils/http";

export interface ProductImportVO {
  taskId: string;
}

export const IMPORT_MAX_BYTES = 20 * 1024 * 1024;

export function downloadProductTemplate(): Promise<DownloadedFile> {
  return http.download({ method: "get", url: "/product/product-imports/template" });
}

export function uploadProductImport(file: File): Promise<ProductImportVO> {
  const form = new FormData();
  form.append("file", file);
  return http.request<ProductImportVO>({
    method: "post",
    url: "/product/product-imports",
    data: form
  });
}
