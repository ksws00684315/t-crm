import { http } from "./http";
import { isDemoMode } from "@/utils/demo-mode";

export interface KolSiteConfig {
  siteName: string;
}

export function getKolSiteConfig(): Promise<KolSiteConfig> {
  if (isDemoMode) return Promise.resolve({ siteName: "SparkTrack" });
  return http.request<KolSiteConfig>({ url: "/public/site-config", method: "get" });
}
