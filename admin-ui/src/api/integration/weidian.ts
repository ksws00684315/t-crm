import { http } from "../http";

export interface WeidianAppVO {
  appKey: string | null;
  secretConfigured: boolean;
  updateTime: string | null;
  callbackUrl: string;
}

export interface WeidianAppRequest {
  appKey: string;
  appSecret: string;
}

export interface WeidianAuthorizationVO {
  authorizeUrl: string;
}

/** `GET /integration/weidian/app`; super administrator only. */
export function getWeidianApp(): Promise<WeidianAppVO> {
  return http.request<WeidianAppVO>({ method: "get", url: "/integration/weidian/app" });
}

/** Replaces the company-level service app credentials. The secret is never returned. */
export function saveWeidianApp(request: WeidianAppRequest): Promise<void> {
  return http.request<void>({
    method: "put",
    url: "/integration/weidian/app",
    data: request
  });
}

export function startWeidianAuthorization(shopId: string): Promise<WeidianAuthorizationVO> {
  return http.request<WeidianAuthorizationVO>({
    method: "post",
    url: `/integration/weidian/shops/${shopId}/authorizations`
  });
}
