import { http } from "../http";

export interface WeidianAuthorizationVO {
  authorizeUrl: string;
}

export function startWeidianAuthorization(shopId: string): Promise<WeidianAuthorizationVO> {
  return http.request<WeidianAuthorizationVO>({
    method: "post",
    url: `/integration/weidian/shops/${shopId}/authorizations`
  });
}
