import { http } from "../http";
import type { PageResult } from "../types";

/** `OperLogVO`. */
export interface OperLogVO {
  id: string;
  userId: string | null;
  username: string | null;
  module: string;
  action: string;
  method: string | null;
  requestUri: string | null;
  requestParams: string | null;
  result: string;
  errorMsg: string | null;
  ip: string | null;
  costMs: string | null;
  operTime: string;
}

export function listOperLogs(page: number, size: number): Promise<PageResult<OperLogVO>> {
  return http.request<PageResult<OperLogVO>>({
    method: "get",
    url: "/system/oper-logs",
    params: { page, size }
  });
}
