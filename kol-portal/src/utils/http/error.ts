import { isKnownErrorCode, messageForErrorCode, NETWORK_ERROR_MESSAGE } from "@/api/error-codes";

export const NETWORK_ERROR = "NETWORK_ERROR";

/** Failure of an API call: HTTP error, non-zero envelope code, or no response at all. */
export class ApiError extends Error {
  readonly status: number;
  readonly errorCode: string | null;
  readonly traceId: string | null;
  /** Names of the request fields the backend rejected (COMMON_VALIDATION_FAILED only). */
  readonly fields: string[];
  /** Set by a global handler that already told the user (401 redirect); callers then stay quiet. */
  handled = false;

  constructor(init: {
    status: number;
    errorCode: string | null;
    traceId: string | null;
    fields?: string[];
  }) {
    // The message is our own English text, never the backend `msg`.
    super(
      init.errorCode === NETWORK_ERROR ? NETWORK_ERROR_MESSAGE : messageForErrorCode(init.errorCode)
    );
    this.name = "ApiError";
    this.status = init.status;
    this.errorCode = init.errorCode;
    this.traceId = init.traceId;
    this.fields = init.fields ?? [];
  }

  /**
   * Text for the user. Unknown error codes and internal errors also get the trace id so support
   * can find the log line; other known codes are self-explanatory.
   */
  get displayMessage(): string {
    const needsTrace =
      this.errorCode !== NETWORK_ERROR &&
      (!isKnownErrorCode(this.errorCode) || this.errorCode === "COMMON_INTERNAL_ERROR");
    return needsTrace && this.traceId
      ? `${this.message} (trace id: ${this.traceId})`
      : this.message;
  }
}
