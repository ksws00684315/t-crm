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
export type PostTemplate = "OPERATOR" | "BD" | "FINANCE";

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
  | "withdrawal:pay"
  | "prepay:apply"
  | "prepay:review"
  | "prepay:finance-review"
  | "prepay:pay"
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

/** How a departure plan ends the account (task 41). */
export type DeparturePlanType = "RESIGN" | "DISABLE";
/** A plan is pending until it runs (DONE) or is withdrawn (CANCELLED). */
export type DeparturePlanStatus = "SCHEDULED" | "DONE" | "CANCELLED";
/** Who asked for the plan. */
export type DepartureInitiator = "SELF" | "ADMIN";
/** What happened to the account's creators when the plan ran. */
export type DepartureResult = "TRANSFERRED" | "UNASSIGNED" | "HEIR_INVALID";

/** `DeparturePlanVO`: the resignation or disable plan of one BD account. */
export interface DeparturePlanVO {
  id: string;
  userId: string;
  type: DeparturePlanType;
  effectiveAt: string;
  heirUserId: string | null;
  heirName: string | null;
  initiator: DepartureInitiator;
  reason: string | null;
  status: DeparturePlanStatus;
  executedAt: string | null;
  result: DepartureResult | null;
  resultMessage: string | null;
  /** Pending plan without an heir: the list shows "heir still to be named". */
  awaitingHeir: boolean;
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
  /** `/system/users` rows only: the newest departure plan, null when the account has none. */
  departurePlan?: DeparturePlanVO | null;
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

/** Sales channel of an order / refund / link. Weidian is the only channel today. */
export type Channel = "WEIDIAN";

/** Order-level refund summary (task 40 §3.1): none / processing / partial / full success. */
export type OrderRefundStatus = "NONE" | "PROCESSING" | "PARTIAL_SUCCESS" | "FULL_SUCCESS";

/** Refund type (requirement 08.02): refund-only, return-and-refund, or unrecognized. */
export type RefundType = "REFUND_ONLY" | "RETURN_REFUND" | "UNKNOWN";

/** Single-refund status (task 40 §3.1). Only SUCCESS counts as a completed refund. */
export type RefundStatus = "PROCESSING" | "SUCCESS" | "CLOSED" | "UNKNOWN";

/**
 * Order-list refund filter (task 70 / 1024 handoff B03): refunding, or refunded covering both
 * partial and whole refunds. Separate from `OrderStatus` on purpose.
 */
export type RefundFilterState = "REFUNDING" | "REFUNDED";

/** Where the order data came from (task 40 §3.2): the Weidian API or a manual import. */
export type OrderSource = "API" | "IMPORT";

/** Order attribution status (design §4.6). */
export type AttributionStatus = "PENDING" | "ATTRIBUTED" | "NON_KOL" | "CONFLICT" | "UNMATCHED";

/** How one order line was attributed: 0 unmatched, 1 product id, 2 manual, 3 non-KOL, 4 conflict. */
export type ItemAttributionType = 0 | 1 | 2 | 3 | 4;

/**
 * `OrderItemDTO`. Amounts are strings; display names are null when the reference is gone.
 * `kolName` and `imageUrl` (task 71, B05) follow the detail endpoint's field names; both are null
 * for unattributed lines, deleted creators and missing images.
 */
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
  kolName: string | null;
  productId: string | null;
  kolItemId: string | null;
  attributionType: ItemAttributionType | null;
  manualReason: string | null;
  imageUrl: string | null;
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
  /** Task 40 §3.2 additions; optional until the backend branch merges. */
  channel?: Channel;
  shopCode?: string | null;
  /** Order-level refund summary (none / processing / partial / full success). */
  refundStatus?: OrderRefundStatus;
  freight?: string | null;
  createTime?: string | null;
}

/** One product row of an order detail (task 40 §3.2); `status` is the raw line status. */
export interface OrderDetailItemVO {
  id: string;
  imageUrl: string | null;
  itemName: string | null;
  weidianItemId: string;
  skuName: string | null;
  kolId: string | null;
  kolName: string | null;
  kolCode: string | null;
  quantity: number | null;
  unitPrice: string | null;
  payAmount: string | null;
  refundAmount: string | null;
  status: string | null;
}

