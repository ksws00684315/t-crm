import { http } from "../http";
import type { PageResult } from "../types";

/** `BdTransferVO` row. Null BD names and null `kolCode`/`kolName` mean unassigned/deleted. */
export interface BdTransferVO {
  id: string;
  kolId: string;
  kolCode: string | null;
  kolName: string | null;
  fromBdUserId: string | null;
  fromBdUserName: string | null;
  toBdUserId: string | null;
  toBdUserName: string | null;
  /** ISO 8601 UTC instant. */
  effectiveAt: string;
  status: BdTransferStatus;
  executedAt: string | null;
  reason: string | null;
  cancelReason: string | null;
  createTime: string;
}

export type BdTransferStatus = "SCHEDULED" | "DONE" | "CANCELLED";

/** `BdTransferRequest`: exactly one of `kolIds` and `fromBdUserId`; null `toBdUserId` = company. */
export interface CreateBdTransferRequest {
  kolIds?: string[];
  fromBdUserId?: string;
  toBdUserId?: string | null;
  /** ISO 8601 UTC instant; omitted = immediately. */
  effectiveAt?: string;
  reason?: string;
}

/** `BdTransferResultVO`. */
export interface BdTransferResultVO {
  immediate: number;
  scheduled: number;
  skipped: number;
}

/** `BdTransferQuery`; omitted fields mean "no filter". */
export interface BdTransferQuery {
  page?: number;
  size?: number;
  status?: BdTransferStatus;
  kolCode?: string;
  fromBdUserId?: string;
  toBdUserId?: string;
}

/**
 * What the transfer dialog moves: selected creators (`kolIds`, toolbar batch or a single row) or
 * every creator of one BD (`fromBdUserId`, user-management entry). `count` is shown to the user
 * (the KOL-list entries derive it from the selection length).
 */
export interface TransferTarget {
  kolIds?: string[];
  fromBdUserId?: string;
  /** Display name of `fromBdUserId` (nickname preferred). */
  fromBdName?: string;
  count?: number;
}

export function listBdTransfers(query: BdTransferQuery): Promise<PageResult<BdTransferVO>> {
  return http.request<PageResult<BdTransferVO>>({
    method: "get",
    url: "/kol/bd-transfers",
    params: query
  });
}

/** 409 `KOL_TRANSFER_EXISTS` carries the conflicting KOL codes in `data`. */
export function createBdTransfer(request: CreateBdTransferRequest): Promise<BdTransferResultVO> {
  return http.request<BdTransferResultVO>({
    method: "post",
    url: "/kol/bd-transfers",
    data: request
  });
}

/** 409 `KOL_TRANSFER_NOT_SCHEDULED` when the record is no longer scheduled. */
export function cancelBdTransfer(id: string): Promise<null> {
  return http.request<null>({ method: "delete", url: `/kol/bd-transfers/${id}` });
}
