import { afterEach, describe, expect, it, vi } from "vitest";
import { http } from "../http";

vi.mock("../http", () => ({ http: { request: vi.fn() } }));

afterEach(() => {
  vi.unstubAllEnvs();
  vi.clearAllMocks();
  vi.resetModules();
});

describe("preview site configuration", () => {
  it("does not send the demo session to a live backend that can invalidate it", async () => {
    vi.stubEnv("DEV", true);
    vi.stubEnv("MODE", "demo");
    const { getKolSiteConfig } = await import("../site-config");
    await expect(getKolSiteConfig()).resolves.toEqual({ siteName: "SparkTrack" });
    expect(http.request).not.toHaveBeenCalled();
  });

  it.each([
    [false, "demo"],
    [true, "development"]
  ])("loads live configuration when DEV=%s and MODE=%s", async (dev, mode) => {
    vi.stubEnv("DEV", dev);
    vi.stubEnv("MODE", mode);
    vi.mocked(http.request).mockResolvedValue({ siteName: "Configured brand" });
    const { getKolSiteConfig } = await import("../site-config");
    await expect(getKolSiteConfig()).resolves.toEqual({ siteName: "Configured brand" });
    expect(http.request).toHaveBeenCalledWith({ url: "/public/site-config", method: "get" });
  });
});
