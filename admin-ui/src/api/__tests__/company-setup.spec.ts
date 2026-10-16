import { ElMessage } from "element-plus";
import { createPinia, setActivePinia } from "pinia";
import { createMemoryHistory, createRouter } from "vue-router";
import { beforeEach, describe, expect, it, vi } from "vitest";
import type { InternalAxiosRequestConfig } from "axios";
import { routes } from "@/router/routes";
import { useUserStore } from "@/stores/user";
import { ApiError } from "@/utils/http/error";
import { saveSession } from "@/utils/session";
import { http } from "../http";
import { setupHttp } from "../setup";
import type { UserVO } from "../types";

const companyA: UserVO = {
  id: "7",
  username: "alice",
  nickname: "Alice",
  email: null,
  role: "SUPER_ADMIN",
  postTemplate: null,
  permissions: [],
  dataScope: "ALL",
  tenantId: "1",
  tenantName: "A 公司",
  status: 1,
  lastLoginTime: null,
  createTime: "2026-10-05T00:00:00Z",
  companies: [
    { tenantId: "1", tenantName: "A 公司", role: "SUPER_ADMIN" },
    { tenantId: "2", tenantName: "B 公司", role: "STAFF" }
  ]
};
const companyB: UserVO = { ...companyA, role: "STAFF", tenantId: "2", tenantName: "B 公司" };

const ok = (data: unknown) => ({ code: 0, msg: "ok", data, errorCode: null, traceId: "t" });
const switched = {
  code: 409,
  msg: "x",
  data: null,
  errorCode: "AUTH_COMPANY_SWITCHED",
  traceId: "t"
};

function signIn(user: UserVO) {
  setActivePinia(createPinia());
  saveSession({ token: "tok", expiresAt: Date.now() + 60_000, user });
  setActivePinia(createPinia());
  setupHttp(createRouter({ history: createMemoryHistory(), routes }));
  return useUserStore();
}

describe("company header source", () => {
  let seen: InternalAxiosRequestConfig[];

  beforeEach(() => {
    localStorage.clear();
    seen = [];
    http.instance.defaults.adapter = async config => {
      seen.push(config);
      return { data: ok(null), status: 200, statusText: "", headers: {}, config };
    };
  });

  it("sends the company of this tab, not the one another tab stored", async () => {
    signIn(companyA);
    // Another tab switched: localStorage now holds company B, this tab still shows A.
    saveSession({ token: "tok", expiresAt: Date.now() + 60_000, user: companyB });
    await http.request({ url: "/kols" });
    expect(seen[0].headers.get("X-Company-Id")).toBe("1");
  });

  it("sends nothing for the platform administrator", async () => {
    signIn({
      ...companyA,
      role: "PLATFORM_ADMIN",
      tenantId: null,
      tenantName: null,
      companies: []
    });
    await http.request({ url: "/platform/tenants" });
    expect(seen[0].headers.has("X-Company-Id")).toBe(false);
  });

  it("sends nothing when signed out", async () => {
    signIn(companyA).clearSession();
    await http.request({ url: "/auth/login" });
    expect(seen[0].headers.has("X-Company-Id")).toBe(false);
  });
});

describe("409 AUTH_COMPANY_SWITCHED", () => {
  let warning: ReturnType<typeof vi.spyOn>;
  beforeEach(() => {
    localStorage.clear();
    warning = vi.spyOn(ElMessage, "warning").mockImplementation(() => ({}) as never);
  });

  it("warns once, re-reads /auth/me once and keeps callers quiet", async () => {
    const store = signIn(companyA);
    let meCalls = 0;
    let releaseMe: () => void = () => {};
    http.instance.defaults.adapter = async config => {
      if (config.url === "/auth/me") {
        meCalls += 1;
        await new Promise<void>(resolve => (releaseMe = resolve));
        return { data: ok(companyB), status: 200, statusText: "", headers: {}, config };
      }
      return { data: switched, status: 409, statusText: "", headers: {}, config };
    };

    const errors = await Promise.all([
      http.request({ method: "post", url: "/kols" }).catch((error: unknown) => error),
      http.request({ method: "put", url: "/kols/1" }).catch((error: unknown) => error),
      http.request({ url: "/products" }).catch((error: unknown) => error)
    ]);
    await vi.waitFor(() => expect(meCalls).toBe(1));
    releaseMe();
    await vi.waitFor(() => expect(store.user?.tenantId).toBe("2"));

    expect(errors.every(error => error instanceof ApiError && error.handled)).toBe(true);
    expect(warning).toHaveBeenCalledTimes(1);
    expect(warning).toHaveBeenCalledWith("当前公司已在其他标签页切换，页面将刷新");
    expect(meCalls).toBe(1);
  });
});
