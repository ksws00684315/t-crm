import { http } from "../http";
import type { PageResult } from "../types";

export interface PromotionPlatform {
  templateId: string;
  address: string;
}

export interface PromotionConfigVO {
  defaultRate: string | null;
  platforms: Array<PromotionPlatform & { templateName: string | null }>;
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
  changeType: "CONFIG" | "STATUS" | "REVIEW" | "AUTO_CLEANUP";
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

/**
 * Per-creator auto cleanup (task 33 §3.2): `days` null turns it off, 1–365 otherwise. The
 * company-level configuration and the cleanup-record queries are gone with the old page.
 */
export function updateAutoCleanup(id: string, days: number | null): Promise<null> {
  return http.request<null>({
    method: "put",
    url: `/kol/kols/${id}/auto-cleanup`,
    data: { days }
  });
}

/** Immediate cleanup; deletes the creator's Weidian product links in the background. */
export function cleanupKol(id: string, reason: string) {
  return http.request<null>({ method: "post", url: `/kol/kols/${id}/cleanup`, data: { reason } });
}
