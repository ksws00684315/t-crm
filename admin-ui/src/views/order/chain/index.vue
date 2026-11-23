<script setup lang="ts">
import RateSupplementDialog from "../RateSupplementDialog.vue";
import { Document, ShoppingCart, Refresh, Money, Wallet, Right } from "@element-plus/icons-vue";
import { computed, onMounted, ref } from "vue";
import { useI18n } from "vue-i18n";
import { useRoute, useRouter } from "vue-router";
import { getOrder, getOrderChain } from "@/api/order/order";
import type { ChainLineVO, ChainWithdrawalVO, OrderChainVO, OrderDetailVO } from "@/api/types";
import CopyText from "@/components/CopyText.vue";
import DataTable, { type DataColumn } from "@/components/DataTable.vue";
import { useUserStore } from "@/stores/user";
import { formatInstant, formatNameWithCode, formatAmountWithCurrency } from "@/utils/format";
import { cents, decimal, sumMoney, validMoney } from "@/utils/finance";
import { notifyError } from "@/utils/notify";
import { channelText, orderStatusText, orderRefundStatusText } from "@/utils/order";
import { formatRate } from "@/utils/rate";
import OrderProductImage from "../OrderProductImage.vue";

defineOptions({ name: "OrderChainPage" });

const { t, te } = useI18n();

function shopLabel(name: string | null | undefined, code: string | null | undefined): string {
  if (!name && !code) return "-";
  return code ? formatNameWithCode(name ?? "-", code) : (name ?? "-");
}

const route = useRoute();
const router = useRouter();
const userStore = useUserStore();

const rateDialog = ref(false);
const rateTarget = ref<ChainLineVO | null>(null);
function canSupplement(line: ChainLineVO): boolean {
  return (
    userStore.isSuperAdmin &&
    !!detail.value?.payTime &&
    !!lineItem(line)?.kolCode &&
    !!line.commission &&
    line.commission.rateSnapshot == null
  );
}
function openSupplement(line: ChainLineVO) {
  rateTarget.value = line;
  rateDialog.value = true;
}
const loading = ref(false);
const chain = ref<OrderChainVO | null>(null);
/** Header context; degrades to the bare chain when the detail call fails. */
const detail = ref<OrderDetailVO | null>(null);

/** Financial data needs commission:view; OWN scopes additionally see nothing on mixed orders. */
const canSeeCommission = computed(() => userStore.can("commission:view"));
/**
 * Successful-refund total summed on the client, deduplicated by refund id so one refund spanning
 * several lines counts once (B07; same rule as the server's dedup, for viewers without funds).
 */
const completedRefundTotal = computed(() => {
  const amounts = refundRecords.value
    .filter(refund => refund.refundStatus === "SUCCESS")
    .map(refund => refund.amount);
  return sumKnownAmounts(amounts);
});

const orderLines = computed(() => chain.value?.lines ?? []);
const orderCreators = computed(() => {
  const creators = new Map<string, { name: string | null; code: string | null }>();
  for (const line of orderLines.value) {
    const item = lineItem(line);
    const creator = item?.kolName || item?.kolCode ? item : line.link;
    const identity = creator?.kolCode || creator?.kolName;
    if (!creator || !identity) continue;
    const key = creator.kolId || identity;
    const previous = creators.get(key);
    creators.set(key, {
      name: creator.kolName || previous?.name || null,
      code: creator.kolCode || previous?.code || null
    });
  }
  const order = detail.value;
  const identity = order?.kolCode || order?.kolName;
  if (!creators.size && order && identity) {
    creators.set(order.kolId || identity, {
      name: order.kolName,
      code: order.kolCode
    });
  }
  return creators;
});
const copyJobs = computed(() => [
  ...new Map(
    orderLines.value.flatMap(({ copyJob }) => (copyJob ? [[copyJob.jobCode, copyJob]] : []))
  ).values()
]);
const refundRecords = computed(() => [
  ...new Map(
    orderLines.value.flatMap(line => line.refunds).map(refund => [refund.id, refund])
  ).values()
]);
const withdrawalRecords = computed(() => {
  if (!canSeeCommission.value || !chain.value?.funds) return [];
  const byApplication = new Map<string, ChainWithdrawalVO[]>();
  for (const record of orderLines.value.flatMap(line => line.commission?.withdrawals ?? [])) {
    const allocations = byApplication.get(record.withdrawalId);
    if (allocations) allocations.push(record);
    else byApplication.set(record.withdrawalId, [record]);
  }
  // One application can span multiple orders and items. These allocations belong to this order.
  return [...byApplication.values()].map(allocations => ({
    ...allocations[0]!,
    orderShare: sumKnownAmounts(allocations.map(record => record.occupiedCny))
  }));
});
const paidWithdrawalTotal = computed(() => {
  const paid = withdrawalRecords.value.filter(record => record.status === "PAID");
  return paid.length && paid.every(record => record.paidAt)
    ? sumKnownAmounts(paid.map(record => record.orderShare))
    : null;
});
const occupiedTotal = computed(() => {
  const amounts = orderLines.value.map(line => line.commission?.occupiedTotal);
  return canSeeCommission.value && amounts.length ? sumKnownAmounts(amounts) : null;
});
const refundTotalAmount = computed(
  () => detail.value?.refundAmount ?? chain.value?.funds?.refundTotal ?? completedRefundTotal.value
);
const platform = computed(() =>
  canSeeCommission.value ? (chain.value?.weidianReconciliation ?? null) : null
);
const flows = computed(() => (Array.isArray(platform.value?.flows) ? platform.value.flows : []));
const flowsView = computed(() => {
  if (!canSeeCommission.value) return "noPermission";
  if (userStore.isOwnScope && !chain.value?.funds) return "scopeLimited";
  if (!Array.isArray(platform.value?.flows)) return "incomplete";
  return "table";
});
const settlementState = computed(() => {
  if (!platform.value) return "—";
  if (platform.value.settlementAmount != null) return t("order.chain.orderMoneyStatus.SETTLED");
  if (flows.value.some(flow => flow.type === "GOODS_INCOME" || flow.type === "REFUND_DEDUCTION"))
    return t("finance.unverified");
  return (detail.value ? detail.value.payTime : chain.value?.funds?.orderSettlementStatus)
    ? t("order.chain.orderMoneyStatus.PENDING_SETTLEMENT")
    : "—";
});

