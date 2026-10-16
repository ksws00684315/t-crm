import type { AuthSession } from "./auth";
import { http } from "./http";

export interface RegisterPreview {
  tenantName: string;
}

export interface RegisterBody {
  code: string;
  email: string;
  emailCode: string;
  password: string;
  whatsapp: string;
  primaryPlatform: string;
  primaryHandle: string;
  primaryUrl: string | null;
  name: string | null;
  country: string | null;
}

export const previewRegister = (code: string) =>
  http.request<RegisterPreview>({
    method: "GET",
    url: `/register/${encodeURIComponent(code)}`
  });

export const sendRegisterCode = (data: { code: string; email: string }) =>
  http.request<null>({ method: "POST", url: "/register/email-codes", data });

export const registerKol = (data: RegisterBody) =>
  http.request<AuthSession>({ method: "POST", url: "/register", data });
