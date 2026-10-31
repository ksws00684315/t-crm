<script setup lang="ts">
import NameCode from "@/components/NameCode.vue";
import {
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
import type { ChainLineVO, OrderChainVO, OrderDetailVO } from "@/api/types";
import CopyText from "@/components/CopyText.vue";
import DataTable, { type DataColumn } from "@/components/DataTable.vue";
import { useUserStore } from "@/stores/user";
import { formatInstant, formatNameWithCode, formatAmountWithCurrency } from "@/utils/format";
import { notifyError } from "@/utils/notify";
import {
  channelText,
  orderStatusText,
  refundStatusText,
  refundTagType,
  refundTypeText
} from "@/utils/order";
import { formatRate } from "@/utils/rate";
import OrderProductCell from "../OrderProductCell.vue";

defineOptions({ name: "OrderChainPage" });

/**
 * Order chain (requirement 09.04, task 40 §4): opens as a system tab from the order list since
 * task 59 (R-17). The chain endpoint only carries the per-line wiring, so the page also loads the
 * order detail for the header and the line names. Every product line walks copy job → creator
 * item link → order line → refunds; task 72 wires the real commission data into the funds cards,
 * the per-line commission block and the finance ledger (funds, flows and line blocks stay null
 * without commission:view or, for OWN scopes, on mixed attribution).
 */
const { t, te, locale } = useI18n();

function shopLabel(name: string | null | undefined, code: string | null | undefined): string {
  if (!name && !code) return "-";
  return code ? formatNameWithCode(name ?? "-", code) : (name ?? "-");
}

const route = useRoute();
const router = useRouter();
const userStore = useUserStore();

const loading = ref(false);
const chain = ref<OrderChainVO | null>(null);
/** Header context; degrades to the bare chain when the detail call fails. */
const detail = ref<OrderDetailVO | null>(null);
const sourceIds = computed(
  () =>
    [
      ...new Set((chain.value?.lines ?? []).map(line => line.link?.sourceProductId).filter(Boolean))
    ].join(", ") || "-"
);
const targetIds = computed(
  () =>
    [
      ...new Set(
        (chain.value?.lines ?? [])
          .map(line => line.link?.targetItemId ?? lineItem(line)?.weidianItemId)
          .filter(Boolean)
      )
    ].join(", ") || "-"
);

/** Financial data needs commission:view; OWN scopes additionally see nothing on mixed orders. */
const canSeeCommission = computed(() => userStore.can("commission:view"));

/**
 * Successful-refund total summed on the client, deduplicated by refund id so one refund spanning
 * several lines counts once (B07; same rule as the server's dedup, for viewers without funds).
 */
const completedRefundTotal = computed(() => {
  const refunds = (chain.value?.lines ?? []).flatMap(line => line.refunds);
  const centsById = new Map<string, bigint>();
  for (const refund of refunds) {
    if (refund.refundStatus !== "SUCCESS") continue;
    const match = /^(\d+)(?:\.(\d{1,2}))?$/.exec(refund.amount);
    if (!match) return null;
    if (!centsById.has(refund.id)) {
      centsById.set(
        refund.id,
        BigInt(match[1]!) * 100n + BigInt((match[2] ?? "").padEnd(2, "0") || "0")
      );
    }
  }
  let cents = 0n;
  for (const value of centsById.values()) cents += value;
  return `${cents / 100n}.${String(cents % 100n).padStart(2, "0")}`;
});

/** Refund total: the server value first, then the deduplicated client sum for viewers without it. */
const refundTotalAmount = computed(
  () => chain.value?.funds?.refundTotal ?? completedRefundTotal.value
);

/** Pay amount: the funds block first, then the order detail header value. */
const payAmountText = computed(() =>
  formatAmountWithCurrency(chain.value?.funds?.payAmount ?? detail.value?.payAmount ?? null)
);

/** Non-null flow rows for the table; the empty cases come from {@link #flowsView}. */
const flows = computed(() =>
  Array.isArray(chain.value?.financeFlows) ? chain.value.financeFlows : []
);

const chainStages = computed(() => {
  const lines = chain.value?.lines ?? [];
  const jobs = [
    ...new Map(
      lines.filter(line => line.copyJob).map(line => [line.copyJob!.jobCode, line.copyJob!])
    ).values()
  ];
  const links = [
    ...new Map(
      lines.filter(line => line.link).map(line => [line.link!.kolItemId, line.link!])
    ).values()
  ];
  const refunds = [
    ...new Map(lines.flatMap(line => line.refunds).map(refund => [refund.id, refund])).values()
  ];
  const jobStates = [...new Set(jobs.map(job => job.status?.trim().toLowerCase()).filter(Boolean))];
  const missingJobStatus = jobs.some(job => !job.status?.trim());
  const jobState = jobStates.length === 1 && !missingJobStatus ? jobStates[0] : null;
  const jobStateKey = `copyJob.jobStatus.${jobState}`;
  const knownJobState = ["processing", "completed", "partial_failed", "failed"].includes(
    jobState ?? ""
  );
  const count = (value: number) => t("order.chain.recordCount", { count: value });
  const hasFinancials =
    canSeeCommission.value && lines.length > 0 && lines.every(line => line.commission != null);
  const occupied = hasFinancials
    ? sumKnownCny([
        ...new Map(lines.map(line => [line.orderItemId, line.commission!.occupiedTotal])).values()
      ])
    : null;
  const withdrawalRecords = hasFinancials
    ? lines.flatMap(line => line.commission!.withdrawals)
    : null;
  const withdrawals =
    withdrawalRecords &&
    withdrawalRecords.every(withdrawal =>
      ["PENDING_REVIEW", "PENDING_PAYMENT", "PAID", "REJECTED", "WITHDRAWN"].includes(
        withdrawal.status
      )
    )
      ? new Set(
          withdrawalRecords
            .filter(withdrawal =>
              ["PENDING_REVIEW", "PENDING_PAYMENT", "PAID"].includes(withdrawal.status)
            )
            .map(withdrawal => withdrawal.withdrawalId)
        ).size
      : null;
  return [
    {
      key: "copy",
      icon: Document,
      tone: "success",
      label: t("order.chain.copyJob"),
      state: !jobs.length
        ? t("order.chain.notLinked")
        : missingJobStatus
          ? t("finance.unverified")
          : jobStates.length > 1
            ? t("order.chain.mixedStates")
            : knownJobState
              ? t(jobStateKey)
              : (jobs[0]?.status ?? t("finance.unverified")),
      stateTone:
        jobState === "failed"
          ? "danger"
          : jobState === "partial_failed"
            ? "warning"
            : jobState === "completed"
              ? "success"
              : "info",
      reference: jobs.length === 1 ? jobs[0]!.jobCode : jobs.length ? count(jobs.length) : "-",
      amount: null
    },
    {
      key: "link",
      icon: Link,
      tone: "success",
      label: t("order.chain.kolItem"),
      state: links.length ? t("order.chain.linkCreated") : t("order.chain.notLinked"),
      stateTone: "info",
      reference:
        links.length === 1 ? (links[0]!.linkCode ?? "-") : links.length ? count(links.length) : "-",
      amount: null
    },
    {
      key: "order",
      icon: ShoppingCart,
      tone: "success",
      label: t("order.chain.orderNode"),
      state: detail.value ? orderStatusText(detail.value.orderStatus) : "-",
      stateTone:
        detail.value?.orderStatus === "UNKNOWN"
          ? "danger"
          : ["RECEIVED", "COMPLETED"].includes(detail.value?.orderStatus ?? "")
            ? "success"
            : ["PAID", "SHIPPED"].includes(detail.value?.orderStatus ?? "")
              ? "primary"
              : "info",
      reference: chain.value?.orderNo ?? "-",
      amount: null
    },
    {
      key: "refund",
      icon: Refresh,
      tone: "neutral",
      stateTone: "info",
      label: t("order.chain.refunds"),
      state: count(refunds.length),
      reference: null,
      amount:
        refundTotalAmount.value == null
          ? t("finance.unverified")
          : formatAmountWithCurrency(refundTotalAmount.value)
    },
    {
      key: "finance",
      icon: Money,
      tone: "finance",
      stateTone: "info",
      label: t("order.chain.financeNode"),
      state:
        canSeeCommission.value && Array.isArray(chain.value?.financeFlows)
          ? t("order.chain.recordCount", { count: flows.value.length })
          : "-",
      reference: null,
      amount: t("finance.unverified")
    },
    {
      key: "withdrawal",
      icon: Wallet,
      tone: "neutral",
      stateTone: "info",
      label: t("order.chain.withdrawalOccupation"),
      state: withdrawals == null ? "-" : count(withdrawals),
      reference: null,
      amount: occupied == null ? t("finance.unverified") : formatAmountWithCurrency(occupied)
    }
  ];
});

/** Sum only the backend's current occupation totals; never mix ledger flow types or use floats. */
function sumKnownCny(values: string[]): string | null {
  let cents = 0n;
  for (const value of values) {
    const match = /^(\d+)(?:\.(\d{1,2}))?$/.exec(value);
    if (!match) return null;
    cents += BigInt(match[1]!) * 100n + BigInt((match[2] ?? "").padEnd(2, "0") || "0");
  }
  return `${cents / 100n}.${String(cents % 100n).padStart(2, "0")}`;
}

/**
 * What the finance ledger section shows: the flow table, its empty state, or the reason the
 * financial data is hidden (no commission:view, or an OWN scope on a mixed-attribution order).
 */
const flowsView = computed<"table" | "empty" | "noPermission" | "scopeLimited" | "incomplete">(
  () => {
    if (!canSeeCommission.value) return "noPermission";
    if (chain.value && chain.value.financeFlows === undefined) return "incomplete";
    if (chain.value?.financeFlows != null && !Array.isArray(chain.value.financeFlows))
      return "incomplete";
    if (
      flows.value.some(
        flow =>
          !flow ||
          typeof flow.flowNo !== "string" ||
          !flow.flowNo.trim() ||
          typeof flow.orderItemId !== "string" ||
          !flow.orderItemId.trim() ||
          typeof flow.type !== "string" ||
          !flow.type.trim() ||
          typeof flow.direction !== "string" ||
          !flow.direction.trim() ||
          typeof flow.amount !== "string" ||
          !flow.amount.trim()
      )
    )
      return "incomplete";
    if (flows.value.length) return "table";
    if (chain.value?.financeFlows) return "empty";
    if (userStore.isOwnScope) return "scopeLimited";
    return "incomplete";
  }
);

function flowTypeText(type: string | null | undefined): string {
  if (!type) return "-";
  const key = `order.chain.flowType.${type}`;
  return te(key) ? t(key) : type;
}

function flowDirectionText(direction: string | null | undefined): string {
  if (direction === "INCREASE") return t("order.chain.directionIncrease");
  if (direction === "DECREASE") return t("order.chain.directionDecrease");
  return direction || "-";
}

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

function lineItem(line: ChainLineVO) {
  return detail.value?.items.find(item => item.id === line.orderItemId);
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
  { key: "product", align: "left", label: t("order.chain.summaryProduct"), minWidth: 230 },
  { key: "status", label: t("common.status"), minWidth: 100 },
  { key: "kol", align: "center", label: t("order.chain.summaryKol"), minWidth: 145 },
  { key: "payAmount", label: t("order.payAmount"), minWidth: 110, amount: true },
  {
    key: "rate",
    label: t("order.chain.summaryRate"),
    minWidth: locale.value.startsWith("en") ? 150 : 100
  },
  { key: "commission", label: t("order.chain.summaryCommission"), minWidth: 120, amount: true },
  { key: "occupied", label: t("order.chain.withdrawalOccupation"), minWidth: 110, amount: true }
]);

