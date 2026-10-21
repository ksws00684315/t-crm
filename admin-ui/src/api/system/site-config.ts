import { http } from "../http";

export interface AdminSiteConfig {
  siteName: string;
  uploadBaseUrl: string;
}

export interface SystemConfigItem {
  key: string;
  group: string;
  type: string;
  value: string;
  defaultValue: string;
  overridden: boolean;
  min: number | null;
  max: number | null;
  nameKey: string;
}

export interface SystemConfigGroup {
  group: string;
  items: SystemConfigItem[];
}

export function getAdminSiteConfig(): Promise<AdminSiteConfig> {
  return http.request<AdminSiteConfig>({ url: "/public/site-config", method: "get" });
}

export function getSystemConfigs(): Promise<SystemConfigGroup[]> {
  return http.request<SystemConfigGroup[]>({
    url: "/platform/system-configs",
    method: "get"
  });
}

export function updateSystemConfig(key: string, value: string): Promise<void> {
  return http.request<void>({
    url: `/platform/system-configs/${encodeURIComponent(key)}`,
    method: "put",
    data: { value }
  });
}

export function resetSystemConfig(key: string): Promise<void> {
  return http.request<void>({
    url: `/platform/system-configs/${encodeURIComponent(key)}`,
    method: "delete"
  });
}
