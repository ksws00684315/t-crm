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

/** `SocialAccountVO`; `followers` is a string (backend Long). */
export interface SocialAccountVO {
  platform: string;
  handle: string;
  url: string | null;
  followers: string | null;
}

/** `KolVO`. `defaultRate` is a fraction with 4 decimals ("0.1550" = 15.5%), null when not set. */
export interface KolVO {
  id: string;
  kolCode: string;
  name: string;
  email: string;
  /** ISO 3166-1 alpha-2, upper case. */
  country: string;
  socialAccounts: SocialAccountVO[];
  /** Null means "no BD assigned". */
  bdUserId: string | null;
  bdUserName: string | null;
  defaultRate: string | null;
  /** 1 = enabled, 0 = disabled. */
  status: 0 | 1;
  remark: string | null;
  createTime: string;
  updateTime: string;
}

/** `BdHistoryVO`: one ownership period; null `bdUserId` = unassigned, null `endTime` = current. */
export interface BdHistoryVO {
  bdUserId: string | null;
  bdUserName: string | null;
  startTime: string;
  endTime: string | null;
}

/** `KolDetailVO`: the KOL fields plus the ownership history, newest first. */
export interface KolDetailVO extends KolVO {
  bdHistory: BdHistoryVO[];
}

/** `BdUserVO`: a selectable BD (enabled OPERATOR with OWN data scope). */
export interface BdUserVO {
  id: string;
  username: string;
  nickname: string;
}

export type TaskStatus = "WAITING" | "RUNNING" | "SUCCESS" | "PARTIAL" | "FAILED";

export type TaskType = "KOL_IMPORT" | "PRODUCT_IMPORT" | "MAPPING_IMPORT";

/** `TaskVO`: progress of a batch task. `done` counts processed rows, failed ones included. */
export interface TaskVO {
  id: string;
  taskType: TaskType;
  status: TaskStatus;
  total: number;
  done: number;
  failed: number;
  /** English reason when the task failed as a whole; not shown to users verbatim. */
  message: string | null;
  hasErrorFile: boolean;
  createTime: string;
  startedAt: string | null;
  finishedAt: string | null;
}

/** `ShopVO`. */
export interface ShopVO {
  id: string;
  shopName: string;
  weidianShopId: string;
  status: 0 | 1;
  remark: string | null;
  createTime: string;
  updateTime: string;
}

/** `ProductVO`. `price` 2 decimals; `rate` fraction 4 decimals or null. */
export interface ProductVO {
  id: string;
  productCode: string;
  shopId: string;
  shopName: string | null;
  weidianShopId: string | null;
  weidianItemId: string;
  name: string;
  category: string | null;
  imageUrl: string | null;
  price: string;
  rate: string | null;
  status: 0 | 1;
  createTime: string;
  updateTime: string;
}

/** `KolItemVO`. `validTo` null means currently effective. */
export interface KolItemVO {
  id: string;
  kolId: string;
  kolCode: string | null;
  kolName: string | null;
  productId: string;
  productCode: string | null;
  productName: string | null;
  shopId: string;
  shopName: string | null;
  weidianShopId: string | null;
  weidianItemId: string;
  validFrom: string;
  validTo: string | null;
}

/** Why a tracking link is unusable (task 06 §4.3). */
export type LinkUnavailableReason = "KOL_DISABLED" | "PRODUCT_OFF_SHELF" | "SHOP_DISABLED";

/**
 * `LinkVO`: one tracking link of a KOL. Click counts cover the last 30 days (strings, 64-bit);
 * valid clicks exclude bots and duplicates.
 */
export interface LinkVO {
  kolItemId: string;
  productCode: string;
  productName: string;
  weidianItemId: string;
  linkUrl: string;
  targetUrl: string;
  available: boolean;
  unavailableReasons: LinkUnavailableReason[];
  clicks30d: string;
  validClicks30d: string;
}
