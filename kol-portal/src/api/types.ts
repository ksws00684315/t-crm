/** Shared API types; the envelope matches the backend `R<T>` record. */

export interface Envelope<T> {
  code: number;
  msg: string;
  data: T;
  errorCode: string | null;
  traceId: string | null;
}

export interface PageResult<T> {
  items: T[];
  total: number;
  page: number;
  size: number;
}

/** Element of `data` when the backend answers COMMON_VALIDATION_FAILED. */
export interface FieldErrorVO {
  field: string;
  message: string;
}
