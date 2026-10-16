import { http } from "./http";

export interface KolProfile {
  kolCode: string;
  name: string;
  email: string;
  country: string | null;
  tenantName: string;
  whatsapp?: string | null;
  sheetUrl?: string | null;
}

export interface AuthSession {
  tokenName: string;
  token: string;
  expiresIn: number;
  kol: KolProfile;
}

export interface CompanySelection {
  selectionToken: string;
  companies: { tenantId: string; tenantName: string }[];
}

export const login = (data: { email: string; password: string }) =>
  http.request<AuthSession | CompanySelection>({ method: "POST", url: "/auth/login", data });
export const selectCompany = (data: { selectionToken: string; tenantId: string }) =>
  http.request<AuthSession>({ method: "POST", url: "/auth/login/select", data });
export const logout = () => http.request<null>({ method: "POST", url: "/auth/logout" });
export const activate = (data: { token: string; password: string }) =>
  http.request<AuthSession>({ method: "POST", url: "/auth/activate", data });
export const forgotPassword = (data: { email: string }) =>
  http.request<null>({ method: "POST", url: "/auth/forgot", data });
export const resetPassword = (data: { token: string; password: string }) =>
  http.request<null>({ method: "POST", url: "/auth/reset", data });
export const getMe = () => http.request<KolProfile>({ method: "GET", url: "/me" });
export const changePassword = (data: { oldPassword: string; newPassword: string }) =>
  http.request<AuthSession>({ method: "PUT", url: "/me/password", data });
