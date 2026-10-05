import { http } from "./http";
import type { UserVO } from "./types";

export interface LoginRequest {
  username: string;
  password: string;
}

/** `LoginVO`. */
export interface LoginVO {
  tokenName: string;
  token: string;
  /** Seconds until the session expires. */
  expiresIn: number;
  user: UserVO;
}

export interface PasswordRequest {
  oldPassword: string;
  newPassword: string;
}

export function login(request: LoginRequest): Promise<LoginVO> {
  return http.request<LoginVO>({ method: "post", url: "/auth/login", data: request });
}

export function logout(): Promise<null> {
  return http.request<null>({ method: "post", url: "/auth/logout" });
}

export function fetchCurrentUser(): Promise<UserVO> {
  return http.request<UserVO>({ method: "get", url: "/auth/me" });
}

export function changePassword(request: PasswordRequest): Promise<null> {
  return http.request<null>({ method: "put", url: "/auth/password", data: request });
}