/** Product name of a finance flow, matched by its order-item id. */
function flowProductName(orderItemId: string): string {
  const item = detail.value?.items.find(candidate => candidate.id === orderItemId);
  return item?.itemName ?? t("order.detail.itemName");
}

const refundColumns = computed<DataColumn[]>(() => [
  { key: "refundNo", align: "left" as const, label: t("order.refund.no"), width: 150 },
  { key: "refundType", label: t("order.refund.typeColumn"), width: 100 },
  { key: "refundStatus", label: t("order.refund.statusColumn"), width: 100 },
  { key: "amount", label: t("order.refund.amount"), width: 100, amount: true },
  { key: "finishTime", label: t("order.refund.finishTime"), width: 170 }
]);

const withdrawalColumns = computed<DataColumn[]>(() => [
  {
    key: "applicationNo",
    align: "left" as const,
    label: t("order.chain.applicationNo"),
    width: 170
  },
  { key: "occupiedCny", label: t("order.chain.withdrawalAmount"), width: 110, amount: true },
  { key: "status", label: t("finance.status"), width: 100 },
  { key: "paidAt", label: t("order.chain.paidAt"), width: 170 }
]);

const flowColumns = computed<DataColumn[]>(() => [
  { key: "flowNo", align: "left" as const, label: t("order.chain.flowNo"), width: 130 },
  { key: "shopName", align: "left" as const, label: t("order.chain.shopName"), width: 130 },
  { key: "type", label: t("order.chain.financeType"), width: 110 },
  { key: "direction", label: t("order.chain.direction"), width: 80 },
  { key: "amount", label: t("order.chain.financeAmount"), width: 110, amount: true },
  { key: "businessAt", label: t("order.chain.businessTime"), width: 170 },
  { key: "transferredAt", label: t("order.chain.transferTime"), width: 170 },
  {
    key: "withdrawalNo",
    align: "left" as const,
    label: t("order.chain.linkedSettlementNo"),
    width: 170
  }
]);

