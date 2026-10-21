import { http } from "./http";

export interface KolSiteConfig {
  siteName: string;
}

export function getKolSiteConfig(): Promise<KolSiteConfig> {
  return http.request<KolSiteConfig>({ url: "/public/site-config", method: "get" });
}