function countRecords(count: number) {
  return t("order.chain.recordCount", { count });
}
function copyJobState(status?: string | null): string {
  const value = status?.trim().toLowerCase();
  const key = `copyJob.jobStatus.${value}`;
  return value && te(key) ? t(key) : status || t("finance.unverified");
}
function copyJobTagType(status?: string | null) {
  if (status === "completed") return "success";
  if (status === "partial_failed") return "warning";
  if (status === "failed") return "danger";
  return "info";
}
function financeStatusText(status?: string | null) {
  const key = `order.chain.commissionMoneyStatus.${status}`;
  return status && te(key) ? t(key) : "—";
}
function displayAmount(amount?: string | null) {
  return amount == null ? "—" : formatAmountWithCurrency(amount);
}
function sumKnownAmounts(amounts: readonly (string | null | undefined)[]) {
  return amounts.every((amount): amount is string => amount != null && validMoney(amount, false))
    ? sumMoney(amounts)
    : null;
}
function displayTime(value?: string | null) {
  return value ? formatInstant(value) : "—";
}

function amountTone(row: FundMovement) {
  if (row.amount == null || !validMoney(row.amount.replace(/^[+-]/, ""), false) || !row.direction)
    return undefined;
  return row.direction === "receivableIncrease" || row.direction === "commissionPayableDecrease"
    ? "chain-money--positive"
    : "chain-money--negative";
}

type FundMovement = {
  id: string;
  type: "goodsFund" | "commissionFund";
  direction:
    | "receivableIncrease"
    | "receivableDecrease"
    | "commissionPayableIncrease"
    | "commissionPayableDecrease"
    | null;
  amount: string | null;
  status: string;
  time: string | null;
  reference: string | null;
  withdrawals?: { id: string; number: string }[];
};

const activeWithdrawals = computed(() =>
  withdrawalRecords.value.filter(record =>
    ["PENDING_REVIEW", "PENDING_PAYMENT", "PAID"].includes(record.status)
  )
);

/** Split the verified accrual into unreleased and released, unoccupied amounts. */
const commissionBalances = computed(() => {
  const funds = chain.value?.funds;
  const financeFlows = chain.value?.financeFlows;
  if (
    !canSeeCommission.value ||
    !funds?.financeStatus ||
    funds.financeStatus === "UNVERIFIED" ||
    (funds.financeStatus !== "REFUNDING" && detail.value?.commission?.afterSales) ||
    occupiedTotal.value == null
  )
    return null;

  // An open refund blocks availability while preserving the accrued amount.
  if (funds.financeStatus === "REFUNDING") {
    const amount = funds.financeAmount;
    if (amount == null || !validMoney(amount, false)) return null;
    const remaining = cents(amount) - cents(occupiedTotal.value);
    return { frozen: remaining > 0n ? decimal(remaining) : null, available: null };
  }

  if (
    !funds.kolCommission ||
    !validMoney(funds.kolCommission, false) ||
    !Array.isArray(financeFlows)
  )
    return null;

  let released = 0n;
  for (const flow of financeFlows) {
    if (flow.type !== "KOL_COMMISSION" || flow.withdrawalNo != null) continue;
    if (flow.amount == null || !validMoney(flow.amount, false) || !flow.direction) return null;
    released += flow.direction === "INCREASE" ? cents(flow.amount) : -cents(flow.amount);
  }

  const occupied = cents(occupiedTotal.value);
  const accrued = cents(funds.kolCommission);
  // A withdrawal referencing missing ledger data cannot establish an available balance.
  if (released < occupied) return null;
  const frozen = accrued - released;
  const available = (accrued < released ? accrued : released) - occupied;
  return {
    frozen: frozen > 0n ? decimal(frozen) : null,
    available: available > 0n ? decimal(available) : null
  };
});

