import axios, { type AxiosAdapter, type AxiosInstance, type AxiosRequestConfig } from "axios";
import type { Envelope, FieldErrorVO } from "@/api/types";
import { KOL_BASE_URL, KOL_TOKEN_HEADER } from "@/api/constants";
import { ApiError, NETWORK_ERROR } from "./error";

export interface HttpOptions {
  baseURL?: string;
  /** Supplies the current token, or null when signed out. */
  getToken: () => string | null;
  /** Called on every HTTP 401, before the error is thrown. */
  onUnauthorized: (error: ApiError) => void;
  /** Test hook. */
  adapter?: AxiosAdapter;
}

export interface Http {
  instance: AxiosInstance;
  /** Resolves with `data` of a successful envelope, otherwise throws {@link ApiError}. */
  request<T>(config: AxiosRequestConfig): Promise<T>;
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
      fields: body.errorCode === "COMMON_VALIDATION_FAILED" ? fieldNames(body.data) : []
    });
  }
  // Not an envelope (proxy error page, gateway timeout, ...).
  return new ApiError({ status, errorCode: null, traceId: null });
}

export function createHttp(options: HttpOptions): Http {
  const instance = axios.create({
    baseURL: options.baseURL ?? KOL_BASE_URL,
    timeout: 15000,
    headers: { "Content-Type": "application/json" },
    adapter: options.adapter,
    // Handle every status ourselves so the envelope of an error response is available.
    validateStatus: () => true
  });

  instance.interceptors.request.use(config => {
    const token = options.getToken();
    if (token) config.headers.set(KOL_TOKEN_HEADER, token);
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

  return { instance, request };
}
