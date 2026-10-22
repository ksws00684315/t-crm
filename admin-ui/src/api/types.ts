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

export type Role = "PLATFORM_ADMIN" | "SUPER_ADMIN" | "STAFF";
/** Roles that a company super administrator may assign to another company user. */
export type CompanyRole = Exclude<Role, "PLATFORM_ADMIN">;
export type DataScope = "ALL" | "OWN";

/**
 * Post chosen when a sub-account is created. It only pre-fills permissions (ADR-018);
 * it is not itself a role.
 */
export type PostTemplate = "OPERATOR" | "BD";

/** The 18 permission codes in design §3.1. The API may also return codes this build does not know. */
export type PermissionCode =
  | "kol:view"
  | "kol:edit"
  | "kol:register-link"
  | "kol:copy-token"
  | "product:view"
  | "product:edit"
  | "product:status"
  | "order:view"
  | "order:manage"
  | "commission:view"
  | "commission:freeze"
  | "payout-account:review"
  | "withdrawal:review"
  | "prepay:apply"
  | "dashboard:shop"
  | "dashboard:team"
  | "dashboard:kol"
  | "data:export";

/** One company the signed-in account belongs to (`companies` of `/auth/me`, ADR-022). */
export interface CompanyVO {
  tenantId: string;
  tenantName: string;
  role: CompanyRole;
}

/** `UserVO`. Ids are strings (backend Long), time stamps are ISO 8601 instants. */
export interface UserVO {
  id: string;
  username: string;
  nickname: string;
  email: string | null;
  role: Role;
  /** Null for a super administrator and the platform administrator. */
  postTemplate: PostTemplate | null;
  /**
   * Granted codes. A super administrator still receives the field; `can()` does not consult it.
   * Typed as strings so a code this build does not know yet is shown as-is and does not throw.
   */
  permissions: string[];
  dataScope: DataScope;
  /** Null for the platform administrator; optional at runtime for older backend responses. */
  tenantId: string | null;
  /** Null for the platform administrator; optional at runtime for older backend responses. */
  tenantName: string | null;
  /** 1 = enabled, 0 = disabled. */
  status: 0 | 1;
  lastLoginTime: string | null;
  createTime: string;
  /**
   * `/auth/me` and login only: the companies this account may switch to, sorted by name; empty for
   * the platform administrator. Optional at runtime for older backend responses.
   */
  companies?: CompanyVO[];
  /** `/system/users` rows only: how many companies this person belongs to. */
  companyCount?: number;
}

/** `SocialAccountVO`; `followers` is a string (backend Long). `primary` is the main platform. */
export interface SocialAccountVO {
  platform: string;
  handle: string;
  url: string | null;
  followers: string | null;
  primary: boolean;
}

export type ReviewStatus = "PENDING" | "APPROVED";
export type RelationStatus = "PENDING_PROMOTION" | "PROMOTING" | "INVALID";
export type DisplayRelationStatus = RelationStatus | "PENDING_UPDATE";
export type LoginStatus = "NORMAL" | "FROZEN" | "ABNORMAL";

/** `KolVO`. `defaultRate` is a fraction with 4 decimals ("0.1550" = 15.5%), null when not set. */
export interface KolVO {
  id: string;
  kolCode: string;
  name: string;
  email: string;
  /** ISO 3166-1 alpha-2, upper case. Null when unset. */
  country: string | null;
  socialAccounts: SocialAccountVO[];
  /** Null means "no BD assigned". */
  bdUserId: string | null;
  bdUserName: string | null;
  defaultRate: string | null;
  /** 1 = enabled, 0 = disabled. */
  status: 0 | 1;
  remark: string | null;
  whatsapp: string | null;
  phone: string | null;
  /** Enabled template id, or a disabled one still stored on the creator. */
  linkTemplateId: string | null;
  linkTemplateName: string | null;
  inviteCode: string | null;
  sheetUrl: string | null;
  /** 1 entered in the admin console, 2 self-registered. */
  registerSource: 1 | 2;
  /**
   * 0 not activated, 1 activated. Task 12c adds this to `KolVO`; until then the field may be absent.
   */
  accountStatus?: 0 | 1;
  reviewStatus: ReviewStatus;
  relationStatus: RelationStatus;
  displayRelationStatus: DisplayRelationStatus;
  loginStatus: LoginStatus;
  /**
   * Per-creator auto cleanup (task 33 §3.2): null = off, otherwise the number of days without
   * orders after which the creator is cleaned up. Optional until the backend ships it.
   */
  autoCleanupDays?: number | null;
  platforms: Array<{ templateId: string; templateName: string; address: string }>;
  jumpTemplateId: string | null;
  configVersion: number | null;
  inviteTime: string;
  createTime: string;
  updateTime: string;
}

