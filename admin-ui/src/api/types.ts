/** Shared API types. Field names mirror the backend `*Request` / `*VO` records one to one. */

/** Response envelope `R<T>` returned by every backend endpoint. */
export interface Envelope<T> {
  code: number;
  msg: string;
  data: T;
  errorCode: string | null;
  traceId: string | null;
}

/** `PageResult<T>`; page numbers start at 1. */
export interface PageResult<T> {
  items: T[];
  /** Serialized as a string (spec 03); convert with Number() before el-pagination. */
  total: string;
  page: number;
  size: number;
}

/** Element of `data` when the backend answers COMMON_VALIDATION_FAILED. */
export interface FieldErrorVO {
  field: string;
  message: string;
}

export type Role = "SUPER_ADMIN" | "OPERATOR" | "FINANCE";
export type DataScope = "ALL" | "OWN";

/** `UserVO`. Ids are strings (backend Long), time stamps are ISO 8601 instants. */
export interface UserVO {
  id: string;
  username: string;
  nickname: string;
  email: string | null;
  role: Role;
  dataScope: DataScope;
  /** 1 = enabled, 0 = disabled. */
  status: 0 | 1;
  lastLoginTime: string | null;
  createTime: string;
}
