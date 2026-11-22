<script setup lang="ts">
import RateSupplementDialog from "../RateSupplementDialog.vue";
import {
  Box,
  Document,
  Link,
  ShoppingCart,
  Refresh,
  Money,
  Wallet,
  Right
} from "@element-plus/icons-vue";
import { computed, onMounted, ref } from "vue";
import { useI18n } from "vue-i18n";
import { useRoute, useRouter } from "vue-router";
import { getOrder, getOrderChain } from "@/api/order/order";
import type {
  ChainFinanceFlowVO,
  ChainLineVO,
  ChainWithdrawalVO,
  OrderChainVO,
  OrderDetailVO
} from "@/api/types";
import CopyText from "@/components/CopyText.vue";
import DataTable, { type DataColumn } from "@/components/DataTable.vue";
import { useUserStore } from "@/stores/user";
import { formatInstant, formatNameWithCode, formatAmountWithCurrency } from "@/utils/format";
import { cents, decimal, sumMoney, validMoney } from "@/utils/finance";
import { notifyError } from "@/utils/notify";
import {
  channelText,
  orderStatusText,
  orderRefundStatusText,
  orderRefundTagType
} from "@/utils/order";
import { formatRate } from "@/utils/rate";
import OrderProductImage from "../OrderProductImage.vue";

defineOptions({ name: "OrderChainPage" });

const { t, te, locale } = useI18n();

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

function amountTone(amount: string | null) {
  if (!amount || !validMoney(amount.replace(/^[+-]/, ""))) return undefined;
  return amount.startsWith("-") ? "chain-money--negative" : "chain-money--positive";
}

type FundMovement = {
  id: string;
  type: "goodsFund" | "commissionFund";
  direction:
    | "receivableIncrease"
    | "receivableDecrease"
    | "accrualExpense"
    | "expensePending"
    | "increase"
    | "decrease"
    | null;
  subject?: ChainFinanceFlowVO["subject"] | null;
  amount: string | null;
  status: string;
  time: string | null;
  reference: string | null;
  withdrawalId?: string;
};

const activeWithdrawals = computed(() =>
  withdrawalRecords.value.filter(record =>
    ["PENDING_REVIEW", "PENDING_PAYMENT", "PAID"].includes(record.status)
  )
);

const withdrawalStageReference = computed(() => {
  const counts = new Map<string, number>();
  for (const record of activeWithdrawals.value) {
    const label =
      record.status !== "PAID"
        ? t("order.chain.withdrawalInProgress")
        : record.paidAt
          ? financeStatusText("WITHDRAWN")
          : t("finance.unverified");
    counts.set(label, (counts.get(label) ?? 0) + 1);
  }
  if (!counts.size) {
    return occupiedTotal.value == null ? "—" : t("order.chain.noActiveWithdrawal");
  }
  return [...counts]
    .map(([status, count]) => t("order.chain.withdrawalStatusCount", { status, count }))
    .join(" · ");
});

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

  // A pending refund temporarily blocks availability. Show its estimated surviving commission.
  if (funds.financeStatus === "REFUNDING") {
    const amount = funds.financeAmount;
    const remaining =
      amount != null && validMoney(amount, false) ? cents(amount) - cents(occupiedTotal.value) : 0n;
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
    // Task 159 subject rows: the settleable postings carry the released total (releases in,
    // released clawbacks out); the other subjects do not move the released balance.
    if (flow.subject !== "COMMISSION_SETTLEABLE") continue;
    if (!validMoney(flow.amount, false) || !flow.direction) return null;
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
  // Task 159 review: the board is the subject ledger (task 158 §5.2); the platform reconciliation
  // flows stay in the settlement header and completeness alerts, not as movement rows — showing
  // both would double every payment.
  const rows: FundMovement[] = [];
  const balances = commissionBalances.value;
  if (balances?.frozen) {
    rows.push({
      id: "commission-frozen",
      type: "commissionFund",
      direction: null,
      amount: balances.frozen,
      status: financeStatusText("FROZEN"),
      time: null,
      reference: null
    });
  }
  if (balances?.available) {
    rows.push({
      id: "commission-available",
      type: "commissionFund",
      direction: null,
      amount: balances.available,
      status: t("order.chain.withdrawalReady"),
      time: null,
      reference: null
    });
  }
  // Task 159 subject postings replace the derived withdrawal rows; paid allocations split per row.
  for (const flow of chain.value?.financeFlows ?? []) {
    const withdrawalRecord = withdrawalRecords.value.find(
      record => record.applicationNo === flow.withdrawalNo
    );
    rows.push({
      id: `flow-${flow.flowNo}`,
      type: flow.type === "KOL_COMMISSION" ? "commissionFund" : "goodsFund",
      direction: flow.direction === "DECREASE" ? "decrease" : "increase",
      subject: flow.subject,
      amount: flow.direction === "DECREASE" ? `-${flow.amount}` : `+${flow.amount}`,
      status: flow.subject === "ORDER_WITHDRAWN" ? financeStatusText("WITHDRAWN") : "—",
      time: flow.businessAt,
      reference: flow.withdrawalNo ?? flow.flowNo,
      withdrawalId: withdrawalRecord?.withdrawalId
    });
  }
  return rows;
});