/** `GET /order/orders/{id}` (task 40 §3.2). */
export interface OrderDetailVO {
  id: string;
  /** The Weidian order number. */
  orderNo: string;
  channel: Channel;
  shopId: string;
  shopName: string | null;
  shopCode: string | null;
  /** The shop's Weidian merchant identifier. */
  merchantNo: string | null;
  kolId: string | null;
  kolName: string | null;
  kolCode: string | null;
  orderStatus: OrderStatus;
  refundStatus: OrderRefundStatus;
  attributionStatus: AttributionStatus;
  source: OrderSource | null;
  orderTime: string | null;
  payTime: string | null;
  lastSyncTime: string | null;
  createTime: string | null;
  itemAmount: string | null;
  totalAmount: string;
  payAmount: string;
  freight: string | null;
  items: OrderDetailItemVO[];
}

/** `GET /order/refunds` row (task 40 §3.3). */
export interface RefundVO {
  id: string;
  refundNo: string;
  orderId: string;
  orderNo: string;
  channel: Channel;
  shopName: string | null;
  shopCode: string | null;
  kolName: string | null;
  kolCode: string | null;
  refundType: RefundType;
  refundStatus: RefundStatus;
  amount: string;
  applyTime: string | null;
  finishTime: string | null;
}

/** One refunded product row, linked to its order line (task 40 §3.3). */
export interface RefundItemVO {
  orderItemId: string;
  imageUrl: string | null;
  itemName: string | null;
  weidianItemId: string;
  skuName: string | null;
  kolId: string | null;
  kolName: string | null;
  kolCode: string | null;
  quantity: number | null;
  refundAmount: string | null;
}

/** `GET /order/refunds/{id}` (task 40 §3.3). */
export interface RefundDetailVO extends RefundVO {
  merchantNo: string | null;
  lines: RefundItemVO[];
}

/** Copy job block of a chain line (task 40 §3.4); null when the item has no copy job. */
export interface ChainCopyJobVO {
  jobCode: string;
  createTime: string | null;
  status: string | null;
}

/** KOL item link block of a chain line (task 40 §3.4); null when the line has no mapping. */
export interface ChainLinkVO {
  kolItemId: string;
  linkCode: string | null;
  /** The source product the mapping points at (the chain's source item id). */
  sourceProductId: string;
  targetItemId: string;
  kolId: string | null;
  kolName: string | null;
  kolCode: string | null;
}

/** One refund attached to a chain line (task 40 §3.4). */
export interface ChainRefundVO {
  id: string;
  refundNo: string;
  refundType: RefundType;
  refundStatus: RefundStatus;
  amount: string;
  finishTime: string | null;
}

/** One withdrawal allocation shown on a chain line (task 72, B09); paidAt is null until paid. */
export interface ChainWithdrawalVO {
  withdrawalId: string;
  applicationNo: string;
  occupiedCny: string;
  status: string;
  paidAt: string | null;
}

/**
 * Commission block of a chain line (task 72, B07/B09). Null without `commission:view` or when an
 * OWN-scoped viewer lacks coverage over every attributed line; unknown amounts stay null (shown
 * as needs verification) and are never shown as zero — only the occupation total is a known value.
 */
export interface ChainLineCommissionVO {
  rateSnapshot: string | null;
  netCommission: string | null;
  shopProfit: string | null;
  releaseBasis: string | null;
  occupiedTotal: string;
  withdrawals: ChainWithdrawalVO[];
}

/** Order-level funds of the chain (task 72, B07); gated exactly like the line commission block. */
export interface OrderChainFundsVO {
  payAmount: string | null;
  refundTotal: string;
  kolCommission: string;
  /** Weidian settlement net pending M0 (Q-1017-1/2): always null for now, shown as unverified. */
  shopProfit: string | null;
  accountStatus: "POSTED" | "UNVERIFIED";
}

/**
 * One finance flow of the chain (task 72, B08). Amounts are always positive; `direction` carries
 * the sign. Shop receivables wait for the M0 settlement net, so only KOL_COMMISSION rows exist.
 */
export interface ChainFinanceFlowVO {
  flowNo: string;
  orderItemId: string;
  shopName: string | null;
  type: "KOL_COMMISSION" | "SHOP_RECEIVABLE";
  direction: "INCREASE" | "DECREASE";
  amount: string;
  businessAt: string | null;
  transferredAt: string | null;
  withdrawalNo: string | null;
}