/** The product-task page owns the copy jobs; keep the job code as the filter (task 38). */
function openCopyJob(jobCode: string): void {
  void router.push({ path: "/product/tasks", query: { jobCode } });
}

/** Task 59: the refund list and the creator item list read these as pre-filled filters. */
function openRefund(refundNo: string): void {
  void router.push({ path: "/order/refunds", query: { refundNo } });
}

function openKolItem(linkCode: string): void {
  void router.push({ path: "/kol/kol-items", query: { linkCode } });
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
    <template v-if="chain">
      <section class="chain-page__card" data-test="chain-funds">
        <h3 class="chain-page__title">{{ t("order.chain.fundsSection") }}</h3>
        <div class="chain-fund-cards">
          <article class="chain-fund-card">
            <span>{{ t("order.payAmount") }}</span>
            <strong>{{ payAmountText }}</strong>
          </article>
          <article class="chain-fund-card">
            <span>{{ t("order.refundTotal") }}</span>
            <strong>{{
              refundTotalAmount
                ? formatAmountWithCurrency(refundTotalAmount, "CNY")
                : t("common.developing")
            }}</strong>
          </article>
          <article
            class="chain-fund-card"
            :class="{ 'chain-fund-card--pending': chain.funds?.kolCommission == null }"
          >
            <span>{{ t("order.chain.kolCommission") }}</span>
            <strong>{{ fundsText(chain.funds?.kolCommission) }}</strong>
          </article>
        </div>
      </section>

      <section class="chain-page__card chain-order-info" data-test="chain-order-info">
        <h3 class="chain-page__title">{{ t("order.chain.orderInfoSection") }}</h3>
        <ol class="chain-stages" :aria-label="t('order.chain.wiringDetails')">
          <li
            v-for="(stage, index) in chainStages"
            :key="stage.key"
            :data-test="`chain-stage-${stage.key}`"
            :class="`chain-stage--${stage.tone}`"
          >
            <span class="chain-stage__icon" aria-hidden="true"
              ><el-icon><component :is="stage.icon" /></el-icon
            ></span>
            <div class="chain-stage__content">
              <span class="chain-stage__label">{{ stage.label }}</span>
              <span class="chain-stage__state" :class="`chain-stage__state--${stage.stateTone}`">{{
                stage.state
              }}</span>
              <CopyText
                v-if="stage.reference && stage.reference !== '-'"
                :value="stage.reference"
              />
              <span v-else-if="stage.reference" class="chain-product__secondary">{{
                stage.reference
              }}</span>
              <span v-if="stage.amount" class="chain-product__secondary chain-money">{{
                stage.amount
              }}</span>
            </div>
            <el-icon
              v-if="index < chainStages.length - 1"
              class="chain-stage__arrow"
              aria-hidden="true"
              ><Right
            /></el-icon>
          </li>
        </ol>
        <div class="chain-page__card chain-page__card--nested" data-test="chain-header">
          <dl class="chain-order-band">
            <div>
              <dt>{{ t("order.orderId") }}</dt>
              <dd>
                <CopyText :value="chain.orderNo" />
              </dd>
            </div>
            <div v-if="detail">
              <dt>{{ t("order.shop") }}</dt>
              <dd>{{ shopLabel(detail.shopName, detail.shopCode) }}</dd>
            </div>
            <div v-if="detail">
              <dt>{{ t("order.channel") }}</dt>
              <dd>{{ channelText(detail.channel) }}</dd>
            </div>
            <div>
              <dt>{{ t("order.chain.targetItemId") }}</dt>
              <dd><CopyText v-if="targetIds !== '-'" :value="targetIds" /><span v-else>-</span></dd>
            </div>
            <div>
              <dt>{{ t("order.chain.sourceItemId") }}</dt>
              <dd><CopyText v-if="sourceIds !== '-'" :value="sourceIds" /><span v-else>-</span></dd>
            </div>
            <div v-if="detail">
              <dt>{{ t("order.detail.orderTime") }}</dt>
              <dd>{{ formatInstant(detail.orderTime) }}</dd>
            </div>
          </dl>
        </div>

        <DataTable
          plain
          table-id="chain-product-summary"
          data-test="chain-product-summary"
          :rows="chain.lines"
          :columns="summaryColumns"
          :total="chain.lines.length"
          :paged="false"
          :fixed-columns="false"
        >
          <template #cell-product="{ row }">
            <OrderProductCell
              :image-url="lineItem(row as ChainLineVO)?.imageUrl"
              :name="lineName(row as ChainLineVO)"
              :sku="lineSku(row as ChainLineVO)"
              :product-id="
                (row as ChainLineVO).link?.targetItemId ??
                lineItem(row as ChainLineVO)?.weidianItemId
              "
            />
          </template>
          <template #cell-status="{ row }">{{ lineStatus(row as ChainLineVO) }}</template>
          <template #cell-kol="{ row }">
            <NameCode
              v-if="lineItem(row as ChainLineVO)?.kolCode || (row as ChainLineVO).link?.kolCode"
              :name="lineItem(row as ChainLineVO)?.kolName ?? (row as ChainLineVO).link?.kolName"
              :code="lineItem(row as ChainLineVO)?.kolCode ?? (row as ChainLineVO).link?.kolCode"
            />
            <span v-else>-</span>
          </template>
          <template #cell-payAmount="{ row }">{{
            formatAmountWithCurrency((row as ChainLineVO).payAmount)
          }}</template>
          <template #cell-rate="{ row }">{{
            (row as ChainLineVO).commission?.rateSnapshot == null
              ? fundsText(null)
              : formatRate((row as ChainLineVO).commission!.rateSnapshot)
          }}</template>
          <template #cell-commission="{ row }">{{
            fundsText((row as ChainLineVO).commission?.netCommission)
          }}</template>
          <template #cell-occupied="{ row }">{{
            fundsText((row as ChainLineVO).commission?.occupiedTotal)
          }}</template>
        </DataTable>

        <details class="chain-wiring-details" data-test="chain-wiring-details">
          <summary>{{ t("order.chain.wiringDetails") }}</summary>
          <section
            v-for="line in chain.lines"
            :key="line.orderItemId"
            class="chain-page__card chain-line"
            data-test="chain-line"
          >
            <h4 class="chain-line__title">
              <!-- R-18: the other blocks read "label + value"; the product title now does too. -->
              <span class="chain-line__product-label">{{ t("order.chain.product") }}</span>
              {{ lineName(line) }}
              <span v-if="lineSku(line)" class="chain-line__sku">{{ lineSku(line) }}</span>
            </h4>

            <div class="chain-line__blocks">
              <div class="chain-block" data-test="chain-copy-job">
                <div class="chain-block__title">{{ t("order.chain.copyJob") }}</div>
                <template v-if="line.copyJob">
                  <el-button
                    link
                    type="primary"
                    data-test="chain-copy-job-link"
                    @click="openCopyJob(line.copyJob.jobCode)"
                  >
                    {{ line.copyJob.jobCode }}
                  </el-button>
                  <div class="chain-block__text">
                    {{ t("order.chain.copyJobTime") }}: {{ formatInstant(line.copyJob.createTime) }}
                  </div>
                </template>
                <div v-else class="chain-block__text">-</div>
              </div>

              <div class="chain-block" data-test="chain-kol-item">
                <div class="chain-block__title">{{ t("order.chain.kolItem") }}</div>
                <template v-if="line.link">
                  <div class="chain-block__text">
                    {{ t("order.chain.linkCode") }}:
                    <!-- R-05: codes never wrap; the ellipsis keeps the line single-line and the jump
                     lands on the creator item list pre-filtered by this code (task 59). -->
                    <el-button
                      link
                      type="primary"
                      class="chain-block__code"
                      data-test="chain-kol-item-link"
                      @click="openKolItem(line.link.linkCode ?? '')"
                    >
                      {{ line.link.linkCode ?? "-" }}
                    </el-button>
                  </div>
                  <div class="chain-block__text">
                    {{ t("order.chain.sourceItemId") }}: {{ line.link.sourceProductId ?? "-" }}
                  </div>
                  <div class="chain-block__text">
                    {{ t("order.chain.targetItemId") }}: {{ line.link.targetItemId ?? "-" }}
                  </div>
                  <div class="chain-block__text">
                    {{ t("order.kol") }}:
                    <span v-if="line.link.kolCode"
                      ><NameCode :name="line.link.kolName" :code="line.link.kolCode"
                    /></span>
                    <span v-else>-</span>
                  </div>
                </template>
                <div v-else class="chain-block__text">-</div>
              </div>

              <div class="chain-block" data-test="chain-order-line">
                <div class="chain-block__title">{{ t("order.chain.orderLine") }}</div>
                <div class="chain-block__text">
                  {{ t("order.line.quantity") }}: {{ line.quantity ?? "-" }}
                </div>
                <div class="chain-block__text">
                  {{ t("order.line.payAmount") }}: {{ formatAmountWithCurrency(line.payAmount) }}
                </div>
              </div>

              <div
                class="chain-block chain-block--wide"
                data-test="chain-commission"
                v-if="line.commission"
              >
                <div class="chain-block__title">{{ t("order.chain.commissionBlock") }}</div>
                <div class="chain-commission-fields">
                  <div class="chain-block__text">
                    {{ t("order.chain.rateSnapshot") }}:
                    {{
                      line.commission.rateSnapshot == null
                        ? t("finance.unverified")
                        : formatRate(line.commission.rateSnapshot)
                    }}
                  </div>
                  <div class="chain-block__text">
                    {{ t("order.chain.kolCommission") }}:
                    {{
                      line.commission.netCommission == null
                        ? t("finance.unverified")
                        : formatAmountWithCurrency(line.commission.netCommission)
                    }}
                  </div>
                  <div class="chain-block__text">
                    {{ t("order.chain.releaseBasis") }}:
                    {{
                      line.commission.releaseBasis == null
                        ? "-"
                        : t(`finance.${line.commission.releaseBasis}`)
                    }}
                  </div>
                  <div class="chain-block__text">
                    {{ t("order.chain.occupiedTotal") }}:
                    {{ formatAmountWithCurrency(line.commission.occupiedTotal) }}
                  </div>
                </div>
                <template v-if="line.commission.withdrawals.length">
                  <div class="chain-block__text chain-block__subtitle">
                    {{ t("order.chain.withdrawalOccupation") }}
                  </div>
                  <DataTable
                    plain
                    :table-id="`chain-withdrawals-${line.orderItemId}`"
                    :rows="line.commission.withdrawals"
                    :columns="withdrawalColumns"
                    :total="line.commission.withdrawals.length"
                    :paged="false"
                  >
                    <template #cell-status="{ row }">{{
                      row.status
                        ? te(`finance.${row.status}`)
                          ? t(`finance.${row.status}`)
                          : row.status
                        : "-"
                    }}</template>
                    <template #cell-occupiedCny="{ row }"
                      ><span class="chain-money">{{
                        formatAmountWithCurrency(row.occupiedCny)
                      }}</span></template
                    >
                    <template #cell-paidAt="{ row }">{{ formatInstant(row.paidAt) }}</template>
                  </DataTable>
                </template>
                <div v-else class="chain-block__text">{{ t("order.chain.noWithdrawal") }}</div>
              </div>

              <div class="chain-block chain-block--wide" data-test="chain-refunds">
                <div class="chain-block__title">{{ t("order.chain.refunds") }}</div>
                <DataTable
                  v-if="line.refunds.length"
                  plain
                  :table-id="`chain-refunds-${line.orderItemId}`"
                  :rows="line.refunds"
                  :columns="refundColumns"
                  :total="line.refunds.length"
                  :paged="false"
                >
                  <template #cell-refundNo="{ row }">
                    <!-- Task 59: jump to the refund list pre-filtered by this refund number. -->
                    <el-button
                      link
                      type="primary"
                      data-test="chain-refund-link"
                      @click="openRefund(row.refundNo)"
                    >
                      {{ row.refundNo }}
                    </el-button>
                  </template>
                  <template #cell-refundType="{ row }">{{
                    refundTypeText(row.refundType)
                  }}</template>
                  <template #cell-refundStatus="{ row }">
                    <el-tag :type="refundTagType(row.refundStatus)">
                      {{ refundStatusText(row.refundStatus) }}
                    </el-tag>
                  </template>
                  <template #cell-amount="{ row }"
                    ><span class="chain-money">{{
                      formatAmountWithCurrency(row.amount)
                    }}</span></template
                  >
                  <template #cell-finishTime="{ row }">{{
                    formatInstant(row.finishTime)
                  }}</template>
                </DataTable>
                <div v-else class="chain-block__text">{{ t("order.chain.noRefund") }}</div>
              </div>
            </div>
          </section>
        </details>

        <div v-if="!chain.lines.length" class="chain-page__card chain-page__empty">
          {{ t("order.chain.noLines") }}
        </div>
      </section>

      <section class="chain-page__card" data-test="chain-finance-ledger">
        <h3 class="chain-page__title">{{ t("order.chain.financeLedgerSection") }}</h3>
        <!-- Shop receivable flows wait for the M0 Weidian settlement net; the type stays reserved. -->
        <el-alert type="info" :closable="false" :title="t('order.chain.financeLedgerNote')" />
        <DataTable
          v-if="flowsView === 'table' || flowsView === 'empty'"
          data-test="chain-flows"
          plain
          table-id="chain-finance-flows"
          :rows="flows"
          :columns="flowColumns"
          :total="flows.length"
          :paged="false"
        >
          <template #cell-flowNo="{ row }">
            <CopyText :value="row.flowNo" />
            <div class="chain-product__secondary">{{ flowProductName(row.orderItemId) }}</div>
          </template>
          <template #cell-type="{ row }">{{ flowTypeText(row.type) }}</template>
          <template #cell-direction="{ row }">{{ flowDirectionText(row.direction) }}</template>
          <template #cell-amount="{ row }"
            ><span class="chain-money">{{ formatAmountWithCurrency(row.amount) }}</span></template
          >
          <template #cell-businessAt="{ row }">{{ formatInstant(row.businessAt) }}</template>
          <template #cell-transferredAt="{ row }">{{ formatInstant(row.transferredAt) }}</template>
          <template #cell-withdrawalNo="{ row }">{{ row.withdrawalNo ?? "－" }}</template>
        </DataTable>
        <div v-if="flowsView === 'empty'" class="chain-ledger-empty">
          {{ t("order.chain.noFlows") }}
        </div>
        <el-alert
          v-else-if="flowsView === 'noPermission'"
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
          :title="t('order.chain.incompleteFlows')"
          data-test="chain-flows-incomplete"
        />
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
}
.chain-page__card {
  padding: var(--tcrm-space-4);
  background: var(--tcrm-color-card);
  border: 1px solid var(--tcrm-color-border);
  border-radius: var(--tcrm-radius-card);
}
.chain-page__card--nested {
  margin-bottom: var(--tcrm-space-3);
  padding: var(--tcrm-space-3);
  background: var(--tcrm-color-order-band);
}
.chain-order-band {
  display: flex;
  flex-wrap: wrap;
  gap: var(--tcrm-space-3) var(--tcrm-space-5);
  margin: 0;
}
.chain-order-band > div {
  display: flex;
  align-items: center;
  gap: var(--tcrm-space-2);
  min-width: 0;
}
.chain-order-band dt {
  color: var(--tcrm-color-text-secondary);
  flex-shrink: 0;
}
.chain-order-band dd {
  margin: 0;
  overflow-wrap: anywhere;
}
.chain-product__secondary {
  color: var(--tcrm-color-text-secondary);
  overflow-wrap: anywhere;
}
.chain-stages {
  display: flex;
  gap: var(--tcrm-space-3);
  margin: 0;
  padding: var(--tcrm-space-3) 0;
  list-style: none;
  overflow-x: auto;
  min-width: 0;
}
.chain-stages li {
  display: flex;
  align-items: flex-start;
  position: relative;
  flex: 1 0 150px;
  gap: var(--tcrm-space-2);
  min-width: 0;
  padding-right: var(--tcrm-space-2);
}
.chain-stage__icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 32px;
  height: 32px;
  flex-shrink: 0;
  border: 1px solid currentColor;
  border-radius: 50%;
  color: var(--tcrm-color-text-secondary);
  background: var(--tcrm-color-muted);
}
.chain-stage--success .chain-stage__icon {
  color: var(--tcrm-color-success);
  background: var(--tcrm-color-success-bg);
}
.chain-stage--finance .chain-stage__icon {
  color: var(--tcrm-color-chain-finance);
  background: color-mix(in srgb, var(--tcrm-color-chain-finance) 8%, var(--tcrm-color-card));
}
.chain-stage__content {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: var(--tcrm-space-1);
  min-width: 0;
  flex: 1;
}
.chain-stage__label {
  color: var(--tcrm-color-text);
}
.chain-stage__state {
  color: var(--tcrm-color-text-secondary);
}
.chain-stage__state--danger {
  color: var(--tcrm-color-danger);
  background: var(--tcrm-color-danger-bg);
}
.chain-stage__state--success {
  color: var(--tcrm-color-success);
  background: var(--tcrm-color-success-bg);
}
.chain-stage__state--warning {
  color: var(--tcrm-color-warning);
  background: var(--tcrm-color-warning-bg);
}
.chain-stage__state--primary {
  color: var(--tcrm-color-primary);
  background: var(--tcrm-color-order-band);
}
.chain-stage__state--danger,
.chain-stage__state--success,
.chain-stage__state--warning,
.chain-stage__state--primary {
  border-radius: var(--tcrm-radius-tag);
  padding: 0 var(--tcrm-space-1);
  min-height: 28px;
  display: inline-flex;
  align-items: center;
}
.chain-stage__arrow {
  flex-shrink: 0;
  color: var(--tcrm-color-text-secondary);
  margin-top: var(--tcrm-space-2);
}
.chain-money {
  white-space: nowrap;
  font-variant-numeric: tabular-nums;
}
.chain-wiring-details > summary {
  cursor: pointer;
  color: var(--tcrm-color-primary);
  padding: var(--tcrm-space-3) 0;
}
.chain-wiring-details .chain-line {
  margin-bottom: var(--tcrm-space-3);
}
.chain-order-info {
  display: flex;
  flex-direction: column;
  gap: var(--tcrm-space-3);
}
.chain-fund-cards {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: var(--tcrm-space-3);
}
.chain-fund-card {
  display: flex;
  flex-direction: column;
  gap: var(--tcrm-space-2);
  min-width: 0;
  padding: var(--tcrm-space-3);
  border: 1px solid var(--tcrm-color-border);
  border-radius: var(--tcrm-radius-card);
  background: var(--tcrm-color-card);
}
.chain-fund-card > span {
  color: var(--tcrm-color-text-secondary);
}
.chain-fund-card > strong {
  overflow-wrap: anywhere;
  font-size: var(--tcrm-font-size-section);
}
.chain-fund-card--pending > strong {
  color: var(--el-text-color-secondary);
  font-size: var(--tcrm-font-size-body);
  font-weight: 400;
}
.chain-ledger-empty {
  padding: var(--tcrm-space-5);
  color: var(--tcrm-color-text-secondary);
  text-align: center;
}
.chain-page__title {
  margin: 0 0 var(--tcrm-space-3);
  font-size: var(--tcrm-font-size-section);
  line-height: var(--tcrm-line-height-section);
  font-weight: 600;
}
.chain-page__empty {
  color: var(--tcrm-color-text-secondary);
}
.chain-line__title {
  display: flex;
  flex-wrap: wrap;
  gap: var(--tcrm-space-2);
  align-items: baseline;
  margin: 0 0 var(--tcrm-space-3);
  font-size: var(--tcrm-font-size-section);
  line-height: var(--tcrm-line-height-section);
  font-weight: 600;
}
.chain-line__sku {
  font-size: var(--tcrm-font-size-body);
  line-height: var(--tcrm-line-height-body);
  font-weight: 400;
  color: var(--tcrm-color-text-secondary);
}
.chain-line__blocks {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(220px, 1fr));
  gap: var(--tcrm-space-3);
}
.chain-block {
  min-width: 0;
  padding: var(--tcrm-space-3);
  border: 1px solid var(--tcrm-color-border);
  border-radius: var(--tcrm-radius-card);
}
.chain-block--wide {
  /* L-003: the refund table needs the whole row so amounts and finish times stop truncating. */
  grid-column: 1 / -1;
}
@media (max-width: 900px) {
  .chain-fund-cards {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
  .chain-commission-fields {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
.chain-block__title {
  margin-bottom: var(--tcrm-space-2);
  font-weight: 600;
}
.chain-block__subtitle {
  margin: var(--tcrm-space-2) 0 var(--tcrm-space-2);
  font-weight: 600;
}
.chain-commission-fields {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
  gap: var(--tcrm-space-2) var(--tcrm-space-3);
}
.chain-block__text {
  line-height: var(--tcrm-line-height-body);
  word-break: break-all;
}
/* R-05: a code stays on one line and ellipsizes; the other block texts keep breaking freely. */
.chain-block__code {
  max-width: 100%;
  height: auto;
  padding: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  vertical-align: baseline;
  line-height: var(--tcrm-line-height-body);
}
.chain-line__product-label {
  color: var(--tcrm-color-text-secondary);
  font-size: var(--tcrm-font-size-body);
  line-height: var(--tcrm-line-height-body);
  font-weight: 400;
}
</style>
