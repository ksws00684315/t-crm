import { http } from "../http";
import type { PageResult } from "../types";

/**
 * `OperLogVO` (task 33 §3.3). `summary` is the human-readable Chinese one-liner — BD transfers
 * list the creators, both BDs, the effective time and the reason there.
 */
export interface OperLogVO {
  id: string;
  module: string;
  action: string;
  operatorName: string | null;
  method: string | null;
  path: string | null;
  summary: string | null;
  success: boolean;
  operTime: string;
  ip: string | null;
}

/** `OperLogQuery`; omitted fields mean "no filter". `from`/`to` are ISO 8601 instants. */
export interface OperLogQuery {
  module?: string;
  keyword?: string;
  operator?: string;
  from?: string;
  to?: string;
}

export function listOperLogs(
  page: number,
  size: number,
  query: OperLogQuery = {}
): Promise<PageResult<OperLogVO>> {
  return http.request<PageResult<OperLogVO>>({
    method: "get",
    url: "/system/oper-logs",
    params: { page, size, ...query }
  });
}
