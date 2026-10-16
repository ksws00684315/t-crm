import axios, { type AxiosAdapter, type AxiosInstance, type AxiosRequestConfig } from "axios";
import type { Envelope, FieldErrorVO } from "@/api/types";
import { ApiError, NETWORK_ERROR } from "./error";

/** Request header carrying the Sa-Token session token (backend `sa-token.token-name`). */
export const TOKEN_HEADER = "X-Admin-Token";
/** All admin endpoints live under this prefix; the dev server and Nginx proxy it to the backend. */
export const BASE_URL = "/admin-api";

export interface HttpOptions {
  baseURL?: string;
  /** Supplies the current token, or null when signed out. */
  getToken: () => string | null;
  /** Supplies the current UI language, sent as Accept-Language on every request (spec 03 section 6). */
  getLanguage: () => string;
  /** Called on every HTTP 401, before the error is thrown. */
  onUnauthorized: (error: ApiError) => void;
  /** Test hook. */
  adapter?: AxiosAdapter;
}

/** A file returned by a non-JSON endpoint (`Content-Disposition: attachment`). */
export interface DownloadedFile {
  blob: Blob;
  /** From `Content-Disposition`; null when the header has no file name. */
  fileName: string | null;
}

export interface Http {
  instance: AxiosInstance;
  /** Resolves with `data` of a successful envelope, otherwise throws {@link ApiError}. */
  request<T>(config: AxiosRequestConfig): Promise<T>;
  /** Fetches a file; failures carry the JSON envelope and throw {@link ApiError} like `request`. */
  download(config: AxiosRequestConfig): Promise<DownloadedFile>;
}

/** File name from `Content-Disposition`, preferring the RFC 5987 `filename*` form. */
export function fileNameFromDisposition(header: string | null | undefined): string | null {
  if (!header) return null;
  const extended = /filename\*\s*=\s*(?:UTF-8|utf-8)''([^;]+)/.exec(header);
  if (extended) {
    try {
      return decodeURIComponent(extended[1].trim());
    } catch {
      // fall through to the plain form
    }
  }
  const plain = /filename\s*=\s*"([^"]*)"|filename\s*=\s*([^;]+)/.exec(header);
  const name = plain ? (plain[1] ?? plain[2]).trim() : "";
  return name.length > 0 ? name : null;
}

/** Reads a JSON envelope out of an error response that was requested as a blob. */
async function blobBody(data: unknown): Promise<unknown> {
  if (!(data instanceof Blob)) return data;
  try {
    return JSON.parse(await data.text());
  } catch {
    return null;
  }
}

function isEnvelope(body: unknown): body is Envelope<unknown> {
  return typeof body === "object" && body !== null && "code" in body && "data" in body;
}

function fieldNames(data: unknown): string[] {
  if (!Array.isArray(data)) return [];
  return (data as Partial<FieldErrorVO>[])
    .map(item => item.field)
    .filter((field): field is string => typeof field === "string" && field.length > 0);
}

function toApiError(status: number, body: unknown): ApiError {
  if (isEnvelope(body)) {
    return new ApiError({
      status,
      errorCode: body.errorCode,
      traceId: body.traceId,
      fields: body.errorCode === "COMMON_VALIDATION_FAILED" ? fieldNames(body.data) : [],
      payload: body.data
    });
  }
  // Not an envelope (proxy error page, gateway timeout, ...).
  return new ApiError({ status, errorCode: null, traceId: null });
}

export function createHttp(options: HttpOptions): Http {
  const instance = axios.create({
    baseURL: options.baseURL ?? BASE_URL,
    timeout: 15000,
    headers: { "Content-Type": "application/json" },
    adapter: options.adapter,
    // Handle every status ourselves so the envelope of an error response is available.
    validateStatus: () => true
  });

  instance.interceptors.request.use(config => {
    const token = options.getToken();
    if (token) config.headers.set(TOKEN_HEADER, token);
    config.headers.set("Accept-Language", options.getLanguage());
    return config;
  });

  async function request<T>(config: AxiosRequestConfig): Promise<T> {
    let status: number;
    let body: unknown;
    try {
      const response = await instance.request(config);
      status = response.status;
      body = response.data;
    } catch {
      throw new ApiError({ status: 0, errorCode: NETWORK_ERROR, traceId: null });
    }
    if (status >= 200 && status < 300 && isEnvelope(body) && body.code === 0) {
      return body.data as T;
    }
    const error = toApiError(status, body);
    if (status === 401) options.onUnauthorized(error);
    throw error;
  }

  async function download(config: AxiosRequestConfig): Promise<DownloadedFile> {
    let status: number;
    let body: unknown;
    let disposition: string | null = null;
    try {
      const response = await instance.request({ ...config, responseType: "blob" });
      status = response.status;
      body = response.data;
      const header: unknown = response.headers?.["content-disposition"];
      disposition = typeof header === "string" ? header : null;
    } catch {
      throw new ApiError({ status: 0, errorCode: NETWORK_ERROR, traceId: null });
    }
    if (status >= 200 && status < 300 && body instanceof Blob) {
      return { blob: body, fileName: fileNameFromDisposition(disposition) };
    }
    const error = toApiError(status, await blobBody(body));
    if (status === 401) options.onUnauthorized(error);
    throw error;
  }

  return { instance, request, download };
}
