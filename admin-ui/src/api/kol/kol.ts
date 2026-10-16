import { http } from "../http";
import type { DownloadedFile } from "@/utils/http";
import type { KolDetailVO, KolVO, PageResult } from "../types";

/** `KolQuery`; omitted fields mean "no filter". The backend ignores `bdUserId` for a BD. */
export interface KolQuery {
  page?: number;
  size?: number;
  keyword?: string;
  country?: string;
  status?: 0 | 1;
  bdUserId?: string;
}

/** `SocialAccountRequest`. At most one account may set `primary`. */
export interface SocialAccountRequest {
  platform: string;
  handle: string;
  url?: string | null;
  followers?: string | null;
  primary: boolean;
}

/** `EditKolRequest`. `defaultRate` is a fraction string with up to 4 decimals, or null. */
export interface EditKolRequest {
  name: string;
  email: string;
  /** Null clears the country. */
  country: string | null;
  socialAccounts: SocialAccountRequest[];
  defaultRate: string | null;
  remark: string | null;
  whatsapp: string | null;
  linkTemplateId: string | null;
  inviteCode: string | null;
  sheetUrl: string | null;
}

/** `GET` / `POST /kol/register-code`. `url` is the full creator-portal link. */
export interface RegisterCodeVO {
  code: string;
  url: string;
  status: 0 | 1;
}

/** `CreateKolRequest`. `bdUserId` is ignored for a BD (the KOL is always assigned to them). */
export interface CreateKolRequest extends EditKolRequest {
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

export function getKol(id: string): Promise<KolDetailVO> {
  return http.request<KolDetailVO>({ method: "get", url: `/kol/kols/${id}` });
}

export function createKol(request: CreateKolRequest): Promise<KolVO> {
  return http.request<KolVO>({ method: "post", url: "/kol/kols", data: request });
}

export function editKol(id: string, request: EditKolRequest): Promise<null> {
  return http.request<null>({ method: "put", url: `/kol/kols/${id}`, data: request });
}

export function updateKolStatus(id: string, status: 0 | 1): Promise<null> {
  return http.request<null>({ method: "put", url: `/kol/kols/${id}/status`, data: { status } });
}

/** Changes the BD; null clears the assignment. */
export function changeKolBd(id: string, bdUserId: string | null): Promise<null> {
  return http.request<null>({ method: "put", url: `/kol/kols/${id}/bd-user`, data: { bdUserId } });
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
    // For FormData axios drops the JSON default so the browser sets multipart/form-data + boundary.
    data: form,
    timeout: 120000
  });
}