const stageGroups = computed(() => {
  const jobs = copyJobs.value;
  const jobStates = new Set(jobs.map(job => job.status?.trim().toLowerCase()));
  const singleJobState = jobStates.size === 1 ? [...jobStates][0] : null;
  const refundAmount = refundTotalAmount.value;
  const currentCommission = canSeeCommission.value ? chain.value?.funds?.commissionStatus : null;
  const commissionState = activeWithdrawals.value.some(record => record.status !== "PAID")
    ? t("order.chain.withdrawalInProgress")
    : paidWithdrawalTotal.value != null || currentCommission === "WITHDRAWN"
      ? financeStatusText("WITHDRAWN")
      : commissionBalances.value?.available
        ? t("order.chain.withdrawalReady")
        : commissionBalances.value?.frozen
          ? financeStatusText("FROZEN")
          : financeStatusText(currentCommission);
  return [
    {
      key: "journey",
      stages: [
        {
          key: "source",
          icon: Box,
          label: t("order.chain.originalProduct"),
          state: t(
            orderLines.value.some(({ link }) => link?.sourceItemId)
              ? "order.chain.linked"
              : "order.chain.notLinked"
          )
        },
        {
          key: "copy",
          icon: Document,
          label: t("order.chain.copyJob"),
          stateTone: copyJobTagType(singleJobState),
          state: !jobs.length
            ? t("order.chain.notLinked")
            : jobs.some(job => !job.status?.trim())
              ? t("finance.unverified")
              : jobStates.size > 1
                ? t("order.chain.mixedStates")
                : copyJobState(jobs[0]?.status),
          identifiers: jobs.map(job => job.jobCode),
          reference: jobs.length ? null : "—"
        },
        {
          key: "link",
          icon: Link,
          label: t("order.chain.kolItem"),
          state: t(
            orderLines.value.some(({ link }) => link)
              ? "order.chain.linkCreated"
              : "order.chain.notLinked"
          )
        },
        {
          key: "order",
          icon: ShoppingCart,
          label: t("order.chain.completedOrder"),
          state: detail.value ? orderStatusText(detail.value.orderStatus) : "—",
          reference: chain.value?.orderNo ?? "—"
        }
      ]
    },
    {
      key: "afterOrder",
      stages: [
        {
          key: "refund",
          icon: Refresh,
          label: t("order.chain.refunds"),
          state: detail.value
            ? orderRefundStatusText(detail.value.refundStatus)
            : countRecords(refundRecords.value.length),
          reference:
            refundAmount == null
              ? countRecords(refundRecords.value.length)
              : displayAmount(refundAmount)
        },
        {
          key: "settlement",
          icon: Money,
          label: t("order.chain.shopSettlement"),
          state: settlementState.value,
          reference: displayAmount(platform.value?.settlementAmount)
        },
        {
          key: "withdrawal",
          icon: Wallet,
          label: t("order.chain.commissionBlock"),
          state: commissionState,
          reference: withdrawalStageReference.value
        }
      ]
    }
  ];
});

