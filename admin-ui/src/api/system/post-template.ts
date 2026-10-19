import { http } from "../http";
import type { DataScope, PostTemplate } from "../types";

/**
 * Post template of one company (task 33 §3.4): which permissions and data scope a newly created
 * account starts with. Super administrators only; existing accounts are never touched.
 */
export interface PostTemplateVO {
  postTemplate: PostTemplate;
  dataScope: DataScope;
  permissions: string[];
  /** True while the company still uses the code default. */
  isDefault: boolean;
}

export interface SavePostTemplateRequest {
  dataScope: DataScope;
  permissions: string[];
}

export function listPostTemplates(): Promise<PostTemplateVO[]> {
  return http.request<PostTemplateVO[]>({ method: "get", url: "/system/post-templates" });
}

export function savePostTemplate(
  postTemplate: PostTemplate,
  request: SavePostTemplateRequest
): Promise<null> {
  return http.request<null>({
    method: "put",
    url: `/system/post-templates/${postTemplate}`,
    data: request
  });
}

export function resetPostTemplate(postTemplate: PostTemplate): Promise<PostTemplateVO> {
  return http.request<PostTemplateVO>({
    method: "post",
    url: `/system/post-templates/${postTemplate}/reset`
  });
}