/**
 * One product row of the order chain (task 40 §3.4, funds by task 72): copy job → KOL item link →
 * order line → refunds, plus the line commission block; line names come from the order detail
 * loaded alongside.
 */
export interface ChainLineVO {
  orderItemId: string;
  subOrderId: string | null;
  copyJob: ChainCopyJobVO | null;
  link: ChainLinkVO | null;
  quantity: number | null;
  payAmount: string | null;
  refunds: ChainRefundVO[];
  commission: ChainLineCommissionVO | null;
}

/** `GET /order/orders/{id}/chain` (task 40 §3.4; funds and flows by task 72). */
export interface OrderChainVO {
  orderId: string;
  orderNo: string;
  funds: OrderChainFundsVO | null;
  lines: ChainLineVO[];
  financeFlows: ChainFinanceFlowVO[] | null;
}

/** Which of the two sync streams a monitoring row describes. */
export type SyncDataType = "ORDER" | "REFUND";

/** Where the data of one monitoring row came from: the Weidian API or a manual import. */
export type SyncSource = "API" | "IMPORT";

/** One stream (orders or refunds) of a shop's monitoring block (task 40 §3.5). */
export interface KindSyncVO {
  source: SyncSource | null;
  lastSuccessAt: string | null;
  lastError: string | null;
  /** Counts arrive as strings: the backend serializes every long that way (spec 03). */
  failures24h: number | string;
  /** Missing-field record counts by field name (dictionary in design §4.6a). */
  missingFields: Record<string, number | string>;
}

/** The shop's latest import and its declared data cutoff (task 40 §3.5). */
export interface ImportCutoffVO {
  kind: SyncDataType;
  dataCutoffTime: string;
  taskId: string;
}

/** `OrderSyncStateDTO` extended by task 40 §3.5: one shop with per-stream monitoring. */
export interface OrderSyncStateVO {
  shopId: string;
  shopName: string;
  enabled: boolean;
  authorized: boolean;
  lastSuccessAt?: string | null;
  lastError?: string | null;
  orders?: KindSyncVO | null;
  refunds?: KindSyncVO | null;
  lastImport?: ImportCutoffVO | null;
}

/** Which pipeline a failure belongs to (task 40 §3.5). */
export type SyncFailureKind = "ORDER_SYNC" | "REFUND_SYNC" | "ORDER_IMPORT" | "REFUND_IMPORT";

/** One recent sync / import failure (task 40 §3.5); `taskId` links an import task. */
export interface SyncFailureVO {
  id: string;
  shopId: string | null;
  shopName: string | null;
  kind: SyncFailureKind;
  error: string | null;
  taskId: string | null;
  time: string;
}

/** Why a line sits in the manual pool (task 40 §3.6, OrderService.unmatchedReason). */
export type UnmatchedReason = "PENDING" | "CONFLICT" | "NO_MAPPING" | "MAPPING_EXPIRED" | "MATCHED";

/** One mapping candidate sharing the line's Weidian item id (task 40 §3.6). */
export interface EvidenceMappingVO {
  kolItemId: string;
  kolId: string | null;
  kolName: string | null;
  kolCode: string | null;
  linkCode: string | null;
  validFrom: string | null;
  validTo: string | null;
  /** Whether `validFrom <= payTime < validTo` (null end = current). */
  coversPayTime: boolean;
}

/** The recorded manual decision on this line, if any (task 40 §3.6). */
export interface EvidenceManualVO {
  attributionType: ItemAttributionType | null;
  manualReason: string | null;
  attributionTime: string | null;
}

/** `GET /order/unmatched/{orderItemId}/evidence` (task 40 §3.6). */
export interface MatchEvidenceVO {
  orderItemId: string;
  orderId: string;
  orderNo: string;
  shopId: string;
  shopName: string | null;
  merchantNo: string | null;
  weidianItemId: string;
  itemName: string | null;
  skuName: string | null;
  payTime: string | null;
  orderAttributionStatus: AttributionStatus;
  lineAttributionType: string | null;
  unmatchedReason: UnmatchedReason;
  product: { productId: string; productCode: string | null; productName: string | null } | null;
  mappings: EvidenceMappingVO[];
  manual: EvidenceManualVO | null;
}