/**
 * Text for a gated fund value: the amount when known, otherwise the reason it is hidden — no
 * permission, OWN scope on a mixed order — or the unverified placeholder when the funds block is
 * visible but the amount itself waits for the M0 Weidian settlement net (never a fake zero).
 */
function fundsText(value: string | null | undefined): string {
  if (value != null) return formatAmountWithCurrency(value);
  if (!chain.value?.funds) {
    if (!canSeeCommission.value) return t("order.chain.noCommissionPermission");
    if (userStore.isOwnScope) return t("finance.scopeLimited");
  }
  return t("finance.unverified");
}

/** Line titles come from the detail items matched by order-item id. */
function lineName(line: ChainLineVO): string {
  const item = detail.value?.items.find(candidate => candidate.id === line.orderItemId);
  return item?.itemName ?? t("order.detail.itemName");
}

function lineSku(line: ChainLineVO): string | null {
  return detail.value?.items.find(candidate => candidate.id === line.orderItemId)?.skuName ?? null;
}

function newProductId(line: ChainLineVO) {
  return lineItem(line)?.weidianItemId || line.link?.targetItemId;
}

function lineItem(line: ChainLineVO) {
  return detail.value?.items.find(item => item.id === line.orderItemId);
}

function productSpan({
  column,
  rowIndex
}: {
  column: { columnKey?: string; property?: string };
  rowIndex: number;
}): number[] | undefined {
  if ((column.columnKey ?? column.property) !== "freight") return;
  return rowIndex === 0 ? [orderLines.value.length, 1] : [0, 0];
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
  {
    key: "productIds",
    align: "left",
    label: t("order.chain.productIds"),
    minWidth: locale.value.startsWith("en") ? 300 : 248
  },
  { key: "status", label: t("common.status"), minWidth: 90, align: "left" },
  { key: "unitPrice", label: t("order.detail.unitPrice"), minWidth: 104, amount: true },
  { key: "quantity", label: t("order.line.quantity"), minWidth: 80 },
  { key: "totalAmount", label: t("order.amounts.productTotal"), minWidth: 116, amount: true },
  { key: "freight", label: t("order.freight"), minWidth: 90, amount: true, tooltip: false },
  { key: "payAmount", label: t("order.chain.itemPaidAmount"), minWidth: 120, amount: true },
  {
    key: "rate",
    label: t("order.chain.summaryRate"),
    minWidth: locale.value.startsWith("en") ? 160 : 110
  },
  {
    key: "commission",
    label: t("order.chain.summaryCommission"),
    // Without commission:view the cell shows the full no-permission sentence, never truncated.
    minWidth: canSeeCommission.value ? 120 : { "zh-CN": 140, "en-US": 290 },
    amount: true
  },
  {
    key: "occupied",
    label: t("order.chain.withdrawalOccupation"),
    minWidth: locale.value.startsWith("en") ? 155 : 110,
    amount: true
  },
  {
    key: "paidRemaining",
    label: t("order.chain.paidRemaining"),
    minWidth: locale.value.startsWith("en") ? 185 : 150,
    amount: true
  }
]);

/**
 * Withdrawn X / remaining Y of one line (task 158 §4.3): the paid allocation total versus the
 * released balance less every occupation. Null when the data is missing or unparseable.
 */
