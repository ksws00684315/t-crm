import { http } from "../http";
import type { DownloadedFile } from "@/utils/http";
import type { DisplayRelationStatus, KolDetailVO, KolVO, PageResult, ReviewStatus } from "../types";

/**
 * `KolQuery`; omitted fields mean "no filter". The three search fields combine with AND
 * (1018 row 12, combined query per the 2026-10-20 review); `bdUserId` is ignored for a BD.
 */
export interface KolQuery {
  page?: number;
  size?: number;
  keyword?: string;
  kolCode?: string;
  name?: string;
  email?: string;
  bdUserId?: string;
  reviewStatus?: ReviewStatus;
  relationStatus?: DisplayRelationStatus;
  loginStatus?: "FROZEN" | "NORMAL_OR_ABNORMAL";
}

/** `SocialAccountRequest`. At most one account may set `primary`. */
export interface SocialAccountRequest {
  platform: string;
  handle: string;
  url?: string | null;
  followers?: string | null;
  primary: boolean;
}

/** Ordinary edit body (task 28): profile fields only; email is read-only in the UI. */
export interface EditKolRequest {
  name: string;
  /** Null clears the country. */
  country: string | null;
  socialAccounts: SocialAccountRequest[];
  remark: string | null;
  whatsapp: string | null;
  phone: string | null;
}

export interface ReviewKolRequest {
  name: string;
  phone: string | null;
  whatsapp: string | null;
  primaryPlatform: string | null;
  primaryHandle: string | null;
  country: string | null;
  remark: string | null;
}

/** `GET` / `POST /kol/register-code`. `url` is the full creator-portal link. */
export interface RegisterCodeVO {
  code: string;
  url: string;
}

/** Create body still carries email and the fields that seed promotion config. */
export interface CreateKolRequest {
  name: string;
  email: string;
  country: string | null;
  socialAccounts: SocialAccountRequest[];
  defaultRate: string | null;
  remark: string | null;
  whatsapp: string | null;
  phone: string | null;
  linkTemplateId: string | null;
  inviteCode: string | null;
  sheetUrl: string | null;
  /** Ignored for a BD (the KOL is always assigned to them). */
  bdUserId: string | null;
}

/** Accepted import (HTTP 202). */
export interface KolImportVO {
  taskId: string;
}

/** Upload limit, same as the backend (spec 06 section 3). */
export const IMPORT_MAX_BYTES = 20 * 1024 * 1024;

export function listKols(query: KolQuery): Promise<PageResult<KolVO>> {
  return http.request<PageResult<KolVO>>({ method: "get", url: "/kol/kols", params: query });
}

/** Remaining days for enabled automatic cleanup, keyed by creator id. */
export function getCleanupRemaining(ids: string[]): Promise<Record<string, number>> {
  return http.request<Record<string, number>>({
    method: "get",
    url: "/kol/kols/cleanup-remaining",
    params: { ids: ids.join(",") }
  });
}

export function getKol(id: string): Promise<KolDetailVO> {
  return http.request<KolDetailVO>({ method: "get", url: `/kol/kols/${id}` });
}

export function createKol(request: CreateKolRequest): Promise<KolVO> {
  return http.request<KolVO>({ method: "post", url: "/kol/kols", data: request });
}

export function editKol(id: string, request: EditKolRequest): Promise<null> {
  return http.request<null>({ method: "put", url: `/kol/kols/${id}`, data: request });
}

export function reviewKol(id: string, request: ReviewKolRequest): Promise<null> {
  return http.request<null>({ method: "post", url: `/kol/kols/${id}/review`, data: request });
}

export function updateKolStatus(id: string, status: 0 | 1): Promise<null> {
  return http.request<null>({ method: "put", url: `/kol/kols/${id}/status`, data: { status } });
}

export function downloadKolTemplate(): Promise<DownloadedFile> {
  return http.download({ method: "get", url: "/kol/kol-imports/template" });
}

/** Resends the activation email. 409 `KOL_ALREADY_ACTIVATED` when the account is already active. */
export function inviteKol(id: string): Promise<null> {
  return http.request<null>({ method: "post", url: `/kol/kols/${id}/invite` });
}

/** Current registration link. The backend creates one when the caller has none. */
export function getRegisterCode(): Promise<RegisterCodeVO> {
  return http.request<RegisterCodeVO>({ method: "get", url: "/kol/register-code" });
}

/** Revokes the current registration link and returns a new one. */
export function rotateRegisterCode(): Promise<RegisterCodeVO> {
  return http.request<RegisterCodeVO>({ method: "post", url: "/kol/register-code" });
}

export function uploadKolImport(file: File): Promise<KolImportVO> {
  const form = new FormData();
  form.append("file", file);
  return http.request<KolImportVO>({
    method: "post",
    url: "/kol/kol-imports",
    // The http interceptor drops the JSON default for FormData; the browser sets the boundary.
    data: form,
    timeout: 120000
  });
}
