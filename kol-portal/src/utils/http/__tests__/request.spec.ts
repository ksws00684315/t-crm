import type { AxiosAdapter, InternalAxiosRequestConfig } from "axios";
import { describe, expect, it, vi } from "vitest";
import { KOL_TOKEN_HEADER } from "@/api/constants";
import { createHttp } from "..";
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
  return {
    http: createHttp({ getToken: () => token, onUnauthorized, adapter }),
    seen,
    onUnauthorized
  };
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
    await expect(http.request({ url: "/me" })).resolves.toEqual({ id: "9007199254740993" });
  });

  it("uses the /kol-api prefix and sends the creator token header", async () => {
    const { http, seen } = setup({ status: 200, data: ok(null) });
    await http.request({ url: "/me" });
    expect(seen[0].baseURL).toBe("/kol-api");
    expect(seen[0].headers.get(KOL_TOKEN_HEADER)).toBe("tok-1");
  });

  it("omits the token header when signed out", async () => {
    const { http, seen } = setup({ status: 200, data: ok(null) }, null);
    await http.request({ url: "/me" });
    expect(seen[0].headers.has(KOL_TOKEN_HEADER)).toBe(false);
  });

  it("shows English text for a known errorCode and never the backend msg", async () => {
    const { http } = setup({ status: 403, data: failure(403, "AUTH_FORBIDDEN") });
    const error = await rejection(http.request({ url: "/x" }));
    expect(error.displayMessage).toBe("You do not have permission to do this.");
  });

  it("shows a generic English text plus the trace id for an unknown errorCode", async () => {
    const { http } = setup({ status: 418, data: failure(418, "BREW_FAILED") });
    const error = await rejection(http.request({ url: "/x" }));
    expect(error.displayMessage).toBe(
      "The operation failed. Please try again later. (trace id: trace-42)"
    );
  });

  it("treats a non-envelope body as a generic failure", async () => {
    const { http } = setup({ status: 502, data: "<html>Bad Gateway</html>" });
    const error = await rejection(http.request({ url: "/x" }));
    expect(error.status).toBe(502);
    expect(error.displayMessage).toBe("The operation failed. Please try again later.");
  });

  it("reports a network failure in English", async () => {
    const { http } = setup({ status: 0, fail: true });
    const error = await rejection(http.request({ url: "/x" }));
    expect(error.displayMessage).toBe("Network error. Please check your connection and try again.");
  });

  it("calls onUnauthorized on 401 and still throws", async () => {
    const { http, onUnauthorized } = setup({
      status: 401,
      data: failure(401, "AUTH_UNAUTHORIZED")
    });
    const error = await rejection(http.request({ url: "/me" }));
    expect(onUnauthorized).toHaveBeenCalledWith(error);
  });

  it("does not call onUnauthorized for other statuses", async () => {
    const { http, onUnauthorized } = setup({ status: 403, data: failure(403, "AUTH_FORBIDDEN") });
    await rejection(http.request({ url: "/x" }));
    expect(onUnauthorized).not.toHaveBeenCalled();
  });

  it("collects rejected field names on COMMON_VALIDATION_FAILED", async () => {
    const fields = [{ field: "account", message: "must not be blank" }];
    const { http } = setup({ status: 400, data: failure(400, "COMMON_VALIDATION_FAILED", fields) });
    const error = await rejection(http.request({ url: "/x" }));
    expect(error.fields).toEqual(["account"]);
  });
});
