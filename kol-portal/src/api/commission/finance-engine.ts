import { cents, decimal, sumMoney, validMoney } from "@/utils/finance";
import { ApiError } from "@/utils/http/error";
import {
  account,
  sales,
  seedConfig,
  seedIncomes,
  seedLines,
  seedPayments,
  seedSettlements,
  seedWithdrawals
} from "./finance-demo";
import type {
  FinanceRole,
  Income,
  IncomeQuery,
  IncomeResult,
  PaymentRegistration,
  Scenario,
  SettlementConfig,
  SettlementQuery,
  WithdrawalQuery,
  WithdrawalRequest
} from "./finance-types";

export function financeError(status: number, errorCode = "COMMON_VALIDATION_FAILED"): never {
  throw new ApiError({ status, errorCode, traceId: "t1023_preview" });
}
export interface FinanceDemoState {
  incomes: typeof seedIncomes;
  payments: typeof seedPayments;
  withdrawals: typeof seedWithdrawals;
  config: SettlementConfig;
  idempotency: [string, string][];
}
export function createFinanceDemo() {
  let incomes = structuredClone(seedIncomes);
  let payments = structuredClone(seedPayments);
  let withdrawals = structuredClone(seedWithdrawals);
  let config = structuredClone(seedConfig);
  const idempotency = new Map<string, string>();
  function guard(role: FinanceRole, scenario: Scenario, write = false) {
    if (scenario === "error") financeError(503, "COMMON_INTERNAL_ERROR");
    if (role === "PLATFORM_ADMIN" || (write && role === "OPERATOR"))
      financeError(403, "AUTH_FORBIDDEN");
  }
  function owned(role: FinanceRole, row: { bdId?: string; kolId?: string; id?: string }) {
    return role === "BD"
      ? row.bdId === "102311"
      : role === "KOL"
        ? (row.kolId ?? row.id) === "102301"
        : true;
  }
  function range(time: string, query: IncomeQuery) {
    return (
      (!query.start || Date.parse(time) >= Date.parse(query.start)) &&
      (!query.end || Date.parse(time) < Date.parse(query.end))
    );
  }
  function page<T>(items: T[], query: { page?: number; size?: number }) {
    const current = query.page ?? 1;
    const size = query.size ?? 20;
    return {
      items: items.slice((current - 1) * size, current * size),
      total: String(items.length),
      page: current,
      size
    };
  }
  function listIncome(role: FinanceRole, scenario: Scenario, query: IncomeQuery): IncomeResult {
    guard(role, scenario);
    const filteredPayments = payments.filter(
      payment =>
        range(payment.paidAt, query) && (!query.paymentType || payment.type === query.paymentType)
    );
    const rows = (scenario === "empty" ? [] : incomes)
      .filter(
        row =>
          owned(role, row) &&
          (!query.keyword ||
            `${row.name} ${row.code} ${row.email}`
              .toLowerCase()
              .includes(query.keyword.toLowerCase())) &&
          (!query.paymentType || filteredPayments.some(payment => payment.kolId === row.id))
      )
      .map(row => {
        const paid = filteredPayments.filter(payment => payment.kolId === row.id);
        const orders = sales.filter(sale => sale.kolId === row.id && range(sale.paidAt, query));
        const result: Income = {
          ...row,
          name:
            scenario === "long"
              ? `${row.name} — International Creative Studio / Lifestyle and Travel Collective`
              : row.name,
          email:
            scenario === "long"
              ? "t1023_extremely.long.creator.contact.for.international.campaigns@example.com"
              : row.email,
          orderCount: new Set(orders.map(order => order.orderNo)).size,
          quantity: orders.reduce((sum, order) => sum + order.quantity, 0),
          paid: sumMoney(paid.map(item => item.amountCny)),
          commissionPaid: sumMoney(
            paid.filter(item => item.type === "COMMISSION").map(item => item.amountCny)
          ),
          prepayPaid: sumMoney(
            paid.filter(item => item.type === "PREPAY").map(item => item.amountCny)
          ),
          lastPaidAt:
            paid
              .map(item => item.paidAt)
              .sort((a, b) => Date.parse(a) - Date.parse(b))
              .at(-1) ?? null
        };
        return result;
      });
    return {
      ...page(rows, query),
      summary: {
        paid: sumMoney(rows.map(row => row.paid)),
        commissionPaid: sumMoney(rows.map(row => row.commissionPaid)),
        prepayPaid: sumMoney(rows.map(row => row.prepayPaid)),
        creators: rows.length,
        orders: new Set(
          sales
            .filter(sale => rows.some(row => row.id === sale.kolId) && range(sale.paidAt, query))
            .map(sale => sale.orderNo)
        ).size,
        quantity: rows.reduce((sum, row) => sum + row.quantity, 0),
        unverified: rows.reduce((sum, row) => sum + row.unverified, 0)
      }
    };
  }
  function incomeDetail(role: FinanceRole, scenario: Scenario, id: string, query: IncomeQuery) {
    guard(role, scenario);
    const source = incomes.find(row => row.id === id);
    if (!source || !owned(role, source)) financeError(404, "COMMON_NOT_FOUND");
    const income = listIncome(role, scenario, {
      ...query,
      keyword: source.code,
      page: 1,
      size: 100
    }).items[0];
    // The detail remains available even when a payment-type filter no longer matches.
    return structuredClone({
      income:
        scenario === "empty"
          ? {
              ...source,
              estimated: "0.00",
              frozen: "0.00",
              available: "0.00",
              occupied: "0.00",
              cumulativePaid: "0.00",
              deducted: "0.00",
              pendingDeduction: "0.00",
              paid: "0.00",
              commissionPaid: "0.00",
              prepayPaid: "0.00",
              unverified: 0
            }
          : (income ?? source),
      lines:
        scenario === "empty"
          ? []
          : seedLines
              .filter(line => line.kol === source.name)
              .map(line =>
                scenario === "long"
                  ? {
                      ...line,
                      product:
                        line.product +
                        " / International limited edition travel collection with extended description",
                      sku: line.sku + "-INTERNATIONAL-LIMITED-EDITION"
                    }
                  : line
              ),
      payments: (scenario === "empty" ? [] : payments).filter(
        row =>
          row.kolId === id &&
          range(row.paidAt, query) &&
          (!query.paymentType || row.type === query.paymentType)
      )
    });
  }
  function project(role: FinanceRole, row: (typeof withdrawals)[number]) {
    const result = structuredClone(row);
    if (!["SUPER_ADMIN", "KOL"].includes(role)) {
      result.account.account = "t1023_***@example.com";
      result.account.holder = "t1023_***";
    }
    if (!["SUPER_ADMIN", "FINANCE"].includes(role)) {
      result.financeNote = "";
      if (result.payment) result.payment.financeNote = "";
      result.history = result.history.map(event => ({
        ...event,
        note: event.action === "PAID" ? "" : event.note
      }));
    }
    return result;
  }
  function listWithdrawals(role: FinanceRole, scenario: Scenario, query: WithdrawalQuery) {
    guard(role, scenario);
    return page(
      (scenario === "empty" ? [] : withdrawals)
        .filter(
          row =>
            owned(role, row) &&
            (!query.applicationNo || row.applicationNo.includes(query.applicationNo)) &&
            (!query.kol ||
              `${row.kolName} ${row.kolCode}`.toLowerCase().includes(query.kol.toLowerCase())) &&
            (!query.type || row.type === query.type) &&
            (!query.status || row.status === query.status)
        )
        .map(row => project(role, row)),
      query
    );
  }
  function withdrawal(role: FinanceRole, scenario: Scenario, id: string) {
    guard(role, scenario);
    const row = withdrawals.find(item => item.id === id);
    if (!row || !owned(role, row)) financeError(404, "COMMON_NOT_FOUND");
    return row;
  }
  function review(
    role: FinanceRole,
    scenario: Scenario,
    id: string,
    decision: "APPROVE" | "REJECT",
    note: string
  ) {
    guard(role, scenario, true);
    if (!["BD", "SUPER_ADMIN", "FINANCE"].includes(role)) financeError(403, "AUTH_FORBIDDEN");
    const row = withdrawal(role, scenario, id);
    if (
      row.status !== "PENDING_REVIEW" &&
      !(
        decision === "REJECT" &&
        row.status === "PENDING_PAYMENT" &&
        ["SUPER_ADMIN", "FINANCE"].includes(role)
      )
    )
      financeError(409, "COMMON_CONFLICT");
    if (decision === "REJECT" && !note.trim()) financeError(400);
    if (decision === "REJECT") release(row.amount);
    row.status = decision === "APPROVE" ? "PENDING_PAYMENT" : "REJECTED";
    row.reviewNote = note;
    row.history.push({
      action: row.status,
      at: new Date().toISOString(),
      actor: `t1023_${role}`,
      note
    });
    return project(role, row);
  }
  function release(amount: string) {
    incomes[0].occupied = decimal(cents(incomes[0].occupied) - cents(amount));
    incomes[0].available = decimal(cents(incomes[0].available) + cents(amount));
  }
  function pay(
    role: FinanceRole,
    scenario: Scenario,
    id: string,
    payload: PaymentRegistration,
    key: string
  ) {
    guard(role, scenario, true);
    if (role !== "SUPER_ADMIN") financeError(403, "AUTH_FORBIDDEN");
    const row = withdrawal(role, scenario, id);
    const fingerprint = JSON.stringify(payload);
    const existing = idempotency.get(`payment:${id}:${key}`);
    if (existing) {
      if (existing !== fingerprint) financeError(409, "COMMON_CONFLICT");
      return structuredClone(row);
    }
    if (row.status !== "PENDING_PAYMENT" || row.paused) financeError(409, "COMMON_CONFLICT");
    if (
      !validMoney(payload.actualAmount) ||
      !validMoney(payload.amountCny) ||
      cents(payload.amountCny) !== cents(row.amount) ||
      payload.actualCurrency !== row.account.currency ||
      !payload.voucher ||
      !payload.method.trim() ||
      (payload.actualCurrency !== "CNY" && !payload.conversionNote.trim()) ||
      !Number.isFinite(Date.parse(payload.paidAt))
    )
      financeError(400);
    row.payment = structuredClone({ ...payload, paidAt: new Date(payload.paidAt).toISOString() });
    row.status = "PAID";
    row.financeNote = payload.financeNote;
    incomes[0].occupied = decimal(cents(incomes[0].occupied) - cents(row.amount));
    incomes[0].cumulativePaid = sumMoney([incomes[0].cumulativePaid, row.amount]);
    payments.push({
      id: `t1023_payment_${row.id}`,
      sourceNo: row.applicationNo,
      withdrawalId: row.id,
      kolId: row.kolId,
      type: "COMMISSION",
      amountCny: row.amount,
      paidAt: new Date(payload.paidAt).toISOString(),
      deduct: false,
      deducted: "0.00",
      pending: "0.00",
      actualAmount: payload.actualAmount,
      actualCurrency: payload.actualCurrency,
      voucher: payload.voucher
    });
    row.history.push({
      action: "PAID",
      at: new Date(payload.paidAt).toISOString(),
      actor: `t1023_${role}`,
      note: payload.financeNote
    });
    idempotency.set(`payment:${id}:${key}`, fingerprint);
    return structuredClone(row);
  }
  function apply(
    role: FinanceRole,
    scenario: Scenario,
    payload: WithdrawalRequest,
    identity?: { name: string; kolCode: string }
  ) {
    guard(role, scenario);
    if (role !== "KOL") financeError(403, "AUTH_FORBIDDEN");
    const fingerprint = JSON.stringify(payload);
    const existing = idempotency.get(`apply:${payload.idempotencyKey}`);
    if (existing) {
      const [id, original] = JSON.parse(existing) as [string, string];
      if (original !== fingerprint) financeError(409, "COMMON_CONFLICT");
      return structuredClone(withdrawal(role, scenario, id));
    }
    if (
      !validMoney(payload.amount) ||
      cents(payload.amount) < cents(config.minimum) ||
      cents(payload.amount) > cents(incomes[0].available) ||
      !payload.account.account.trim() ||
      !payload.account.holder.trim() ||
      (payload.account.type === "PAYPAL" &&
        !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(payload.account.account)) ||
      (payload.account.type === "USDT" &&
        (!payload.account.network || !/^[0-9A-Za-z]{3,128}$/.test(payload.account.account)))
    )
      financeError(400);
    if (payload.previousId && withdrawal(role, scenario, payload.previousId).status !== "REJECTED")
      financeError(409, "COMMON_CONFLICT");
    const id = String(102400 + withdrawals.length);
    const row = structuredClone(seedWithdrawals[0]);
    Object.assign(row, {
      id,
      applicationNo: `t1023_W${id}`,
      kolName: identity?.name ?? row.kolName,
      kolCode: identity?.kolCode ?? row.kolCode,
      amount: payload.amount,
      account: structuredClone(payload.account),
      note: payload.note,
      createdAt: new Date().toISOString(),
      previousId: payload.previousId ?? null,
      reviewNote: "",
      history: [
        {
          action: "SUBMITTED",
          at: new Date().toISOString(),
          actor: identity?.name ?? "t1023_Alex",
          note: payload.note
        }
      ]
    });
    incomes[0].occupied = sumMoney([incomes[0].occupied, payload.amount]);
    incomes[0].available = decimal(cents(incomes[0].available) - cents(payload.amount));
    row.allocations[0].occupied = payload.amount;
    row.allocations[0].remainingSnapshot = incomes[0].available;
    withdrawals.unshift(row);
    idempotency.set(`apply:${payload.idempotencyKey}`, JSON.stringify([id, fingerprint]));
    return structuredClone(row);
  }
  function cancel(role: FinanceRole, scenario: Scenario, id: string) {
    if (role !== "KOL") financeError(403, "AUTH_FORBIDDEN");
    const row = withdrawal(role, scenario, id);
    if (row.status !== "PENDING_REVIEW") financeError(409, "COMMON_CONFLICT");
    row.status = "WITHDRAWN";
    release(row.amount);
    row.history.push({
      action: "WITHDRAWN",
      at: new Date().toISOString(),
      actor: "t1023_Alex",
      note: ""
    });
    return structuredClone(row);
  }
  return {
    snapshot: (): FinanceDemoState => ({
      incomes: structuredClone(incomes),
      payments: structuredClone(payments),
      withdrawals: structuredClone(withdrawals),
      config: structuredClone(config),
      idempotency: [...idempotency.entries()]
    }),
    hydrate: (state: FinanceDemoState) => {
      if (
        !Array.isArray(state.incomes) ||
        !Array.isArray(state.payments) ||
        !Array.isArray(state.withdrawals) ||
        !Array.isArray(state.idempotency) ||
        !state.config
      )
        financeError(503, "COMMON_INTERNAL_ERROR");
      incomes = structuredClone(state.incomes);
      payments = structuredClone(state.payments);
      withdrawals = structuredClone(state.withdrawals);
      config = structuredClone(state.config);
      idempotency.clear();
      for (const [key, value] of state.idempotency) idempotency.set(key, value);
    },
    listIncome,
    incomeDetail,
    listWithdrawals,
    withdrawal: (role: FinanceRole, scenario: Scenario, id: string) =>
      project(role, withdrawal(role, scenario, id)),
    review,
    pay,
    apply,
    cancel,
    accounts: () => [
      { ...structuredClone(account), id: "102399" },
      {
        id: "102398",
        type: "USDT" as const,
        account: "T1023DemoOnlyAddress00000000000000000",
        currency: "USDT",
        network: "TRC20",
        holder: "t1023_Alex"
      }
    ],
    getConfig: (role: FinanceRole, scenario: Scenario) => {
      guard(role, scenario);
      return structuredClone(config);
    },
    saveConfig: (role: FinanceRole, scenario: Scenario, value: SettlementConfig) => {
      guard(role, scenario, true);
      if (role !== "SUPER_ADMIN") financeError(403, "AUTH_FORBIDDEN");
      if (
        !validMoney(value.minimum, false) ||
        value.channels.some(row => !Number.isInteger(row.days) || row.days < 0 || row.days > 365)
      )
        financeError(400);
      if (value.version !== config.version) financeError(409, "COMMON_CONFLICT");
      config = structuredClone({ ...value, version: `${config.version}-NEXT` });
      return structuredClone(config);
    },
    settlements: (role: FinanceRole, scenario: Scenario, query: SettlementQuery) => {
      guard(role, scenario);
      const allowed = ["t1023_Alex Morgan", "t1023_Sam Rivera"];
      const rows = (scenario === "empty" ? [] : seedSettlements)
        .filter(
          row =>
            (!query.orderNo || row.orderNo.includes(query.orderNo)) &&
            (!query.channel || row.channel === query.channel) &&
            (!query.shop || row.shop.includes(query.shop))
        )
        .flatMap(row => {
          if (role !== "BD") return [row];
          const lines = row.lines.filter(line => allowed.includes(line.kol));
          if (!lines.length) return [];
          if (lines.length === row.lines.length) return [row];
          const commission = sumMoney(lines.map(line => line.commission ?? "0.00"));
          return [
            {
              ...row,
              kolNames: [...new Set(lines.map(line => line.kol))],
              lines,
              rates: [
                ...new Set(
                  lines.map(line => line.rate).filter((rate): rate is string => rate !== null)
                )
              ],
              payment: null,
              refund: null,
              net: null,
              retained: null,
              commission,
              scopeLimited: true
            }
          ];
        });
      return page(structuredClone(rows), query);
    },
    reset: () => {
      incomes = structuredClone(seedIncomes);
      payments = structuredClone(seedPayments);
      withdrawals = structuredClone(seedWithdrawals);
      config = structuredClone(seedConfig);
      idempotency.clear();
    }
  };
}