function paidRemaining(orderItemId: string): { withdrawn: string; remaining: string } | null {
  const flows = chain.value?.financeFlows;
  const line = chain.value?.lines?.find(entry => entry.orderItemId === orderItemId);
  // An unverified line has no established amounts: show them as unknown, never as a fake zero.
  if (!line?.commission || line.commission.netCommission == null || !Array.isArray(flows)) {
    return null;
  }
  let released = 0n;
  for (const flow of flows) {
    if (flow.orderItemId !== orderItemId || flow.subject !== "COMMISSION_SETTLEABLE") continue;
    if (!validMoney(flow.amount, false) || !flow.direction) return null;
    released += flow.direction === "INCREASE" ? cents(flow.amount) : -cents(flow.amount);
  }
  let withdrawn = 0n;
  let occupied = 0n;
  for (const record of line.commission.withdrawals ?? []) {
    if (!record.occupiedCny || !validMoney(record.occupiedCny, false)) return null;
    if (record.status === "PAID") withdrawn += cents(record.occupiedCny);
    if (["PENDING_REVIEW", "PENDING_PAYMENT", "PAID"].includes(record.status)) {
      occupied += cents(record.occupiedCny);
    }
  }
  // A refund clawback may leave the occupation outrunning the released balance (task 158 §4.3):
  // the negative remainder stays visible instead of being clamped to zero.
  return { withdrawn: decimal(withdrawn), remaining: decimal(released - occupied) };
}

