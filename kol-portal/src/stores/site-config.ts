import { ref } from "vue";
import { getKolSiteConfig } from "@/api/site-config";

export const siteName = ref("SparkTrack");
let refreshTimer: number | undefined;

export async function loadSiteConfig(): Promise<void> {
  try {
    siteName.value = (await getKolSiteConfig()).siteName || "SparkTrack";
  } catch {
    siteName.value = "SparkTrack";
  }
}

export function startSiteConfigRefresh(): void {
  if (refreshTimer !== undefined) return;
  refreshTimer = window.setInterval(() => void loadSiteConfig(), 2_000);
}
