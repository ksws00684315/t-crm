import { http } from "./http";

export interface KolSiteConfig {
  siteName: string;
}

export function getKolSiteConfig(): Promise<KolSiteConfig> {
  if (import.meta.env.DEV && import.meta.env.MODE === "demo")
    return Promise.resolve({ siteName: "SparkTrack" });
  return http.request<KolSiteConfig>({ url: "/public/site-config", method: "get" });
}