const flowColumns = computed<DataColumn[]>(() => [
  { key: "type", label: t("order.chain.financeType"), minWidth: 125, align: "left" },
  {
    key: "subject",
    label: t("order.chain.financeSubject"),
    // Long English subject names ("Frozen commission expense") must stay whole (task 160).
    minWidth: locale.value.startsWith("en") ? 235 : 150,
    align: "left"
  },
  { key: "direction", label: t("order.chain.direction"), minWidth: 132 },
  { key: "amount", label: t("order.chain.financeAmount"), minWidth: 114, amount: true },
  {
    key: "status",
    label: t("common.status"),
    minWidth: locale.value.startsWith("en") ? 180 : 133
  },
  { key: "time", label: t("order.chain.settlementPaymentTime"), minWidth: 172 },
  // 20-char flow numbers (FF + snowflake id) plus the copy icon need ~235px of content box.
  { key: "reference", label: t("order.chain.settlementWithdrawalNo"), minWidth: 240 }
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
        <div class="chain-product__secondary">{{ t("order.chain.orderInfoSection") }}</div>
        <div class="chain-header__title">
          <h1 class="chain-page__heading"><CopyText :value="chain.orderNo" /></h1>
          <el-tag v-if="detail" effect="light">{{ orderStatusText(detail.orderStatus) }}</el-tag>
          <el-tag v-if="detail" :type="orderRefundTagType(detail.refundStatus)" effect="light">{{
            orderRefundStatusText(detail.refundStatus)
          }}</el-tag>
        </div>
        <dl class="chain-meta">
          <template v-if="detail">
            <div>
              <dt>{{ t("order.channel") }}</dt>
              <dd>{{ channelText(detail.channel) }}</dd>
            </div>
            <div>
              <dt>{{ t("order.shop") }}</dt>
              <dd>{{ shopLabel(detail.shopName, detail.shopCode) }}</dd>
            </div>
            <div>
              <dt>{{ t("order.detail.orderTime") }}</dt>
              <dd>{{ displayTime(detail.orderTime) }}</dd>
            </div>
            <div>
              <dt>{{ t("order.payTime") }}</dt>
              <dd>{{ displayTime(detail.payTime) }}</dd>
            </div>
          </template>
          <div data-test="chain-creators">
            <dt>{{ t("order.drawerCreator") }}</dt>
            <dd v-if="orderCreators.size" class="chain-creators">
              <span v-for="[key, creator] in orderCreators" :key="key" class="chain-creator">
                <span v-if="creator.name" data-test="identity-name">{{ creator.name }}</span>
                <CopyText
                  v-if="creator.code"
                  class="chain-product__secondary"
                  data-test="identity-code"
                  :value="creator.code"
                  hide-tooltip
                />
              </span>
            </dd>
            <dd v-else>—</dd>
          </div>
        </dl>
      </header>

      <section class="chain-page__card" data-test="chain-order-info">
        <h2 class="chain-page__title">{{ t("order.chain.fullJourney") }}</h2>
        <template v-for="group in stageGroups" :key="group.key">
          <h3 v-if="group.key === 'afterOrder'" class="chain-stages__divider">
            {{ t("order.chain.afterOrder") }}
          </h3>
          <ol
            class="chain-stages"
            :class="`chain-stages--${group.key}`"
            :aria-label="t(`order.chain.${group.key === 'journey' ? 'fullJourney' : 'afterOrder'}`)"
          >
            <li v-for="(stage, index) in group.stages" :key="stage.key">
              <div
                class="chain-stage"
                :data-test="`chain-stage-${stage.key}`"
                :class="{ 'chain-stage--order': stage.key === 'order' }"
              >
                <div class="chain-stage__heading">
                  <span class="chain-stage__label">
                    <el-icon aria-hidden="true"><component :is="stage.icon" /></el-icon>
                    {{ stage.label }}
                  </span>
                  <el-tag
                    size="small"
                    :type="stage.stateTone ?? (stage.key === 'order' ? 'primary' : 'info')"
                  >
                    {{ stage.state }}
                  </el-tag>
                </div>
                <CopyText
                  v-for="identifier in stage.identifiers"
                  :key="identifier"
                  :value="identifier"
                  hide-tooltip
                />
                <span v-if="stage.reference" class="chain-product__secondary">
                  {{ stage.reference }}
                </span>
                <span
                  v-if="
                    stage.key === 'withdrawal' && canSeeCommission && detail?.commission?.afterSales
                  "
                  class="chain-product__secondary"
                  data-test="chain-after-sales"
                >
                  {{ t("finance.afterSalesHint") }}
                </span>
              </div>
              <el-icon
                v-if="group.key === 'journey' && index < group.stages.length - 1"
                class="chain-stage__arrow"
                aria-hidden="true"
                ><Right
              /></el-icon>
            </li>
          </ol>
        </template>
      </section>

      <section class="chain-page__card chain-product-details" data-test="chain-product-details">
        <h2 class="chain-page__title">{{ t("order.chain.productDetails") }}</h2>
        <DataTable
          plain
          table-id="chain-order-items-with-freight"
          data-test="chain-product-summary"
          row-key="orderItemId"
          :rows="chain.lines"
          :columns="summaryColumns"
          :total="chain.lines.length"
          :paged="false"
          :fixed-columns="false"
          :span-method="productSpan"
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
          <template #cell-productIds="{ row }">
            <div class="chain-product__stack">
              <div class="chain-product__identity">
                <span class="chain-product__secondary">{{ t("order.chain.newItemId") }}</span>
                <CopyText
                  v-if="newProductId(row as ChainLineVO)"
                  :value="newProductId(row as ChainLineVO)"
                  hide-tooltip
                />
                <span v-else>—</span>
              </div>
              <div class="chain-product__identity">
                <span class="chain-product__secondary">{{ t("order.chain.sourceItemId") }}</span>
                <CopyText
                  v-if="(row as ChainLineVO).link?.sourceItemId"
                  :value="(row as ChainLineVO).link?.sourceItemId"
                  hide-tooltip
                />
                <span v-else>—</span>
              </div>
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
          <template #cell-freight>
            <div class="chain-product__stack chain-order-freight" data-test="chain-order-freight">
              <span>{{ displayAmount(detail?.freight) }}</span>
              <span
                v-if="detail?.freight != null && chain.lines.length > 1"
                class="chain-product__secondary"
                >{{ t("order.chain.orderFreightScope") }}</span
              >
            </div>
          </template>
          <template #cell-payAmount="{ row }">
            <span data-test="chain-item-pay">{{
              displayAmount((row as ChainLineVO).payAmount)
            }}</span>
          </template>
          <template #cell-rate="{ row }"
            ><span v-if="!canSeeCommission">-</span
            ><el-button
              v-else-if="canSupplement(row as ChainLineVO)"
              link
              type="primary"
              data-test="rate-supplement"
              @click="openSupplement(row as ChainLineVO)"
              >{{ t("order.rateSupplement.title") }}</el-button
            ><span v-else-if="(row as ChainLineVO).commission?.rateSnapshot == null">{{
              fundsText(null)
            }}</span
            ><span v-else class="chain-rate"
              >{{ formatRate((row as ChainLineVO).commission!.rateSnapshot)
              }}<el-tag
                v-if="(row as ChainLineVO).commission!.rateSupplemented"
                class="chain-rate__tag"
                size="small"
                type="warning"
                effect="plain"
                data-test="rate-supplemented"
                >{{ t("order.chain.rateSupplemented") }}</el-tag
              ></span
            ></template
          >
          <template #cell-commission="{ row }"
            ><span
              v-if="!canSeeCommission"
              class="chain-no-permission"
              data-test="chain-line-commission-hidden"
              >{{ t("order.chain.commissionHidden") }}</span
            ><template v-else>{{
              fundsText((row as ChainLineVO).commission?.netCommission)
            }}</template></template
          >
          <template #cell-occupied="{ row }"
            ><span v-if="!canSeeCommission">-</span
            ><template v-else>{{
              fundsText((row as ChainLineVO).commission?.occupiedTotal)
            }}</template></template
          >
          <template #cell-paidRemaining="{ row }">
            <span v-if="!canSeeCommission">-</span>
            <div v-else class="chain-product__stack" data-test="chain-line-paid-remaining">
              <span
                >{{ t("order.chain.lineWithdrawn") }}
                {{ fundsText(paidRemaining((row as ChainLineVO).orderItemId)?.withdrawn) }}</span
              >
              <span class="chain-product__secondary"
                >{{ t("order.chain.lineRemaining") }}
                {{ fundsText(paidRemaining((row as ChainLineVO).orderItemId)?.remaining) }}</span
              >
            </div>
          </template>
        </DataTable>
      </section>

      <section class="chain-page__card" data-test="chain-reconciliation">
        <h2 class="chain-page__title">{{ t("order.chain.fundMovements") }}</h2>
        <div class="chain-overview" data-test="chain-funds">
          <div class="chain-overview__group">
            <h3>{{ t("order.chain.orderAmounts") }}</h3>
            <dl class="chain-overview__values chain-overview__values--order">
              <div>
                <dt>{{ t("order.payAmount") }}</dt>
                <dd>{{ displayAmount(detail?.payAmount ?? chain.funds?.payAmount) }}</dd>
              </div>
              <div>
                <dt>{{ t("order.chain.successfulRefunds") }}</dt>
                <dd>{{ displayAmount(refundTotalAmount) }}</dd>
              </div>
              <div>
                <dt>{{ t("order.amounts.netReceived") }}</dt>
                <dd>{{ displayAmount(detail?.netReceived) }}</dd>
              </div>
            </dl>
          </div>
          <div class="chain-overview__group">
            <h3>{{ t("order.chain.fundSummary") }}</h3>
            <dl class="chain-overview__values">
              <div>
                <dt>{{ t("order.chain.weidianSettlementAmount") }}</dt>
                <dd>{{ displayAmount(platform?.settlementAmount) }}</dd>
                <span
                  v-if="platform?.settlementAmount == null && settlementState !== '—'"
                  class="chain-product__secondary"
                  >{{ settlementState }}</span
                >
              </div>
              <div>
                <dt>{{ t("order.chain.paidWithdrawal") }}</dt>
                <dd>{{ displayAmount(paidWithdrawalTotal) }}</dd>
                <span
                  v-if="
                    canSeeCommission &&
                    chain.funds &&
                    !withdrawalRecords.some(record => record.status === 'PAID')
                  "
                  class="chain-product__secondary"
                  >{{ t("order.chain.noPaidWithdrawal") }}</span
                >
              </div>
            </dl>
          </div>
        </div>

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
          <template #cell-subject="{ row }">
            {{ row.subject ? t(`finance.subjects.${row.subject}`) : "—" }}
          </template>
          <template #cell-direction="{ row }">
            {{ row.direction ? t(`order.chain.${row.direction}`) : "—" }}
          </template>
          <template #cell-amount="{ row }">
            <span :class="amountTone(row.amount)">{{ displayAmount(row.amount) }}</span>
          </template>
          <template #cell-time="{ row }">{{ displayTime(row.time) }}</template>
          <template #cell-reference="{ row }">
            <el-button
              v-if="row.withdrawalId && row.reference"
              link
              type="primary"
              data-test="chain-withdrawal-link"
              @click="openWithdrawal(row.withdrawalId)"
              >{{ row.reference }}</el-button
            >
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
  margin: 0;
}

.chain-meta div {
  display: flex;
  flex-wrap: wrap;
  gap: var(--tcrm-space-1) var(--tcrm-space-2);
  min-width: 0;
}

.chain-meta dt,
.chain-overview dt,
.chain-product__secondary {
  color: var(--tcrm-color-text-secondary);
  overflow-wrap: anywhere;
}

.chain-meta dd,
.chain-overview dd {
  margin: 0;
}

.chain-creators,
.chain-creator {
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  gap: var(--tcrm-space-1) var(--tcrm-space-2);
  min-width: 0;
  overflow-wrap: anywhere;
}

.chain-creator + .chain-creator {
  border-inline-start: 1px solid var(--tcrm-color-border);
  padding-inline-start: var(--tcrm-space-2);
}

.chain-product-details :deep(td[rowspan]:not([rowspan="1"]):has(.chain-order-freight)) {
  vertical-align: top;
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
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: var(--tcrm-space-4);
  margin: 0;
  padding: 0;
  list-style: none;
}

.chain-stages--afterOrder {
  grid-template-columns: repeat(3, minmax(0, 1fr));
}

.chain-stages__divider {
  display: flex;
  align-items: center;
  gap: var(--tcrm-space-2);
  margin: var(--tcrm-space-3) 0 var(--tcrm-space-2);
  color: var(--tcrm-color-text-secondary);
  font: inherit;
}

.chain-stages__divider::after {
  flex: 1;
  border-top: 1px solid var(--tcrm-color-border);
  content: "";
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

.chain-product__identity {
  display: flex;
  align-items: baseline;
  flex-wrap: wrap;
  gap: var(--tcrm-space-1);
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

.chain-overview {
  display: grid;
  grid-template-columns: minmax(0, 3fr) minmax(0, 2fr);
  padding: var(--tcrm-space-3);
  margin-bottom: var(--tcrm-space-3);
  background: var(--tcrm-color-muted);
  border-radius: var(--tcrm-radius-card);
}

.chain-overview__group + .chain-overview__group {
  padding-inline-start: var(--tcrm-space-3);
  border-inline-start: 1px solid var(--tcrm-color-border);
}

.chain-overview__group h3 {
  margin: 0;
  font: inherit;
  font-weight: 600;
}

.chain-overview__values {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: var(--tcrm-space-2);
  margin: var(--tcrm-space-2) 0 0;
}

.chain-overview__values--order {
  grid-template-columns: repeat(3, minmax(0, 1fr));
  padding-inline-end: var(--tcrm-space-3);
}

.chain-overview dd {
  font-size: var(--tcrm-font-size-section);
  font-weight: 600;
  font-variant-numeric: tabular-nums;
  overflow-wrap: anywhere;
}

.chain-rate {
  display: inline-flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: center;
  gap: var(--tcrm-space-1);
}

@container order-chain (max-width: 720px) {
  .chain-stages {
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: var(--tcrm-space-2);
  }

  .chain-stage__arrow {
    display: none;
  }
}

@container order-chain (max-width: 550px) {
  .chain-overview {
    grid-template-columns: minmax(0, 1fr);
    gap: var(--tcrm-space-3);
  }

  .chain-overview__group + .chain-overview__group {
    padding: var(--tcrm-space-3) 0 0;
    border-inline-start: 0;
    border-top: 1px solid var(--tcrm-color-border);
  }

  .chain-overview__values--order {
    padding-inline-end: 0;
  }
}

@container order-chain (max-width: 400px) {
  .chain-stages {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
