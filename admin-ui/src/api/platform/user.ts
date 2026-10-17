import { http } from "../http";
import type { CompanyRole, DataScope, PostTemplate } from "../types";

/** One membership of an account looked up by the platform administrator. */
export interface PlatformUserCompanyVO {
  tenantId: string;
  tenantName: string;
  role: CompanyRole;
  /** Membership status in that company: 1 enabled, 0 disabled. */
  status: 0 | 1;
}

/** `GET /platform/users?username=`: an account and every company it belongs to (ADR-022). */
export interface PlatformUserVO {
  id: string;
  username: string;
  nickname: string;
  /** Always present in the lookup result; null when the account has no email. */
  email: string | null;
  /** Whole account: 1 enabled, 0 disabled. */
  status: 0 | 1;
  companies: PlatformUserCompanyVO[];
}

/** `POST /platform/tenants/{id}/members`. Template and scope only apply to a sub-account. */
export interface AddTenantMemberRequest {
  userId: string;
  role: CompanyRole;
  postTemplate?: PostTemplate;
  /** Omitted: the post template's default scope. */
  dataScope?: DataScope;
}

export interface UpdatePlatformUserRequest {
  nickname: string;
  email?: string | null;
}

/** Exact username match; null when no account has that username. */
export function findPlatformUser(username: string): Promise<PlatformUserVO | null> {
  return http.request<PlatformUserVO | null>({
    method: "get",
    url: "/platform/users",
    params: { username }
  });
}

export function addTenantMember(tenantId: string, request: AddTenantMemberRequest): Promise<null> {
  return http.request<null>({
    method: "post",
    url: `/platform/tenants/${tenantId}/members`,
    data: request
  });
}

export function updatePlatformUser(
  userId: string,
  request: UpdatePlatformUserRequest
): Promise<null> {
  return http.request<null>({ method: "put", url: `/platform/users/${userId}`, data: request });
}

/** Signs the account out of every company. */
export function resetPlatformUserPassword(userId: string, password: string): Promise<null> {
  return http.request<null>({
    method: "put",
    url: `/platform/users/${userId}/password`,
    data: { password }
  });
}

/** Enables or disables the whole account in every company. */
export function updatePlatformUserStatus(userId: string, status: 0 | 1): Promise<null> {
  return http.request<null>({
    method: "put",
    url: `/platform/users/${userId}/status`,
    data: { status }
  });
}