/** `LinkTemplateVO`. `status` 1 enabled, 0 disabled. `channelCode` is always WEIDIAN. */
export interface LinkTemplateVO {
  id: string;
  templateCode: string;
  templateName: string;
  templateUrl: string;
  channelCode: string;
  sortOrder: number;
  status: 0 | 1;
  createTime: string;
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

/** `BdUserVO`: a selectable BD (enabled staff the backend accepts as a creator owner). */
export interface BdUserVO {
  id: string;
  username: string;
  nickname: string;
}

export type TaskStatus = "WAITING" | "RUNNING" | "SUCCESS" | "PARTIAL" | "FAILED";

export type TaskType =
  "KOL_IMPORT" | "PRODUCT_IMPORT" | "MAPPING_IMPORT" | "ORDER_IMPORT" | "REFUND_IMPORT";

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

export type ShopChannel = "WEIDIAN";
export type ShopAuthStatus = "UNAUTHORIZED" | "AUTHORIZED" | "EXPIRED";
export type CopyPoolStatus = "JOINED" | "EXITED" | "NOT_APPLICABLE";
export type CopyIneligibleReason =
  "DISABLED" | "UNAUTHORIZED" | "AUTH_EXPIRED" | "SOURCE_SHOP" | "POOL_EXITED" | "CAPACITY_FULL";

export interface ShopApiCheckVO {
  ok: boolean | null;
  checkedAt: string | null;
  error: string | null;
}

/** `ShopVO`; mirrors `GET /admin-api/product/shops`. */
export interface ShopOptionVO {
  id: string;
  shopCode: string;
  shopName: string;
  channel: ShopChannel;
  status: 0 | 1;
}

export interface ShopVO {
  id: string;
  shopCode: string;
  shopName: string;
  channel: ShopChannel;
  weidianShopId: string | null;
  status: 0 | 1;
  isSource: boolean;
  copyPoolStatus: CopyPoolStatus;
  maxItems: number;
  currentItems: number;
  itemsRefreshedAt: string | null;
  authStatus: ShopAuthStatus;
  authExpiresAt: string | null;
  apiStatus: {
    product: ShopApiCheckVO;
    order: ShopApiCheckVO;
    refund: ShopApiCheckVO;
  };
  copyEligible: boolean;
  copyIneligibleReason: CopyIneligibleReason | null;
  remark: string | null;
  createTime: string;
  updateTime: string;
}

export interface CopyChannelShopVO {
  id: string;
  shopCode: string;
  shopName: string;
  remainingCapacity: number;
}

/** Optional defaults are supplied by the backend when available. */
export interface CopyChannelVO {
  channel: ShopChannel;
  remainingCapacity: number;
  shops: CopyChannelShopVO[];
  defaultQuota?: number;
  defaultValidMinutes?: number;
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
  /** ISO 4217 currency of `price`; CNY unless read from a source sheet (ADR-021). */
  priceCurrency: string;
  rate: string | null;
  status: 0 | 1;
  createTime: string;
  updateTime: string;
}

/** Rows a source-sheet read skipped, by reason, with up to 100 examples. */
export interface SheetSkippedVO {
  empty: number;
  nonWeidian: number;
  noItemId: number;
  noShop: number;
  samples: { row: number; reason: "NON_WEIDIAN" | "NO_ITEM_ID" | "NO_SHOP"; name: string }[];
}

/** `SheetSourceVO` (ADR-021). Columns are letters; `lastError` is an error code or null. */
export interface SheetSourceVO {
  id: string;
  sourceName: string;
  spreadsheetId: string;
  sheetTitle: string;
  headerRow: number;
  linkColumn: string;
  nameColumn: string;
  priceColumn: string;
  categoryColumn: string | null;
  imageColumn: string | null;
  defaultShopId: string | null;
  defaultShopName: string | null;
  priceCurrency: string;
  status: 0 | 1;
  lastReadAt: string | null;
  lastTaskId: string | null;
  lastRowCount: number | null;
  lastSkipped: SheetSkippedVO | null;
  lastError: string | null;
}

/** Result of `POST /product/sheet-sources/{id}/reads`. */
export interface SheetReadResultVO {
  taskId: string;
  rowCount: number;
  skipped: SheetSkippedVO;
}

/** An original product no longer present in the source sheet. */
export interface SheetMissingVO {
  productId: string;
  productCode: string;
  productName: string;
  productStatus: 0 | 1;
  shopId: string;
  shopName: string | null;
  weidianItemId: string;
  sheetRow: number;
  lastSeenTime: string;
}

/** Company Google service account; the key itself is never returned. */
export interface GoogleCredentialVO {
  configured: boolean;
  clientEmail: string | null;
  privateKeyId: string | null;
  updateTime: string | null;
}

/** Creator-item list row (task 39). Amounts and ids are strings. Counts are strings too. */
export interface KolItemVO {
  id: string;
  linkCode: string;
  itemName: string | null;
  imageUrl: string | null;
  kolId: string;
  kolCode: string | null;
  kolName: string | null;
  targetShopId: string;
  targetShopName: string | null;
  targetShopCode: string | null;
  targetChannel: string;
  targetItemId: string;
  targetItemUrl: string | null;
  sourceItemId: string | null;
  sourceChannel: string | null;
  currentRate: string | null;
  salesQuantity: string;
  payAmount: string;
  status: "ENABLED" | "DISABLED";
  cleanupStatus: "NONE" | "PENDING" | "DONE" | "FAILED";
  createTime: string;
}

/** Creator-item detail. `totalPayAmount` uses the same paid-order rule as `payAmount`. */
export interface KolItemDetailVO extends KolItemVO {
  sourceItemUrl: string | null;
  visitCount: string;
  totalPayAmount: string;
  refundAmount: string;
  cleanupTime: string | null;
  cleanupReason: string | null;
  cleanupFailReason: string | null;
}

/** Why a tracking link is unusable (task 06 §4.3). */

export type CopyTokenDisplayStatus = "ACTIVE" | "EXHAUSTED" | "EXPIRED" | "REVOKED";

export interface CopyTokenVO {
  id: string;
  tokenHint: string;
  kolId: string;
  kolCode: string | null;
  kolName: string | null;
  targetChannel: string;
  targetShopId: string | null;
  targetShopName: string | null;
  targetShopCode: string | null;
  used: number;
  quota: number;
  validMinutes: number;
  expiresAt: string;
  displayStatus: CopyTokenDisplayStatus;
  lastUsedAt: string | null;
  createTime: string;
}

export interface CreatedCopyTokenVO extends CopyTokenVO {
  token: string;
}

export type LinkUnavailableReason = "KOL_DISABLED" | "PRODUCT_OFF_SHELF" | "SHOP_DISABLED";

/**
 * `LinkVO`: one tracking link of a KOL. Click counts cover the last 30 days (strings, 64-bit);
 * valid clicks exclude bots and duplicates.
 */
export interface LinkVO {
  kolItemId: string;
  productCode: string | null;
  productName: string | null;
  weidianItemId: string;
  linkUrl: string | null;
  targetUrl: string;
  available: boolean;
  unavailableReasons: LinkUnavailableReason[];
  clicks30d: string;
  validClicks30d: string;
}

/** Standardized Weidian order status (design §8.1). */
export type OrderStatus =
  "UNPAID" | "PAID" | "SHIPPED" | "RECEIVED" | "COMPLETED" | "CLOSED" | "UNKNOWN";

/** Order attribution status (design §4.6). */
export type AttributionStatus = "PENDING" | "ATTRIBUTED" | "NON_KOL" | "CONFLICT" | "UNMATCHED";

/** How one order line was attributed: 0 unmatched, 1 product id, 2 manual, 3 non-KOL, 4 conflict. */
export type ItemAttributionType = 0 | 1 | 2 | 3 | 4;

/** `OrderItemDTO`. Amounts are strings; display names are null when the reference is gone. */
export interface OrderItemVO {
  id: string;
  subOrderId: string | null;
  weidianItemId: string;
  skuId: string | null;
  itemName: string | null;
  skuName: string | null;
  quantity: number | null;
  payAmount: string | null;
  refundAmount: string | null;
  kolId: string | null;
  kolCode: string | null;
  productId: string | null;
  kolItemId: string | null;
  attributionType: ItemAttributionType | null;
  manualReason: string | null;
}

/**
 * `OrderDTO`. `bdUserName` is null when the payment-time snapshot assigns the order to the
 * company; the response never contains buyer data.
 */
export interface OrderVO {
  id: string;
  shopId: string;
  shopName: string | null;
  weidianOrderId: string;
  kolId: string | null;
  kolCode: string | null;
  kolName: string | null;
  bdUserId: string | null;
  bdUserName: string | null;
  orderStatus: OrderStatus;
  attributionStatus: AttributionStatus;
  orderTime: string | null;
  payTime: string | null;
  totalAmount: string;
  payAmount: string;
  /** Completed refunds across the whole order; "0.00" when there are none. */
  refundAmount: string;
  items: OrderItemVO[];
}

/** `OrderSyncStateDTO`: one shop with its order-sync status; nulls when it never synced. */
export interface OrderSyncStateVO {
  shopId: string;
  shopName: string;
  enabled: boolean;
  authorized: boolean;
  lastSuccessAt: string | null;
  lastError: string | null;
}
