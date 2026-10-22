import { http } from "../http";
import type { CopyTokenVO, CreatedCopyTokenVO, PageResult, ShopChannel } from "../types";

export type CopyTokenDisplayStatus = "ACTIVE" | "EXHAUSTED" | "EXPIRED" | "REVOKED";

export interface CopyTokenQuery {
  hint?: string;
  kolId?: string;
  targetChannel?: string;
  status?: CopyTokenDisplayStatus;
  page?: number;
  size?: number;
}

export interface CreateCopyTokenRequest {
  kolId: string;
  targetChannel: ShopChannel;
  quota: number;
  validMinutes: number;
  targetShopId?: string | null;
}

export function listCopyTokens(query: CopyTokenQuery): Promise<PageResult<CopyTokenVO>> {
  return http.request<PageResult<CopyTokenVO>>({
    method: "get",
    url: "/kol/copy-tokens",
    params: query
  });
}

export function createCopyToken(request: CreateCopyTokenRequest): Promise<CreatedCopyTokenVO> {
  return http.request<CreatedCopyTokenVO>({
    method: "post",
    url: "/kol/copy-tokens",
    data: request
  });
}

export function revealCopyToken(id: string): Promise<{ token: string }> {
  return http.request<{ token: string }>({
    method: "post",
    url: `/kol/copy-tokens/${id}/reveal`
  });
}

export function revokeCopyToken(id: string): Promise<null> {
  return http.request<null>({ method: "delete", url: `/kol/copy-tokens/${id}` });
}
