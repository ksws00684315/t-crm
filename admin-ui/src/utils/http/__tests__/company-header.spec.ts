import type { AxiosAdapter, InternalAxiosRequestConfig } from "axios";
import { describe, expect, it, vi } from "vitest";
import { COMPANY_HEADER, createHttp } from "..";

function setup(status: number, data: unknown, companyId: string | null) {
  const seen: InternalAxiosRequestConfig[] = [];
  const adapter: AxiosAdapter = async config => {
    seen.push(config);
    return { data, status, statusText: "", headers: {}, config };
  };
  const onCompanySwitched = vi.fn();
  const onUnauthorized = vi.fn();
  const http = createHttp({
    getToken: () => "tok",
    getLanguage: () => "zh-CN",
    onUnauthorized,
    getCompanyId: () => companyId,
    onCompanySwitched,
    adapter
  });
  return { http, seen, onCompanySwitched, onUnauthorized };
}

const ok = { code: 0, msg: "Success", data: null, errorCode: null, traceId: "t" };
const failure = (status: number, errorCode: string) => ({
  code: status,
  msg: "x",
  data: null,
  errorCode,
  traceId: "t"
});

describe("X-Company-Id", () => {
  it("sends the shown company on every request", async () => {
    const { http, seen } = setup(200, ok, "42");
    await http.request({ url: "/kols" });
    await http.request({ method: "post", url: "/kols", data: {} });
    expect(COMPANY_HEADER).toBe("X-Company-Id");
    expect(seen.map(config => config.headers.get(COMPANY_HEADER))).toEqual(["42", "42"]);
  });

  it("is left out when there is no company (platform administrator, signed out)", async () => {
    const { http, seen } = setup(200, ok, null);
    await http.request({ url: "/platform/tenants" });
    expect(seen[0].headers.has(COMPANY_HEADER)).toBe(false);
  });

  it("is also sent on downloads", async () => {
    const { http, seen } = setup(200, new Blob(["a"]), "42");
    await http.download({ url: "/tasks/1/error-file" });
    expect(seen[0].headers.get(COMPANY_HEADER)).toBe("42");
  });
});

describe("409 AUTH_COMPANY_SWITCHED", () => {
  it("calls onCompanySwitched and still throws", async () => {
    const { http, onCompanySwitched, onUnauthorized } = setup(
      409,
      failure(409, "AUTH_COMPANY_SWITCHED"),
      "42"
    );
    const error = await http.request({ url: "/kols" }).catch((caught: unknown) => caught);
    expect(onCompanySwitched).toHaveBeenCalledTimes(1);
    expect(onCompanySwitched).toHaveBeenCalledWith(error);
    expect(onUnauthorized).not.toHaveBeenCalled();
  });

  it("leaves other conflicts to the caller", async () => {
    const { http, onCompanySwitched } = setup(409, failure(409, "USER_USERNAME_EXISTS"), "42");
    await http.request({ url: "/system/users" }).catch(() => undefined);
    expect(onCompanySwitched).not.toHaveBeenCalled();
  });

  it("is reported for downloads too", async () => {
    const body = new Blob([JSON.stringify(failure(409, "AUTH_COMPANY_SWITCHED"))]);
    const { http, onCompanySwitched } = setup(409, body, "42");
    await http.download({ url: "/x" }).catch(() => undefined);
    expect(onCompanySwitched).toHaveBeenCalledTimes(1);
  });
});
