import { ref } from "vue";
import { getAdminSiteConfig } from "@/api/system/site-config";

export const siteName = ref("SparkTrack");
export const uploadBaseUrl = ref("");
const loaded = ref(false);
let refreshTimer: number | undefined;

export function isSiteConfigLoaded(): boolean {
  return loaded.value;
}

export async function loadSiteConfig(): Promise<void> {
  try {
    const config = await getAdminSiteConfig();
    siteName.value = config.siteName || "SparkTrack";
    uploadBaseUrl.value = config.uploadBaseUrl || "";
  } catch {
    siteName.value = "SparkTrack";
    uploadBaseUrl.value = "";
  } finally {
    loaded.value = true;
  }
}

export function startSiteConfigRefresh(): void {
  if (refreshTimer !== undefined) return;
  refreshTimer = window.setInterval(() => void loadSiteConfig(), 2_000);
}
