import { beforeEach, describe, expect, it, vi } from "vitest";
import { createPinia, setActivePinia } from "pinia";
import { useSessionStore } from "@/stores/session";
import { useLocaleStore } from "@/stores/locale";
import { http } from "../http";
import * as auth from "../auth";

describe("creator API contract", () => {
  beforeEach(() => {
    localStorage.clear();
    setActivePinia(createPinia());
  });
  it("uses the specified methods, paths and payloads without identity parameters", async () => {
    const requests: { method?: string; url?: string; data: unknown }[] = [];
    http.instance.defaults.adapter = async config => {
      requests.push({
        method: config.method,
        url: config.url,
        data: config.data ? JSON.parse(config.data) : undefined
      });
      expect(config.baseURL).toBe("/kol-api");
      return {
        status: 200,
        statusText: "",
        headers: {},
        config,
        data: { code: 0, msg: "Success", data: null, errorCode: null, traceId: "test" }
      };
    };
    await auth.login({ email: "test@example.test", password: "Test-password1" });
    await auth.selectCompany({ selectionToken: "selection-test", tenantId: "9007199254740993" });
    await auth.logout();
    await auth.activate({ token: "link-test", password: "Test-password1" });
    await auth.forgotPassword({ email: "test@example.test" });
    await auth.resetPassword({ token: "reset-test", password: "Test-password2" });
    await auth.getMe();
    await auth.changePassword({ oldPassword: "Test-password1", newPassword: "Test-password2" });
    expect(requests).toEqual([
      {
        method: "post",
        url: "/auth/login",
        data: { email: "test@example.test", password: "Test-password1" }
      },
      {
        method: "post",
        url: "/auth/login/select",
        data: { selectionToken: "selection-test", tenantId: "9007199254740993" }
      },
      { method: "post", url: "/auth/logout", data: undefined },
      {
        method: "post",
        url: "/auth/activate",
        data: { token: "link-test", password: "Test-password1" }
      },
      { method: "post", url: "/auth/forgot", data: { email: "test@example.test" } },
      {
        method: "post",
        url: "/auth/reset",
        data: { token: "reset-test", password: "Test-password2" }
      },
      { method: "get", url: "/me", data: undefined },
      {
        method: "put",
        url: "/me/password",
        data: { oldPassword: "Test-password1", newPassword: "Test-password2" }
      }
    ]);
  });
  it("stops sending a token after it expires while the page stays open", async () => {
    const store = useSessionStore();
    store.setSession({ token: "expiring-test-token", expiresAt: Date.now() + 1000 });
    expect(store.isLoggedIn).toBe(true);
    vi.spyOn(Date, "now").mockReturnValue(Date.now() + 2000);
    http.instance.defaults.adapter = async config => {
      expect(config.headers.has("X-Kol-Token")).toBe(false);
      return { status: 200, statusText: "", headers: {}, config, data: { code: 0, data: null } };
    };
    await auth.getMe();
  });

  it("sends the rotated token and the current language on the next request", async () => {
    const store = useSessionStore();
    store.setSession({ token: "old-test-token", expiresAt: Date.now() + 60000 });
    store.setSession({ token: "new-test-token", expiresAt: Date.now() + 60000 });
    useLocaleStore().setLocale("zh-CN");
    http.instance.defaults.adapter = async config => {
      expect(config.headers.get("X-Kol-Token")).toBe("new-test-token");
      expect(config.headers.get("Accept-Language")).toBe("zh-CN");
      return { status: 200, statusText: "", headers: {}, config, data: { code: 0, data: null } };
    };
    await auth.getMe();
  });
});
