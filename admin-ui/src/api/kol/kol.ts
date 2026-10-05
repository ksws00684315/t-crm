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

/** `SocialAccountRequest`. */
export interface SocialAccountRequest {
  platform: string;
  handle: string;
  url?: string | null;
  followers?: string | null;
}

/** `EditKolRequest`. `defaultRate` is a fraction string with up to 4 decimals, or null. */
export interface EditKolRequest {
  name: string;
  email: string;
  country: string;
  socialAccounts: SocialAccountRequest[];
  defaultRate: string | null;
  remark: string | null;
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
