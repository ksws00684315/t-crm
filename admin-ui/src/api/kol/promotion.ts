/*
 * Promotion status, configuration, records, claiming and cleanup settings (task 25, ADR-023).
 * The paths and payloads follow the contract in the 25a prompt §5 and must not drift from it.
 */

import { http } from "../http";
import type { PageResult } from "../types";
import type { DisplayStatus, PromotionStatus } from "../types";

/** Status change: any target is allowed; `PROMOTING` requires a complete configuration. */
export function updatePromotionStatus(id: string, status: PromotionStatus): Promise<null> {
  return http.request<null>({
    method: "put",
    url: `/kol/kols/${id}/promotion-status`,
    data: { status }
  });
}

/** `GET /kol/kols/{id}/promotion-config`. `remark` is the note of the latest change. */
export interface PromotionConfigVO {
  /** Fraction with 4 decimals ("0.1550") or null, same as `KolVO.defaultRate`. */
  defaultRate: string | null;
  platformTemplateIds: string[];
  landingUrl: string | null;
  inviteCode: string | null;
  sheetUrl: string | null;
  /** Jump platform template id stored in crm_kol.link_template_id. */
  jumpTemplateId: string | null;
  remark: string | null;
}

/** `PUT` body; `remark` is the note written onto this change only. */
export interface PromotionConfigRequest {
  defaultRate: string | null;
  platformTemplateIds: string[];
  landingUrl: string | null;
  inviteCode: string | null;
  sheetUrl: string | null;
  jumpTemplateId: string | null;
  remark: string | null;
}

export function getPromotionConfig(id: string): Promise<PromotionConfigVO> {
  return http.request<PromotionConfigVO>({
    method: "get",
    url: `/kol/kols/${id}/promotion-config`
  });
}

export function savePromotionConfig(id: string, request: PromotionConfigRequest): Promise<null> {
  return http.request<null>({
    method: "put",
    url: `/kol/kols/${id}/promotion-config`,
    data: request
  });
}

/**
 * One promotion record: a configuration save (`CONFIG`) or a status change (`STATUS`). `before`
 * and `after` hold the promotion fields only, exactly as the backend wrote them.
 */
export interface PromotionRecordVO {
  changeType: "CONFIG" | "STATUS";
  before: Record<string, unknown> | null;
  after: Record<string, unknown> | null;
  remark: string | null;
  operatorName: string | null;
  createTime: string;
}

export function listPromotionRecords(
  id: string,
  query: { page: number; size: number }
): Promise<PageResult<PromotionRecordVO>> {
  return http.request<PageResult<PromotionRecordVO>>({
    method: "get",
    url: `/kol/kols/${id}/promotion-records`,
    params: query
  });
}

/** `GET /kol/claimable`: the one unassigned creator matching the keyword, or null. */
export interface ClaimableKolVO {
  id: string;
  kolCode: string;
  name: string;
  maskedEmail: string;
  displayStatus: DisplayStatus;
}

export function findClaimableKol(keyword: string): Promise<ClaimableKolVO | null> {
  return http.request<ClaimableKolVO | null>({
    method: "get",
    url: "/kol/claimable",
    params: { keyword }
  });
}

/** Claims the creator; 409 `KOL_ALREADY_CLAIMED` when another BD won the race. */
export function claimKol(id: string): Promise<null> {
  return http.request<null>({ method: "post", url: `/kol/kols/${id}/claim` });
}

export interface CleanupConfigVO {
  enabled: boolean;
  idleDays: number;
}

export function getCleanupConfig(): Promise<CleanupConfigVO> {
  return http.request<CleanupConfigVO>({ method: "get", url: "/kol/cleanup-config" });
}

export function saveCleanupConfig(config: CleanupConfigVO): Promise<null> {
  return http.request<null>({ method: "put", url: "/kol/cleanup-config", data: config });
}

/** Creators the next cleanup run would remove under the saved configuration. */
export interface CleanupPreviewVO {
  id: string;
  kolCode: string;
  name: string;
  bdUserName: string | null;
  lastOrderAt: string | null;
  idleSince: string;
}

export function listCleanupPreview(query: {
  page: number;
  size: number;
}): Promise<PageResult<CleanupPreviewVO>> {
  return http.request<PageResult<CleanupPreviewVO>>({
    method: "get",
    url: "/kol/cleanup-preview",
    params: query
  });
}
