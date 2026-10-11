import { http } from "../http";

/** `TenantVO`; ids are strings and timestamps are ISO 8601 UTC instants. */
export interface TenantVO {
  id: string;
  tenantCode: string;
  name: string;
  status: 0 | 1;
  remark: string | null;
  createTime: string;
}

/** `TenantAdminVO`: a super administrator belonging to one company. */
export interface TenantAdminVO {
  id: string;
  username: string;
  nickname: string;
  email: string | null;
  status: 0 | 1;
  lastLoginTime: string | null;
  createTime: string;
}

export interface CreateTenantRequest {
  tenantCode: string;
  name: string;
  remark?: string | null;
}

export interface UpdateTenantRequest {
  name: string;
  remark?: string | null;
}

export interface CreateTenantAdminRequest {
  username: string;
  password: string;
  nickname: string;
  email?: string | null;
}

export function listTenants(): Promise<TenantVO[]> {
  return http.request<TenantVO[]>({ method: "get", url: "/platform/tenants" });
}

export function createTenant(request: CreateTenantRequest): Promise<TenantVO> {
  return http.request<TenantVO>({ method: "post", url: "/platform/tenants", data: request });
}

export function updateTenant(id: string, request: UpdateTenantRequest): Promise<null> {
  return http.request<null>({ method: "put", url: `/platform/tenants/${id}`, data: request });
}

export function updateTenantStatus(id: string, status: 0 | 1): Promise<null> {
  return http.request<null>({
    method: "put",
    url: `/platform/tenants/${id}/status`,
    data: { status }
  });
}

export function listTenantAdmins(tenantId: string): Promise<TenantAdminVO[]> {
  return http.request<TenantAdminVO[]>({
    method: "get",
    url: `/platform/tenants/${tenantId}/admins`
  });
}

export function createTenantAdmin(
  tenantId: string,
  request: CreateTenantAdminRequest
): Promise<TenantAdminVO> {
  return http.request<TenantAdminVO>({
    method: "post",
    url: `/platform/tenants/${tenantId}/admins`,
    data: request
  });
}

export function resetTenantAdminPassword(
  tenantId: string,
  userId: string,
  password: string
): Promise<null> {
  return http.request<null>({
    method: "put",
    url: `/platform/tenants/${tenantId}/admins/${userId}/password`,
    data: { password }
  });
}
