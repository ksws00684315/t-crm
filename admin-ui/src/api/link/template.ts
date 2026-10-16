import { http } from "../http";
import type { LinkTemplateVO } from "../types";

/** `CreateLinkTemplateRequest`. The code cannot change after create. */
export interface CreateLinkTemplateRequest {
  templateCode: string;
  templateName: string;
  templateUrl: string;
  sortOrder: number;
}

/** `EditLinkTemplateRequest`. The code is not accepted. */
export interface EditLinkTemplateRequest {
  templateName: string;
  templateUrl: string;
  sortOrder: number;
}

/** Every template of the company, disabled included, ordered by sort then id. */
export function listLinkTemplates(): Promise<LinkTemplateVO[]> {
  return http.request<LinkTemplateVO[]>({ method: "get", url: "/link/templates" });
}

export function createLinkTemplate(request: CreateLinkTemplateRequest): Promise<LinkTemplateVO> {
  return http.request<LinkTemplateVO>({ method: "post", url: "/link/templates", data: request });
}

export function editLinkTemplate(id: string, request: EditLinkTemplateRequest): Promise<null> {
  return http.request<null>({ method: "put", url: `/link/templates/${id}`, data: request });
}

export function updateLinkTemplateStatus(id: string, status: 0 | 1): Promise<null> {
  return http.request<null>({
    method: "put",
    url: `/link/templates/${id}/status`,
    data: { status }
  });
}
