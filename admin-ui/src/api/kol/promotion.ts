import { http } from "../http";
import type { PageResult, RelationStatus } from "../types";

export function updateRelationStatus(id: string, status: RelationStatus): Promise<null> {
  return http.request<null>({
    method: "put",
    url: `/kol/kols/${id}/relation-status`,
    data: { status }
  });
}

export interface PromotionPlatform {
  templateId: string;
  address: string;
}

export interface PromotionConfigVO {
  defaultRate: string | null;
  platforms: Array<PromotionPlatform & { templateName: string }>;
  inviteCode: string | null;
  jumpTemplateId: string | null;
  sheetUrl: string | null;
  remark: string | null;
}

export type PromotionConfigRequest = Omit<PromotionConfigVO, "platforms"> & {
  platforms: PromotionPlatform[];
};

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

export interface PromotionRecordVO {
  changeType: "CONFIG" | "STATUS" | "REVIEW";
  versionNo: number | null;
  effectiveTime: string | null;
  before: Record<string, unknown> | null;
  after: Record<string, unknown> | null;
  remark: string | null;
  operatorName: string | null;
  createTime: string;
}

export function listPromotionRecords(id: string, query: { page: number; size: number }) {
  return http.request<PageResult<PromotionRecordVO>>({
    method: "get",
    url: `/kol/kols/${id}/promotion-records`,
    params: query
  });
}

export interface ClaimableKolVO {
  id: string;
  kolCode: string;
  name: string;
  maskedEmail: string;
  displayRelationStatus: "PENDING_PROMOTION" | "PROMOTING" | "INVALID" | "PENDING_UPDATE";
}

export function findClaimableKol(keyword: string) {
  return http.request<ClaimableKolVO | null>({
    method: "get",
    url: "/kol/claimable",
    params: { keyword }
  });
}

export function claimKol(id: string): Promise<null> {
  return http.request<null>({ method: "post", url: `/kol/kols/${id}/claim` });
}

export interface CleanupConfigVO {
  enabled: boolean;
  idleDays: number;
}

export function getCleanupConfig() {
  return http.request<CleanupConfigVO>({ method: "get", url: "/kol/cleanup-config" });
}

export function saveCleanupConfig(config: CleanupConfigVO) {
  return http.request<null>({ method: "put", url: "/kol/cleanup-config", data: config });
}

export interface CleanupPreviewVO {
  id: string;
  kolCode: string;
  name: string;
  bdUserName: string | null;
  lastOrderAt: string | null;
  idleSince: string;
}

export function listCleanupPreview(query: { page: number; size: number }) {
  return http.request<PageResult<CleanupPreviewVO>>({
    method: "get",
    url: "/kol/cleanup-preview",
    params: query
  });
}

export interface CleanupItemVO {
  id: string;
  kolCode: string;
  shopId: string;
  shopName: string | null;
  weidianItemId: string;
  status: "PENDING" | "SUCCESS" | "FAILED";
  trigger: "AUTO" | "MANUAL";
  reason: string | null;
  failReason: string | null;
  cleanupTime: string;
  finishTime: string | null;
}

export function listCleanupItems(query: {
  kolCode?: string;
  status?: CleanupItemVO["status"];
  page: number;
  size: number;
}) {
  return http.request<PageResult<CleanupItemVO>>({
    method: "get",
    url: "/kol/cleanup-items",
    params: query
  });
}

export function retryCleanupItem(id: string) {
  return http.request<null>({ method: "post", url: `/kol/cleanup-items/${id}/retry` });
}

export function cleanupKol(id: string, reason: string) {
  return http.request<null>({ method: "post", url: `/kol/kols/${id}/cleanup`, data: { reason } });
}
