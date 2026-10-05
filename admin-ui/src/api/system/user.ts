import { http } from "../http";
import type { DataScope, PageResult, Role, UserVO } from "../types";

/** `UserQuery`; omitted fields mean "no filter". */
export interface UserQuery {
  page?: number;
  size?: number;
  username?: string;
  role?: Role;
  status?: 0 | 1;
}

/** `CreateUserRequest`. */
export interface CreateUserRequest {
  username: string;
  password: string;
  nickname: string;
  email?: string | null;
  role: Role;
  dataScope: DataScope;
}

/** `EditUserRequest`. */
export interface EditUserRequest {
  nickname: string;
  email?: string | null;
  role: Role;
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
