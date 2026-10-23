import { http } from "./http";

export interface KolProfile {
  kolCode: string;
  name: string;
  email: string;
  country: string | null;
  tenantName: string;
  systemId?: string;
  primaryPlatform?: string | null;
  primaryHandle?: string | null;
  reviewStatus?: "PENDING" | "APPROVED" | null;
  loginStatus?: "NORMAL" | "FROZEN" | "ABNORMAL" | null;
  relationStatus?: "PENDING_PROMOTION" | "PROMOTING" | "INVALID" | null;
  commissionRate?: string | null;
  rateEffectiveAt?: string | null;
  whatsapp?: string | null;
  phone?: string | null;
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

const demoMode = import.meta.env.DEV && import.meta.env.MODE === "demo";
let demoProfile: KolProfile = {
  kolCode: "KOL-DEMO-0821",
  systemId: "KOL-DEMO-0821",
  name: "林间风",
  email: "creator.demo@example.test",
  country: "CN",
  tenantName: "Spark Home",
  primaryPlatform: "TikTok",
  primaryHandle: "@linjianfeng"
};

export const login = (data: { email: string; password: string }) =>
  http.request<AuthSession | CompanySelection>({ method: "POST", url: "/auth/login", data });
export const selectCompany = (data: { selectionToken: string; tenantId: string }) =>
  http.request<AuthSession>({ method: "POST", url: "/auth/login/select", data });
export const logout = () =>
  demoMode ? Promise.resolve(null) : http.request<null>({ method: "POST", url: "/auth/logout" });
export const activate = (data: { token: string; password: string }) =>
  http.request<AuthSession>({ method: "POST", url: "/auth/activate", data });
export const forgotPassword = (data: { email: string }) =>
  http.request<null>({ method: "POST", url: "/auth/forgot", data });
export const resetPassword = (data: { token: string; password: string }) =>
  http.request<null>({ method: "POST", url: "/auth/reset", data });
export const getMe = () =>
  demoMode ? Promise.resolve(demoProfile) : http.request<KolProfile>({ method: "GET", url: "/me" });
export const updateMe = (data: {
  name: string;
  primaryPlatform: string | null;
  primaryHandle: string | null;
}) => {
  if (demoMode) {
    demoProfile = { ...demoProfile, ...data };
    return Promise.resolve(demoProfile);
  }
  return http.request<KolProfile>({ method: "PUT", url: "/me", data });
};
export const changePassword = (data: { oldPassword: string; newPassword: string }) =>
  demoMode
    ? Promise.resolve({
        tokenName: "X-Kol-Token",
        token: "demo-token",
        expiresIn: 3600,
        kol: demoProfile
      })
    : http.request<AuthSession>({ method: "PUT", url: "/me/password", data });
