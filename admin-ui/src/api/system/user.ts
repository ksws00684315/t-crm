import { http } from "../http";
import type { CompanyRole, DataScope, PageResult, PostTemplate, UserVO } from "../types";

/** `UserQuery`; omitted fields mean "no filter". */
export interface UserQuery {
  page?: number;
  size?: number;
  username?: string;
  role?: CompanyRole;
  status?: 0 | 1;
}

/** `CreateUserRequest`. Permission fields are omitted when the account is a super administrator. */
export interface CreateUserRequest {
  username: string;
  password: string;
  nickname: string;
  email?: string | null;
  role: CompanyRole;
  postTemplate?: PostTemplate | null;
  dataScope?: DataScope;
  permissions?: string[];
}

/** `EditUserRequest`. Permissions are saved by `updateUserPermissions`, not here. */
export interface EditUserRequest {
  nickname: string;
  email?: string | null;
  role: CompanyRole;
  postTemplate?: PostTemplate | null;
  dataScope?: DataScope;
}

/** One row of `GET /system/permissions`. `group` may be a value this build does not know yet. */
export interface PermissionItemVO {
  code: string;
  group: string;
}

/** One post template. `code` is `OPERATOR` or `BD` in this build. */
export interface PermissionTemplateVO {
  code: string;
  permissions: string[];
  dataScope: DataScope;
}

/** `GET /system/permissions`. */
export interface PermissionCatalogVO {
  permissions: PermissionItemVO[];
  templates: PermissionTemplateVO[];
}

/** `GET /system/users/{id}/permissions`. */
export interface UserPermissionsVO {
  permissions: string[];
  dataScope: DataScope;
}

/** `PUT /system/users/{id}/permissions`. Replaces the whole set. */
export interface UpdateUserPermissionsRequest {
  permissions: string[];
  dataScope: DataScope;
}

export function listUsers(query: UserQuery): Promise<PageResult<UserVO>> {
  return http.request<PageResult<UserVO>>({
    method: "get",
    url: "/system/users",
    params: query
  });
}

export function createUser(request: CreateUserRequest): Promise<UserVO> {
  return http.request<UserVO>({ method: "post", url: "/system/users", data: request });
}

export function editUser(id: string, request: EditUserRequest): Promise<null> {
  return http.request<null>({ method: "put", url: `/system/users/${id}`, data: request });
}

export function getPermissionCatalog(): Promise<PermissionCatalogVO> {
  return http.request<PermissionCatalogVO>({ method: "get", url: "/system/permissions" });
}

export function getUserPermissions(id: string): Promise<UserPermissionsVO> {
  return http.request<UserPermissionsVO>({
    method: "get",
    url: `/system/users/${id}/permissions`
  });
}

export function updateUserPermissions(
  id: string,
  request: UpdateUserPermissionsRequest
): Promise<null> {
  return http.request<null>({
    method: "put",
    url: `/system/users/${id}/permissions`,
    data: request
  });
}

export function updateUserStatus(id: string, status: 0 | 1): Promise<null> {
  return http.request<null>({
    method: "put",
    url: `/system/users/${id}/status`,
    data: { status }
  });
}

export function resetUserPassword(id: string, password: string): Promise<null> {
  return http.request<null>({
    method: "put",
    url: `/system/users/${id}/password`,
    data: { password }
  });
}