const fundMovements = computed<FundMovement[]>(() => {
  const events = chain.value?.financeFlows ?? [];
  const settlement = events.find(event => event.type === "ORDER_SETTLEMENT");
  const netSettlement = platform.value?.settlementAmount;
  const hasSettlement = settlement != null && netSettlement != null;
  const hasRefund = events.some(
    event => event.orderStatus === "DEDUCTION" && event.type === "SHOP_RECEIVABLE"
  );
  const rows: FundMovement[] = flows.value
    .filter(
      flow =>
        !(hasSettlement && flow.type === "GOODS_INCOME") &&
        !(hasRefund && flow.type === "REFUND_DEDUCTION")
    )
    .map(flow => {
      const expense = flow.type === "COMMISSION_EXPENSE";
      return {
        id: `weidian-${flow.id}`,
        type: expense ? "commissionFund" : "goodsFund",
        direction: expense
          ? "commissionPayableDecrease"
          : flow.type === "GOODS_INCOME"
            ? "receivableIncrease"
            : "receivableDecrease",
        amount:
          flow.type === "GOODS_INCOME" && flow.amount && validMoney(flow.amount)
            ? `+${flow.amount}`
            : flow.amount,
        status:
          flow.amount == null
            ? t("finance.unverified")
            : t(
                expense
                  ? "order.chain.platformExpense"
                  : flow.type === "REFUND_DEDUCTION"
                    ? "order.chain.goodsRefunded"
                    : "order.chain.goodsSettleable"
              ),
        time: flow.type === "GOODS_INCOME" ? (flow.settlementAt ?? flow.tradeTime) : flow.tradeTime,
        reference: flow.settlementNo
      };
    });
  if (hasSettlement) {
    rows.push({
      id: settlement.flowNo,
      type: "goodsFund",
      direction: null,
      amount: netSettlement,
      status: t("order.chain.goodsSettleable"),
      time: settlement.businessAt,
      reference: settlement.settlementNo ?? null
    });
  }
  const releases = events.filter(
    flow =>
      flow.type === "KOL_COMMISSION" &&
      flow.flowNo.startsWith("CL") &&
      flow.direction === "INCREASE" &&
      flow.businessAt &&
      flow.amount != null
  );
  const completedRefunds = new Set(
    events
      .filter(flow => flow.type === "SHOP_RECEIVABLE" && flow.flowNo.startsWith("SRF"))
      .map(flow => flow.flowNo.slice(3))
  );
  for (const flow of events) {
    if (
      flow.type === "REFUND_REQUEST" &&
      (flow.orderStatus !== "REFUNDING" || completedRefunds.has(flow.flowNo.slice(3)))
    )
      continue;
    const request = flow.type === "REFUND_REQUEST";
    const refund = flow.type === "SHOP_RECEIVABLE" && flow.orderStatus === "DEDUCTION";
    const preIncomeStatus = {
      PRE_INCOME: "preIncome",
      PRE_INCOME_CHANGE: "preIncomeChange",
      PRE_INCOME_CLOSED: "preIncomeClosed",
      PRE_INCOME_PAID: "preIncomePaid"
    } as const;
    const preIncome = Object.entries(preIncomeStatus).find(([key]) => key === flow.orderStatus);
    const receivable =
      flow.type === "SHOP_RECEIVABLE" && (preIncome || flow.direction === "INCREASE");
    const accrual = flow.type === "COMMISSION_ACCRUAL";
    const clawback = flow.type === "COMMISSION_REFUND";
    if (!receivable && !accrual && !clawback && !request && !refund) continue;
    const status = request
      ? "refundInProgress"
      : refund
        ? "goodsRefunded"
        : receivable
          ? (preIncome?.[1] ?? "frozenState")
          : accrual
            ? "frozenState"
            : "goodsRefunded";
    rows.push({
      id: flow.flowNo,
      type: receivable || refund || request ? "goodsFund" : "commissionFund",
      direction: request
        ? null
        : clawback
          ? "commissionPayableDecrease"
          : accrual
            ? "commissionPayableIncrease"
            : flow.direction === "DECREASE"
              ? "receivableDecrease"
              : "receivableIncrease",
      amount:
        flow.direction === "DECREASE" && flow.amount != null ? `-${flow.amount}` : flow.amount,
      status: t(`order.chain.${status}`),
      time: flow.businessAt,
      reference: null
    });
  }
  const withdrawals = activeWithdrawals.value;
  const paid = withdrawals.filter(record => record.status === "PAID" && record.paidAt);
  const pending = withdrawals.filter(record => record.status !== "PAID");
  const unverified = withdrawals.some(record => record.status === "PAID" && !record.paidAt);
  const paidAmount = paid.length ? sumKnownAmounts(paid.map(record => record.orderShare)) : null;
  const pendingAmount = pending.length
    ? sumKnownAmounts(pending.map(record => record.orderShare))
    : null;
  if (withdrawals.length || (hasSettlement && releases.length)) {
    const times = [
      ...paid.map(record => record.paidAt),
      ...releases.map(flow => flow.businessAt)
    ].filter((time): time is string => time != null);
    const releasedAmount = sumKnownAmounts(releases.map(flow => flow.amount));
    const commission = creatorCommission.value;
    const payable =
      commission != null && validMoney(commission, false) ? commission : releasedAmount;
    const partial =
      paid.length > 0 &&
      (pending.length > 0 ||
        (paidAmount != null && payable != null && cents(payable) > cents(paidAmount)));
    rows.push({
      id: "commission-payout",
      type: "commissionFund",
      direction: paid.length && paidAmount != null ? "commissionPayableDecrease" : null,
      amount: unverified
        ? null
        : paid.length
          ? paidAmount != null
            ? `-${paidAmount}`
            : null
          : pending.length
            ? pendingAmount
            : releasedAmount,
      status: unverified
        ? t("finance.unverified")
        : paid.length
          ? t(
              partial
                ? "order.chain.partiallyWithdrawn"
                : "order.chain.commissionMoneyStatus.WITHDRAWN"
            )
          : pending.length
            ? t("order.chain.withdrawalInProgress")
            : t("order.chain.releasedCommission"),
      time: times.sort((a, b) => Date.parse(b) - Date.parse(a))[0] ?? null,
      reference: null,
      withdrawals: withdrawals.map(record => ({
        id: record.withdrawalId,
        number: record.applicationNo
      }))
    });
  }
  return rows.sort((left, right) => {
    const leftTime = left.time ? Date.parse(left.time) : Number.POSITIVE_INFINITY;
    const rightTime = right.time ? Date.parse(right.time) : Number.POSITIVE_INFINITY;
    return leftTime === rightTime ? 0 : leftTime - rightTime;
  });
});

