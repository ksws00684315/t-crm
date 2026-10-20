import type { AuthSession } from "./auth";
import { http } from "./http";

/**
 * Registration after task 32 §2.5 / 33 §3.11: no e-mail verification code any more, and the
 * optional country / profile-URL fields are no longer sent. `bdUserName` is null when the code
 * belongs to the super administrator; the page then shows the tenant name.
 */
export interface RegisterPreview {
  bdUserName: string | null;
  tenantName: string | null;
}

export interface RegisterBody {
  code: string;
  email: string;
  password: string;
  name: string;
  phone: string | null;
  whatsapp: string | null;
  primaryPlatform: string | null;
  primaryHandle: string | null;
}

export const previewRegister = (code: string) =>
  http.request<RegisterPreview>({
    method: "GET",
    url: `/register/${encodeURIComponent(code)}`
  });

export const registerKol = (data: RegisterBody) =>
  http.request<AuthSession>({ method: "POST", url: "/register", data });
