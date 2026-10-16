import { http } from "../http";
import type { GoogleCredentialVO } from "../types";

/** `GET /integration/google-credential`; super administrator only. */
export function getGoogleCredential(): Promise<GoogleCredentialVO> {
  return http.request<GoogleCredentialVO>({ method: "get", url: "/integration/google-credential" });
}

/** Replaces the service account with the content of its JSON key file. */
export function saveGoogleCredential(credentialJson: string): Promise<GoogleCredentialVO> {
  return http.request<GoogleCredentialVO>({
    method: "put",
    url: "/integration/google-credential",
    data: { credentialJson }
  });
}