const orderSales = computed(() =>
  detail.value?.payTime || (!detail.value && chain.value?.funds?.payAmount != null)
    ? (detail.value?.payAmount ?? chain.value?.funds?.payAmount ?? null)
    : null
);
const commissionableAmount = computed(() => detail.value?.commission?.commissionableAmount ?? null);
const creatorCommission = computed(
  () => chain.value?.funds?.kolCommission ?? detail.value?.commission?.estimatedCommission ?? null
);
const commissionRates = computed(() => {
  if (!canSeeCommission.value || !chain.value?.funds) return [];
  return [
    ...new Set(
      detail.value?.commission?.rates ??
        orderLines.value.flatMap(line =>
          line.commission?.rateSnapshot == null ? [] : [line.commission.rateSnapshot]
        )
    )
  ];
});
const commissionState = computed(() => {
  const currentCommission = canSeeCommission.value ? chain.value?.funds?.commissionStatus : null;
  return activeWithdrawals.value.some(record => record.status !== "PAID")
    ? t("order.chain.withdrawalInProgress")
    : paidWithdrawalTotal.value != null && commissionBalances.value?.available
      ? t("order.chain.partiallyWithdrawn")
      : paidWithdrawalTotal.value != null || currentCommission === "WITHDRAWN"
        ? financeStatusText("WITHDRAWN")
        : commissionBalances.value?.available
          ? t("order.chain.releasedCommission")
          : commissionBalances.value?.frozen
            ? financeStatusText("FROZEN")
            : financeStatusText(currentCommission);
});
const stages = computed(() => {
  const openRefunds = refundRecords.value.filter(
    refund => refund.refundStatus === "PROCESSING" || refund.refundStatus === "UNKNOWN"
  );
  const pendingAmount = openRefunds.length
    ? sumKnownAmounts(openRefunds.map(refund => refund.amount))
    : null;
  return [
    {
      key: "copy",
      icon: Document,
      label: t("order.chain.copyJob"),
      state: t("order.chain.notLinked"),
      amount: null
    },
    {
      key: "order",
      icon: ShoppingCart,
      label: t("order.chain.completedOrder"),
      state: detail.value ? orderStatusText(detail.value.orderStatus) : "—",
      amount: displayAmount(orderSales.value)
    },
    {
      key: "refund",
      icon: Refresh,
      label: t("order.chain.refunds"),
      state: detail.value
        ? orderRefundStatusText(detail.value.refundStatus)
        : countRecords(refundRecords.value.length),
      amount: displayAmount(pendingAmount ?? refundTotalAmount.value)
    },
    {
      key: "settlement",
      icon: Money,
      label: t("order.chain.shopSettlement"),
      state: settlementState.value,
      amount: fundsText(commissionableAmount.value)
    },
    {
      key: "withdrawal",
      icon: Wallet,
      label: t("order.chain.commissionBlock"),
      state: commissionState.value,
      amount: fundsText(creatorCommission.value)
    }
  ];
});

