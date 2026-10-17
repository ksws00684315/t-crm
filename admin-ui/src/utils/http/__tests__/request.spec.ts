import type { AxiosAdapter, InternalAxiosRequestConfig } from "axios";
import { describe, expect, it, vi } from "vitest";
import { createHttp, TOKEN_HEADER } from "..";
import { ApiError } from "../error";

interface Reply {
  status: number;
  data?: unknown;
  fail?: boolean;
}

function setup(reply: Reply, token: string | null = "tok-1") {
  const seen: InternalAxiosRequestConfig[] = [];
  const adapter: AxiosAdapter = async config => {
    seen.push(config);
    if (reply.fail) throw new Error("socket hang up");
    return { data: reply.data, status: reply.status, statusText: "", headers: {}, config };
  };
  const onUnauthorized = vi.fn();
  const http = createHttp({
    getToken: () => token,
    getLanguage: () => "zh-CN",
    onUnauthorized,
    adapter
  });
  return { http, seen, onUnauthorized };
}

const ok = <T>(data: T) => ({ code: 0, msg: "Success", data, errorCode: null, traceId: "t-1" });
const failure = (status: number, errorCode: string, data: unknown = null) => ({
  code: status,
  msg: "backend text that must never be shown",
  data,
  errorCode,
  traceId: "trace-42"
});

async function rejection(promise: Promise<unknown>): Promise<ApiError> {
  try {
    await promise;
  } catch (error) {
    if (error instanceof ApiError) return error;
    throw error;
  }
  throw new Error("expected the request to fail");
}

describe("request wrapper", () => {
  it("unwraps the data of a successful envelope", async () => {
    const { http } = setup({ status: 200, data: ok({ id: "9007199254740993" }) });
    await expect(http.request({ url: "/auth/me" })).resolves.toEqual({ id: "9007199254740993" });
  });

  it("sends the token in X-Admin-Token and uses the /admin-api prefix", async () => {
    const { http, seen } = setup({ status: 200, data: ok(null) });
    await http.request({ url: "/auth/me" });
    expect(TOKEN_HEADER).toBe("X-Admin-Token");
    expect(seen[0].headers.get("X-Admin-Token")).toBe("tok-1");
    expect(seen[0].baseURL).toBe("/admin-api");
  });

  it("sends the current language as Accept-Language", async () => {
    const seen: InternalAxiosRequestConfig[] = [];
    const adapter: AxiosAdapter = async config => {
      seen.push(config);
      return { data: ok(null), status: 200, statusText: "", headers: {}, config };
    };
    const http = createHttp({
      getToken: () => null,
      getLanguage: () => "en-US",
      onUnauthorized: vi.fn(),
      adapter
    });
    await http.request({ url: "/auth/login" });
    expect(seen[0].headers.get("Accept-Language")).toBe("en-US");
  });

  it("omits the header when signed out", async () => {
    const { http, seen } = setup({ status: 200, data: ok(null) }, null);
    await http.request({ url: "/auth/login" });
    expect(seen[0].headers.has("X-Admin-Token")).toBe(false);
  });

  it("sends a FormData upload as FormData, without the JSON content type", async () => {
    const { http, seen } = setup({ status: 202, data: ok({ taskId: "1" }) });
    const form = new FormData();
    form.append("file", new Blob(["x"]), "import.xlsx");
    await http.request({ method: "post", url: "/kol/kol-imports", data: form });
    // With the instance's JSON default, axios used to serialize the form to '{"file":{}}'.
    expect(seen[0].data).toBeInstanceOf(FormData);
    expect(String(seen[0].headers.getContentType() ?? "")).not.toContain("application/json");
  });

  it("keeps the JSON content type for an object body", async () => {
    const { http, seen } = setup({ status: 200, data: ok(null) });
    await http.request({ method: "post", url: "/auth/login", data: { username: "a" } });
    expect(seen[0].data).toBe('{"username":"a"}');
    expect(seen[0].headers.getContentType()).toBe("application/json");
  });

  it("maps a known errorCode to Chinese text and ignores the backend msg", async () => {
    const { http } = setup({ status: 409, data: failure(409, "USER_USERNAME_EXISTS") });
    const error = await rejection(http.request({ url: "/system/users" }));
    expect(error.errorCode).toBe("USER_USERNAME_EXISTS");
    expect(error.displayMessage).toBe("用户名已存在");
    expect(error.displayMessage).not.toContain("backend text");
  });

  it("shows a generic text plus the trace id for an unknown errorCode", async () => {
    const { http } = setup({ status: 418, data: failure(418, "BREW_FAILED") });
    const error = await rejection(http.request({ url: "/x" }));
    expect(error.displayMessage).toBe("操作失败，请稍后重试（追踪号：trace-42）");
  });

  it("treats a non-envelope body (proxy error page) as a generic failure", async () => {
    const { http } = setup({ status: 502, data: "<html>Bad Gateway</html>" });
    const error = await rejection(http.request({ url: "/x" }));
    expect(error.status).toBe(502);
    expect(error.errorCode).toBeNull();
    expect(error.displayMessage).toBe("操作失败，请稍后重试");
  });

  it("treats a 2xx response with a non-zero envelope code as a failure", async () => {
    const { http } = setup({ status: 200, data: failure(500, "COMMON_INTERNAL_ERROR") });
    const error = await rejection(http.request({ url: "/x" }));
    expect(error.errorCode).toBe("COMMON_INTERNAL_ERROR");
  });

  it("reports a network failure with its own text", async () => {
    const { http } = setup({ status: 0, fail: true });
    const error = await rejection(http.request({ url: "/x" }));
    expect(error.displayMessage).toBe("网络异常，请检查网络连接后重试");
  });

  it("calls onUnauthorized on 401 and still throws", async () => {
    const { http, onUnauthorized } = setup({
      status: 401,
      data: failure(401, "AUTH_UNAUTHORIZED")
    });
    const error = await rejection(http.request({ url: "/auth/me" }));
    expect(onUnauthorized).toHaveBeenCalledTimes(1);
    expect(onUnauthorized).toHaveBeenCalledWith(error);
    expect(error.displayMessage).toBe("登录已失效，请重新登录");
  });

  it("does not call onUnauthorized for 403 or 422", async () => {
    for (const [status, code] of [
      [403, "AUTH_FORBIDDEN"],
      [422, "USER_CANNOT_DISABLE_SELF"]
    ] as const) {
      const { http, onUnauthorized } = setup({ status, data: failure(status, code) });
      await rejection(http.request({ url: "/x" }));
      expect(onUnauthorized).not.toHaveBeenCalled();
    }
  });

  it("collects the rejected field names on COMMON_VALIDATION_FAILED, not their messages", async () => {
    const fields = [
      { field: "username", message: 'must match "[A-Za-z0-9_.-]{3,64}"' },
      { field: "password", message: "size must be between 10 and 72" }
    ];
    const { http } = setup({ status: 400, data: failure(400, "COMMON_VALIDATION_FAILED", fields) });
    const error = await rejection(http.request({ url: "/system/users" }));
    expect(error.fields).toEqual(["username", "password"]);
    expect(error.displayMessage).not.toContain("must match");
  });
});
