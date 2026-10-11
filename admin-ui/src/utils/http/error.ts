import { isKnownErrorCode, messageForErrorCode, networkErrorMessage } from "@/api/error-codes";
import { translate } from "@/locales";

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
    // Our own text in the language valid at throw time, never the backend `msg`; displayMessage
    // resolves the text again so a language switch is reflected even for existing errors.
    super(
      init.errorCode === NETWORK_ERROR ? networkErrorMessage() : messageForErrorCode(init.errorCode)
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
    const base =
      this.errorCode === NETWORK_ERROR
        ? networkErrorMessage()
        : messageForErrorCode(this.errorCode);
    const needsTrace =
      this.errorCode !== NETWORK_ERROR &&
      (!isKnownErrorCode(this.errorCode) || this.errorCode === "COMMON_INTERNAL_ERROR");
    return needsTrace && this.traceId
      ? translate("errors.withTrace", { message: base, traceId: this.traceId })
      : base;
  }
}