/**
 * Text for a gated fund value: the amount when known, otherwise the reason it is hidden — no
 * permission, OWN scope on a mixed order — or the unverified placeholder when the funds block is
 * visible but the amount itself waits for the M0 Weidian settlement net (never a fake zero).
 */
function fundsText(value: string | null | undefined): string {
  if (!canSeeCommission.value) return t("order.chain.noCommissionPermission");
  if (!chain.value?.funds) {
    if (userStore.isOwnScope) return t("finance.scopeLimited");
  }
  return value == null ? t("finance.unverified") : formatAmountWithCurrency(value);
}

/** Line titles come from the detail items matched by order-item id. */
function lineName(line: ChainLineVO): string {
  const item = detail.value?.items.find(candidate => candidate.id === line.orderItemId);
  return item?.itemName ?? t("order.detail.itemName");
}

function lineSku(line: ChainLineVO): string | null {
  return detail.value?.items.find(candidate => candidate.id === line.orderItemId)?.skuName ?? null;
}

function lineItem(line: ChainLineVO) {
  return detail.value?.items.find(item => item.id === line.orderItemId);
}
function lineNetSales(line: ChainLineVO) {
  const item = lineItem(line);
  const paid = item?.payAmount ?? line.payAmount;
  const refund = item?.refundAmount;
  return paid != null && refund != null && validMoney(paid, false) && validMoney(refund, false)
    ? decimal(cents(paid) - cents(refund))
    : null;
}

function lineStatus(line: ChainLineVO): string {
  const status = lineItem(line)?.status;
  if (!status) return "-";
  const key = `order.line.status.${status}`;
  if (te(key)) return t(key);
  const orderKey = `order.status.${status}`;
  return te(orderKey) ? t(orderKey) : status;
}

const summaryColumns = computed<DataColumn[]>(() => [
  { key: "image", label: t("order.chain.productImage"), width: 76 },
  { key: "product", align: "left", label: t("order.chain.productNameSku"), minWidth: 178 },
  { key: "status", label: t("common.status"), minWidth: 90, align: "left" },
  { key: "unitPrice", label: t("order.detail.unitPrice"), minWidth: 104, amount: true },
  { key: "quantity", label: t("order.line.quantity"), minWidth: 80 },
  { key: "totalAmount", label: t("order.chain.itemTotalPrice"), minWidth: 116, amount: true },
  { key: "payAmount", label: t("order.chain.itemPaidAmount"), minWidth: 110, amount: true },
  { key: "refundAmount", label: t("order.chain.refundAmountLabel"), minWidth: 110, amount: true },
  { key: "netSales", label: t("order.chain.netSalesLabel"), minWidth: 110, amount: true }
]);

const flowColumns = computed<DataColumn[]>(() => [
  { key: "type", label: t("order.chain.financeType"), minWidth: 125, align: "left" },
  { key: "direction", label: t("order.chain.direction"), minWidth: 132 },
  { key: "amount", label: t("order.chain.financeAmount"), minWidth: 114, amount: true },
  { key: "status", label: t("common.status"), minWidth: 133 },
  { key: "time", label: t("order.chain.movementTime"), minWidth: 172 },
  { key: "reference", label: t("order.chain.settlementWithdrawalNo"), minWidth: 184 }
]);

function openWithdrawal(withdrawalId: string): void {
  void router.push({ name: "FundPayouts", query: { withdrawalId } });
}

async function load(): Promise<void> {
  const id = String(route.params.id ?? "");
  if (!id) return;
  loading.value = true;
  chain.value = null;
  detail.value = null;
  try {
    const [chainResult, detailResult] = await Promise.allSettled([getOrderChain(id), getOrder(id)]);
    if (chainResult.status === "fulfilled") {
      chain.value = chainResult.value;
    } else {
      notifyError(chainResult.reason);
    }
    if (detailResult.status === "fulfilled") {
      detail.value = detailResult.value;
    }
  } finally {
    loading.value = false;
  }
}

onMounted(load);
</script>

