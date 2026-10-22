import { http } from "../http";
import type { PageResult } from "../types";

export interface CopyJobQuery {
  page?: number;
  size?: number;
  jobCode?: string;
  targetChannel?: string;
  status?: string;
}

export interface CopyJobVO {
  id: string;
  jobCode: string;
  targetChannel: string;
  status: string;
  totalCount: number;
  successCount: number;
  failCount: number;
  errorMessage: string | null;
  createTime: string;
  finishTime: string | null;
  kolId: string | null;
  kolCode: string | null;
  kolName: string | null;
  targetShopCode: string | null;
  targetShopName: string | null;
}

export interface CopyJobItemVO {
  seq: number;
  sourceChannel: string;
  sourceProductId: string;
  sourceShopId: string | null;
  sourceShopName: string | null;
  sourceMerchantNo: string | null;
  targetChannel: string;
  targetShopId: string | null;
  targetShopName: string | null;
  targetItemId: string | null;
  targetItemUrl: string | null;
  status: string;
  errorMessage: string | null;
}

export function listCopyJobs(query: CopyJobQuery): Promise<PageResult<CopyJobVO>> {
  return http.request<PageResult<CopyJobVO>>({
    method: "get",
    url: "/product/copy-jobs",
    params: query
  });
}

export function listCopyJobItems(
  id: string,
  query: { page: number; size: number }
): Promise<PageResult<CopyJobItemVO>> {
  return http.request<PageResult<CopyJobItemVO>>({
    method: "get",
    url: `/product/copy-jobs/${id}/items`,
    params: query
  });
}
