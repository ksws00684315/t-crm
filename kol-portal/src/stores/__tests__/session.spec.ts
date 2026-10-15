import { beforeEach, describe, expect, it, vi } from "vitest";
import { createPinia, setActivePinia } from "pinia";
import { useSessionStore } from "../session";
import { loadSession } from "@/utils/session";

beforeEach(() => {
  localStorage.clear();
  setActivePinia(createPinia());
});
describe("creator session", () => {
  it("restores profile, token and expiration after reload", () => {
    const kol = {
      kolCode: "TEST",
      name: "Test Creator",
      email: "test@example.test",
      country: null,
      tenantName: "Test Company"
    };
    useSessionStore().acceptSession({
      tokenName: "X-Kol-Token",
      token: "test-session",
      expiresIn: 3600,
      kol
    });
    setActivePinia(createPinia());
    const restored = useSessionStore();
    expect(restored.getToken()).toBe("test-session");
    expect(restored.kol).toEqual(kol);
    expect(loadSession()?.kol).toEqual(kol);
    restored.clearSession();
    expect(restored.getToken()).toBeNull();
    expect(restored.kol).toBeNull();
    expect(loadSession()).toBeNull();
  });
  it("checks expiration on every navigation or request", () => {
    const now = Date.now();
    const store = useSessionStore();
    store.setSession({ token: "test", expiresAt: now + 1000 });
    expect(store.getToken()).toBe("test");
    vi.spyOn(Date, "now").mockReturnValue(now + 1000);
    expect(store.getToken()).toBeNull();
  });
});