<template>
  <div v-loading="loading" class="chain-page">
    <h1 v-if="!chain" class="chain-page__heading">{{ t("order.chain.orderInfoSection") }}</h1>
    <RateSupplementDialog
      v-if="chain && rateTarget"
      v-model="rateDialog"
      :order-id="chain.orderId"
      :order-no="chain.orderNo"
      :item-id="rateTarget.orderItemId"
      :item-name="lineName(rateTarget)"
      @saved="load"
    />
    <template v-if="chain">
      <header class="chain-page__card" data-test="chain-header">
        <h1 class="chain-page__heading">{{ t("order.chain.orderInfoSection") }}</h1>
        <div class="chain-header__title">
          <CopyText :value="chain.orderNo" />
          <el-tag v-if="detail" effect="light">{{ orderStatusText(detail.orderStatus) }}</el-tag>
        </div>
        <dl class="chain-meta">
          <div>
            <dt>{{ t("order.channel") }}</dt>
            <dd>{{ detail ? channelText(detail.channel) : "—" }}</dd>
          </div>
          <div>
            <dt>{{ t("order.shop") }}</dt>
            <dd>{{ shopLabel(detail?.shopName, detail?.shopCode) }}</dd>
          </div>
          <div>
            <dt>{{ t("order.detail.orderTime") }}</dt>
            <dd>{{ displayTime(detail?.orderTime) }}</dd>
          </div>
          <div>
            <dt>{{ t("order.payTime") }}</dt>
            <dd>{{ displayTime(detail?.payTime) }}</dd>
          </div>
        </dl>
        <dl class="chain-meta chain-meta--creator">
          <div data-test="chain-creators">
            <dt>{{ t("order.chain.creatorName") }}</dt>
            <dd v-if="orderCreators.size" class="chain-creators">
              <span v-for="[key, creator] in orderCreators" :key="key" data-test="identity-name">{{
                creator.name || "—"
              }}</span>
            </dd>
            <dd v-else>—</dd>
          </div>
          <div>
            <dt>{{ t("order.chain.creatorId") }}</dt>
            <dd v-if="orderCreators.size" class="chain-creators">
              <CopyText
                v-for="[key, creator] in orderCreators"
                :key="key"
                :value="creator.code || '—'"
                data-test="identity-code"
                hide-tooltip
              />
            </dd>
            <dd v-else>—</dd>
          </div>
          <div>
            <dt>{{ t("order.chain.summaryRate") }}</dt>
            <dd v-if="commissionRates.length">
              {{ commissionRates.map(rate => formatRate(rate)).join(" / ") }}
              <el-tag
                v-if="orderLines.some(line => line.commission?.rateSupplemented)"
                size="small"
                type="warning"
                data-test="rate-supplemented"
                >{{ t("order.chain.rateSupplemented") }}</el-tag
              >
            </dd>
            <dd v-else-if="canSeeCommission && orderLines.some(canSupplement)">
              <el-button
                v-for="line in orderLines.filter(canSupplement)"
                :key="line.orderItemId"
                link
                type="primary"
                data-test="rate-supplement"
                @click="openSupplement(line)"
                >{{ t("order.rateSupplement.title") }}</el-button
              >
            </dd>
            <dd v-else>{{ fundsText(null) }}</dd>
          </div>
          <div>
            <dt>{{ t("order.chain.inviterLabel") }}</dt>
            <dd>{{ detail?.inviterName || "—" }}</dd>
          </div>
        </dl>
      </header>

      <section class="chain-page__card" data-test="chain-order-info">
        <h2 class="chain-page__title">{{ t("order.chain.fullJourney") }}</h2>
        <ol class="chain-stages" :aria-label="t('order.chain.fullJourney')">
          <li v-for="(stage, index) in stages" :key="stage.key">
            <div
              class="chain-stage"
              :data-test="`chain-stage-${stage.key}`"
              :class="{ 'chain-stage--order': stage.key === 'order' }"
            >
              <span class="chain-stage__label">
                <el-icon aria-hidden="true"><component :is="stage.icon" /></el-icon>
                {{ stage.label }}
              </span>
              <div v-if="stage.key === 'copy' && copyJobs.length" class="chain-stage__jobs">
                <div v-for="job in copyJobs" :key="job.jobCode" class="chain-stage__heading">
                  <CopyText :value="job.jobCode" hide-tooltip />
                  <el-tag
                    size="small"
                    :type="copyJobTagType(job.status?.trim().toLowerCase() ?? null)"
                    >{{ copyJobState(job.status) }}</el-tag
                  >
                </div>
              </div>
              <div v-else class="chain-stage__heading">
                <span v-if="stage.amount">{{ stage.amount }}</span>
                <el-tag size="small" :type="stage.key === 'order' ? 'primary' : 'info'">{{
                  stage.state
                }}</el-tag>
              </div>
              <span
                v-if="
                  stage.key === 'withdrawal' && canSeeCommission && detail?.commission?.afterSales
                "
                class="chain-product__secondary"
                data-test="chain-after-sales"
                >{{ t("order.chain.orderAfterSalesHint") }}</span
              >
            </div>
            <el-icon v-if="index < stages.length - 1" class="chain-stage__arrow" aria-hidden="true"
              ><Right
            /></el-icon>
          </li>
        </ol>
      </section>

      <section class="chain-page__card chain-product-details" data-test="chain-product-details">
        <h2 class="chain-page__title">{{ t("order.chain.productDetails") }}</h2>
        <DataTable
          plain
          table-id="chain-order-items-v2"
          data-test="chain-product-summary"
          row-key="orderItemId"
          :rows="chain.lines"
          :columns="summaryColumns"
          :total="chain.lines.length"
          :paged="false"
          :fixed-columns="false"
        >
          <template #cell-image="{ row }">
            <OrderProductImage :image-url="lineItem(row as ChainLineVO)?.imageUrl" />
          </template>
          <template #cell-product="{ row }">
            <div class="chain-product__stack">
              <span>{{ lineName(row as ChainLineVO) }}</span>
              <span class="chain-product__secondary">{{ lineSku(row as ChainLineVO) || "—" }}</span>
            </div>
          </template>
          <template #cell-status="{ row }">{{ lineStatus(row as ChainLineVO) }}</template>
          <template #cell-unitPrice="{ row }">{{
            lineItem(row as ChainLineVO)?.unitPrice == null
              ? t("order.missingHistoricalPrice")
              : formatAmountWithCurrency(lineItem(row as ChainLineVO)?.unitPrice)
          }}</template>
          <template #cell-quantity="{ row }">{{ (row as ChainLineVO).quantity ?? "-" }}</template>
          <template #cell-totalAmount="{ row }">
            <span data-test="chain-item-total">{{
              displayAmount(lineItem(row as ChainLineVO)?.totalAmount)
            }}</span>
          </template>
          <template #cell-payAmount="{ row }">
            <span data-test="chain-item-pay">{{
              displayAmount((row as ChainLineVO).payAmount)
            }}</span>
          </template>
          <template #cell-refundAmount="{ row }">
            {{ displayAmount(lineItem(row as ChainLineVO)?.refundAmount) }}
          </template>
          <template #cell-netSales="{ row }">{{
            displayAmount(lineNetSales(row as ChainLineVO))
          }}</template>
        </DataTable>
      </section>

      <section class="chain-page__card" data-test="chain-reconciliation">
        <h2 class="chain-page__title">{{ t("order.chain.fundMovements") }}</h2>
        <el-alert
          v-if="flowsView === 'noPermission'"
          type="warning"
          :closable="false"
          :title="t('order.chain.noCommissionPermission')"
          data-test="chain-flows-no-permission"
        />
        <el-alert
          v-else-if="flowsView === 'scopeLimited'"
          type="warning"
          :closable="false"
          :title="t('finance.scopeLimited')"
          data-test="chain-flows-scope-limited"
        />
        <el-alert
          v-else-if="flowsView === 'incomplete'"
          type="warning"
          :closable="false"
          :title="t('order.chain.incompleteWeidianFlows')"
          data-test="chain-flows-incomplete"
        />
        <DataTable
          v-if="flowsView === 'table' || (flowsView === 'incomplete' && fundMovements.length)"
          data-test="chain-flows"
          plain
          table-id="chain-fund-movements"
          :rows="fundMovements"
          :columns="flowColumns"
          :total="fundMovements.length"
          :empty-text="t('order.chain.noFundMovements')"
          :paged="false"
          :fixed-columns="false"
        >
          <template #cell-type="{ row }">
            <el-tag round :class="{ 'chain-fund-tag--commission': row.type === 'commissionFund' }">
              {{ t(`order.chain.${row.type}`) }}
            </el-tag>
          </template>
          <template #cell-direction="{ row }">
            {{ row.direction ? t(`order.chain.${row.direction}`) : "—" }}
          </template>
          <template #cell-amount="{ row }">
            <span :class="amountTone(row)">{{ displayAmount(row.amount) }}</span>
          </template>
          <template #cell-time="{ row }">{{ displayTime(row.time) }}</template>
          <template #cell-reference="{ row }">
            <div v-if="row.withdrawals?.length" class="chain-withdrawal-references">
              <el-button
                v-for="record in row.withdrawals"
                :key="record.id"
                link
                type="primary"
                data-test="chain-withdrawal-link"
                @click="openWithdrawal(record.id)"
                >{{ record.number }}</el-button
              >
            </div>
            <CopyText v-else-if="row.reference" :value="row.reference" hide-tooltip />
            <span v-else>—</span>
          </template>
        </DataTable>
      </section>
    </template>
    <el-result
      v-else-if="!loading"
      icon="error"
      :title="t('order.chain.loadFailed')"
      data-test="chain-load-failed"
    >
      <template #extra>
        <el-button type="primary" data-test="chain-retry" @click="load">
          {{ t("table.refresh") }}
        </el-button>
      </template>
    </el-result>
  </div>
