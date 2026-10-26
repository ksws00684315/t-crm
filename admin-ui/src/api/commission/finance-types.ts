export type FinanceRole = "SUPER_ADMIN" | "OPERATOR" | "BD" | "FINANCE" | "PLATFORM_ADMIN" | "KOL";
export type Scenario = "data" | "empty" | "error" | "long";
export type WithdrawalStatus =
  "PENDING_REVIEW" | "PENDING_PAYMENT" | "PAID" | "REJECTED" | "WITHDRAWN";
export interface Page<T> {
  items: T[];
  total: string;
  page: number;
  size: number;
}
export interface IncomeQuery {
  keyword?: string;
  paymentType?: string;
  start?: string;
  end?: string;
  page?: number;
  size?: number;
}
export interface Metrics {
  estimated: string;
  frozen: string;
  available: string;
  occupied: string;
  cumulativePaid: string;
  deducted: string;
  pendingDeduction: string;
  paid: string;
  commissionPaid: string;
  prepayPaid: string;
  unverified: number;
}
export interface Income extends Metrics {
  id: string;
  name: string;
  code: string;
  email: string;
  bdId: string;
  bd: string;
  orderCount: number;
  quantity: number;
  lastPaidAt: string | null;
}
export interface IncomeResult extends Page<Income> {
  summary: {
    paid: string;
    commissionPaid: string;
    prepayPaid: string;
    creators: number;
    orders: number;
    quantity: number;
    unverified: number;
  };
}
export interface CommissionLine {
  id: string;
  orderNo: string;
  itemId: string;
  product: string;
  sku: string;
  image: string;
  base: string | null;
  rate: string | null;
  rateVersion: string | null;
  ruleVersion: string;
  commission: string | null;
  adjustment: string | null;
  releaseBasis: "CONFIRMED" | "FALLBACK" | "UNSETTLED" | "AFTER_SALES" | "UNVERIFIED";
  businessAt: string;
  direction: "CREDIT" | "DEBIT";
  subject: string;
  kol: string;
}
export interface Payment {
  withdrawalId?: string;
  id: string;
  sourceNo: string;
  type: "COMMISSION" | "PREPAY";
  kolId: string;
  amountCny: string;
  paidAt: string;
  deduct: boolean;
  deducted: string;
  pending: string;
  actualAmount?: string;
  actualCurrency?: string;
  voucher?: string;
}
export interface IncomeDetail {
  income: Income;
  lines: CommissionLine[];
  payments: Payment[];
}
export interface Allocation {
  id: string;
  orderNo: string;
  image: string;
  product: string;
  sku: string;
  occupied: string;
  remainingSnapshot: string;
  /** Immutable remainder of this order after allocation; never inferred from a wallet. */
  orderRemainingSnapshot?: string | null;
}
export interface WithdrawalOrder {
  id: string;
  orderNo: string;
  products: Pick<Allocation, "id" | "image" | "product" | "sku">[];
  occupied: string;
  remainingSnapshot: string | null;
}
export interface AccountSnapshot {
  id?: string;
  qrFileId?: string;
  type: "PAYPAL" | "USDT";
  account: string;
  currency: string;
  network: string;
  holder: string;
}
export interface Withdrawal {
  id: string;
  applicationNo: string;
  kolId: string;
  kolName: string;
  kolCode: string;
  bdId: string;
  bdName?: string;
  type: "COMMISSION";
  amount: string;
  currency: "CNY";
  status: WithdrawalStatus;
  createdAt: string;
  paused: boolean;
  pauseReason: string | null;
  account: AccountSnapshot;
  note: string;
  reviewNote: string;
  operationsNote?: string | null;
  financeNote: string;
  allocations: Allocation[];
  estimatedTotal?: string;
  availableTotal?: string;
  history: { action: string; at: string; actor: string; actorId?: string; note: string }[];
  payment: PaymentRegistration | null;
  previousId: string | null;
}
export interface WithdrawalQuery {
  applicationNo?: string;
  kolId?: string;
  type?: "COMMISSION" | "PREPAY";
  status?: string;
  page?: number;
  size?: number;
}
export interface FinanceTask {
  id: string;
  type: "COMMISSION" | "PREPAY";
  applicationNo: string;
  kolId: string;
  kolCode: string;
  kolName: string;
  amount: string;
  currency: "CNY";
  status: string;
  createdAt: string;
  paused?: boolean;
  history?: Withdrawal["history"];
}
export interface PaymentRegistration {
  actualAmount: string;
  actualCurrency: string;
  amountCny: string;
  paidAt: string;
  method: string;
  financeNote: string;
  conversionNote: string;
  voucher: string;
}
export interface WithdrawalRequest {
  payoutAccountId?: string;
  amount: string;
  account: AccountSnapshot;
  note: string;
  previousId?: string;
  idempotencyKey: string;
}
export interface Settlement {
  scopeLimited?: boolean;
  id: string;
  orderNo: string;
  channel: string;
  shop: string;
  shopId?: string;
  kolNames: string[];
  payment: string | null;
  refund: string | null;
  net: string | null;
  commission: string | null;
  rates: string[];
  retained: string | null;
  status: "POSTED" | "UNVERIFIED";
  businessAt: string;
  lines: CommissionLine[];
}
export interface SettlementQuery {
  orderNo?: string;
  channel?: string;
  shopId?: string;
  page?: number;
  size?: number;
}
export interface SettlementConfig {
  currency: "CNY";
  minimum: string;
  version: string;
  channels: { code: string; name: string; basis: string; days: number }[];
}
