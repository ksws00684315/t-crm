import { http } from "./http";
import type { DeparturePlanVO, UserVO } from "./types";

export interface LoginRequest {
  username: string;
  password: string;
  /** Required while the backend has captchas enabled (every login, including reauth). */
  captchaId?: string;
  captchaCode?: string;
}

/** `CaptchaVO`. When `enabled` is false the other fields are null and no captcha is sent. */
export interface CaptchaVO {
  enabled: boolean;
  captchaId: string | null;
  /** PNG data URL. */
  image: string | null;
  /** Seconds until the captcha expires. */
  expiresIn: number;
}

export interface CaptchaAnswer {
  captchaId: string;
  captchaCode: string;
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

/** Issues a new single-use captcha (rate limited per client IP). */
export function createCaptcha(): Promise<CaptchaVO> {
  return http.request<CaptchaVO>({ method: "post", url: "/auth/captchas" });
}

export function logout(): Promise<null> {
  return http.request<null>({ method: "post", url: "/auth/logout" });
}

export function fetchCurrentUser(): Promise<UserVO> {
  return http.request<UserVO>({ method: "get", url: "/auth/me" });
}

/** Moves the session to another company of this account; answers like `/auth/me`. */
export function switchCompany(tenantId: string): Promise<UserVO> {
  return http.request<UserVO>({ method: "post", url: "/auth/company", data: { tenantId } });
}

/** `ResignationRequest`: a BD resigning about itself; it may not name an heir. */
export interface ResignationRequest {
  plannedLeaveAt: string;
  reason?: string | null;
}

/** The caller's own pending resignation; null when there is none (task 41). */
export function getOwnResignation(): Promise<DeparturePlanVO | null> {
  return http.request<DeparturePlanVO | null>({ method: "get", url: "/auth/resignation" });
}

export function submitResignation(request: ResignationRequest): Promise<DeparturePlanVO> {
  return http.request<DeparturePlanVO>({
    method: "post",
    url: "/auth/resignation",
    data: request
  });
}

export function cancelResignation(): Promise<null> {
  return http.request<null>({ method: "delete", url: "/auth/resignation" });
}

export function changePassword(request: PasswordRequest): Promise<null> {
  return http.request<null>({ method: "put", url: "/auth/password", data: request });
}