</template>

<style scoped>
.chain-page {
  display: flex;
  flex-direction: column;
  gap: var(--tcrm-space-4);
  min-width: 0;
  container: order-chain / inline-size;
}

.chain-page__card {
  min-width: 0;
  padding: var(--tcrm-space-4);
  background: var(--tcrm-color-card);
  border: 1px solid var(--tcrm-color-border);
  border-radius: var(--tcrm-radius-card);
}

.chain-page__heading {
  min-width: 0;
  margin: 0;
  font-size: var(--tcrm-font-size-section);
  font-weight: 700;
  overflow-wrap: anywhere;
}

.chain-header__title,
.chain-meta {
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  gap: var(--tcrm-space-2) var(--tcrm-space-3);
}

.chain-header__title {
  margin-block: var(--tcrm-space-1) var(--tcrm-space-2);
}

.chain-meta {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  margin: 0;
}
.chain-meta--creator {
  margin-block-start: var(--tcrm-space-2);
}
.chain-stage__jobs {
  display: grid;
  gap: var(--tcrm-space-1);
}

.chain-meta div {
  display: flex;
  flex-wrap: wrap;
  gap: var(--tcrm-space-1) var(--tcrm-space-2);
  min-width: 0;
}

.chain-meta dt,
.chain-product__secondary {
  color: var(--tcrm-color-text-secondary);
  overflow-wrap: anywhere;
}

