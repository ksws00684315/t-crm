import { http } from "../http";
import type { DownloadedFile } from "@/utils/http";
import type { PageResult, ProductVO } from "../types";

export interface ProductQuery {
  page?: number;
  size?: number;
  keyword?: string;
  shopId?: string;
  status?: 0 | 1;
  category?: string;
}

export interface EditProductRequest {
  name: string;
  category: string | null;
  imageUrl: string | null;
  price: string;
  rate: string | null;
}

export interface ProductImportVO {
  taskId: string;
}

export const IMPORT_MAX_BYTES = 20 * 1024 * 1024;

export function listProducts(query: ProductQuery): Promise<PageResult<ProductVO>> {
  return http.request<PageResult<ProductVO>>({
    method: "get",
    url: "/product/products",
    params: query
  });
}

export function getProduct(id: string): Promise<ProductVO> {
  return http.request<ProductVO>({ method: "get", url: `/product/products/${id}` });
}

export function editProduct(id: string, request: EditProductRequest): Promise<null> {
  return http.request<null>({ method: "put", url: `/product/products/${id}`, data: request });
}

export function updateProductStatus(id: string, status: 0 | 1): Promise<null> {
  return http.request<null>({
    method: "put",
    url: `/product/products/${id}/status`,
    data: { status }
  });
}

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
