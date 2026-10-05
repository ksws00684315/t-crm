import type { AxiosAdapter } from "axios";
import { describe, expect, it, vi } from "vitest";
import { createHttp, fileNameFromDisposition } from "..";
import { ApiError } from "../error";

function setup(status: number, data: unknown, headers: Record<string, string> = {}) {
  const adapter: AxiosAdapter = async config => ({
    data,
    status,
    statusText: "",
    headers,
    config
  });
  const onUnauthorized = vi.fn();
  return { http: createHttp({ getToken: () => "tok", onUnauthorized, adapter }), onUnauthorized };
}

const envelope = (status: number, errorCode: string) =>
  new Blob([JSON.stringify({ code: status, msg: "x", data: null, errorCode, traceId: "t" })], {
    type: "application/json"
  });

describe("file download", () => {
  it("returns the blob and the file name from Content-Disposition", async () => {
    const blob = new Blob(["PK"]);
    const { http } = setup(200, blob, {
      "content-disposition":
        "attachment; filename=\"task-1-errors.xlsx\"; filename*=UTF-8''task-1-errors.xlsx"
    });
    const result = await http.download({ url: "/task/tasks/1/error-file" });
    expect(result.blob).toBe(blob);
    expect(result.fileName).toBe("task-1-errors.xlsx");
  });

  it("reads the error envelope out of a blob and reports 401", async () => {
    const { http, onUnauthorized } = setup(403, envelope(403, "AUTH_FORBIDDEN"));
    await expect(http.download({ url: "/x" })).rejects.toMatchObject({
      status: 403,
      errorCode: "AUTH_FORBIDDEN"
    });
    expect(onUnauthorized).not.toHaveBeenCalled();
    const unauthorized = setup(401, envelope(401, "AUTH_UNAUTHORIZED"));
    await expect(unauthorized.http.download({ url: "/x" })).rejects.toBeInstanceOf(ApiError);
    expect(unauthorized.onUnauthorized).toHaveBeenCalledTimes(1);
  });

  it("falls back to a generic error when the body is not an envelope", async () => {
    const { http } = setup(502, new Blob(["<html>bad gateway</html>"]));
    await expect(http.download({ url: "/x" })).rejects.toMatchObject({
      status: 502,
      errorCode: null
    });
  });

  it("maps a failed connection to a network error", async () => {
    const adapter: AxiosAdapter = async () => {
      throw new Error("socket hang up");
    };
    const http = createHttp({ getToken: () => null, onUnauthorized: vi.fn(), adapter });
    await expect(http.download({ url: "/x" })).rejects.toMatchObject({
      errorCode: "NETWORK_ERROR"
    });
  });

  it("parses file names in both header forms", () => {
    expect(fileNameFromDisposition("attachment; filename*=UTF-8''%E8%BE%BE%E4%BA%BA.xlsx")).toBe(
      "达人.xlsx"
    );
    expect(fileNameFromDisposition('attachment; filename="a.xlsx"')).toBe("a.xlsx");
    expect(fileNameFromDisposition("attachment; filename=b.xlsx")).toBe("b.xlsx");
    expect(fileNameFromDisposition("attachment")).toBeNull();
    expect(fileNameFromDisposition(null)).toBeNull();
    expect(fileNameFromDisposition("attachment; filename*=UTF-8''%E0%A4%A; filename=c.xlsx")).toBe(
      "c.xlsx"
    );
  });
});