.chain-meta dd {
  margin: 0;
}

.chain-creators {
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  gap: var(--tcrm-space-1) var(--tcrm-space-2);
  min-width: 0;
  overflow-wrap: anywhere;
}

.chain-product__stack {
  display: flex;
  flex-direction: column;
  gap: var(--tcrm-space-1);
}

.chain-page__title {
  margin: 0 0 var(--tcrm-space-3);
  font-size: var(--tcrm-font-size-section);
  line-height: var(--tcrm-line-height-section);
  font-weight: 600;
}

.chain-stages {
  display: grid;
  grid-template-columns: repeat(5, minmax(0, 1fr));
  gap: var(--tcrm-space-4);
  margin: 0;
  padding: 0;
  list-style: none;
}

.chain-stages li {
  display: grid;
  position: relative;
  min-width: 0;
}

.chain-stage {
  display: grid;
  min-width: 0;
  align-content: start;
  justify-items: start;
  gap: var(--tcrm-space-2);
  padding: var(--tcrm-space-2) var(--tcrm-space-3);
  border: 1px solid var(--tcrm-color-border);
  border-radius: var(--tcrm-radius-card);
}

.chain-stage--order {
  border-color: color-mix(in srgb, var(--tcrm-color-primary) 30%, var(--tcrm-color-border));
  background: var(--tcrm-color-order-band);
}

.chain-stage__arrow {
  position: absolute;
  inset-block-start: 50%;
  inset-inline-end: calc(-1 * var(--tcrm-space-4));
  inline-size: var(--tcrm-space-4);
  transform: translateY(-50%);
  color: var(--tcrm-color-text-secondary);
}

.chain-stage__heading {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--tcrm-space-1) var(--tcrm-space-2);
  min-width: 0;
  max-width: 100%;
}

.chain-stage__label {
  display: flex;
  align-items: center;
  gap: var(--tcrm-space-2);
  font-weight: 600;
}

.chain-stage__label .el-icon {
  flex-shrink: 0;
  color: var(--el-color-primary);
}

.chain-stage :deep(.el-tag) {
  max-width: 100%;
  height: auto;
  white-space: normal;
}

.chain-stage :deep(.copy-text__value) {
  white-space: normal;
  overflow-wrap: anywhere;
}

.chain-stage > .chain-product__secondary,
.chain-stage > :deep(.copy-text) {
  font-size: var(--tcrm-font-size-body);
}

.chain-fund-tag--commission {
  --el-tag-text-color: color-mix(
    in srgb,
    var(--tcrm-color-chain-finance) 70%,
    var(--tcrm-color-text)
  );
  --el-tag-bg-color: color-mix(
    in srgb,
    var(--tcrm-color-chain-finance) 10%,
    var(--tcrm-color-card)
  );
  --el-tag-border-color: color-mix(
    in srgb,
    var(--tcrm-color-chain-finance) 16%,
    var(--tcrm-color-card)
  );
}

.chain-money--positive {
  color: var(--tcrm-color-success);
}

.chain-money--negative {
  color: var(--tcrm-color-danger);
}

.chain-withdrawal-references {
  display: grid;
  justify-items: center;
  gap: var(--tcrm-space-1);
}
.chain-withdrawal-references .el-button {
  margin-inline-start: 0;
}

@container order-chain (max-width: 860px) {
  .chain-stages {
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: var(--tcrm-space-2);
  }

  .chain-stage__arrow {
    display: none;
  }
  .chain-meta {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@container order-chain (max-width: 400px) {
  .chain-stages {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
