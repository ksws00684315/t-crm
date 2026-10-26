<script setup lang="ts">
import NameCode from "@/components/NameCode.vue";
import { computed, onMounted, ref } from "vue";
import { useI18n } from "vue-i18n";
import { useRoute, useRouter } from "vue-router";
import { getOrder, getOrderChain } from "@/api/order/order";
import type { ChainLineVO, OrderChainVO, OrderDetailVO } from "@/api/types";
import CopyText from "@/components/CopyText.vue";
import DataTable, { type DataColumn } from "@/components/DataTable.vue";
import { useUserStore } from "@/stores/user";
import { formatInstant, formatNameWithCode, formatPrice } from "@/utils/format";
import { notifyError } from "@/utils/notify";
import {
  channelText,
  orderStatusText,
  refundStatusText,
  refundTagType,
  refundTypeText
} from "@/utils/order";
import { formatRate } from "@/utils/rate";

defineOptions({ name: "OrderChainPage" });

/**
 * Order chain (requirement 09.04, task 40 §4): opens as a system tab from the order list since
 * task 59 (R-17). The chain endpoint only carries the per-line wiring, so the page also loads the
 * order detail for the header and the line names. Every product line walks copy job → creator
 * item link → order line → refunds; task 72 wires the real commission data into the funds cards,
 * the per-line commission block and the finance ledger (funds, flows and line blocks stay null
 * without commission:view or, for OWN scopes, on mixed attribution).
 */
const { t } = useI18n();

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

/** Financial data needs commission:view; OWN scopes additionally see nothing on mixed orders. */
const canSeeCommission = computed(() => userStore.can("commission:view"));

const completedRefundTotal = computed(() => {
  const refunds = (chain.value?.lines ?? []).flatMap(line => line.refunds);
  let cents = 0n;
  for (const refund of refunds) {
    if (refund.refundStatus !== "SUCCESS") continue;
    const match = /^(\d+)(?:\.(\d{1,2}))?$/.exec(refund.amount);
    if (!match) return null;
    cents += BigInt(match[1]!) * 100n + BigInt((match[2] ?? "").padEnd(2, "0") || "0");
  }
  return `${cents / 100n}.${String(cents % 100n).padStart(2, "0")}`;
});

/** Refund total: the server deduplicates one refund across lines; fall back to the client sum. */
const refundTotalText = computed(
  () => chain.value?.funds?.refundTotal ?? completedRefundTotal.value ?? t("common.developing")
);

/** Non-null flow rows for the table; the empty cases come from {@link #flowsView}. */
const flows = computed(() => chain.value?.financeFlows ?? []);

/**
 * What the finance ledger section shows: the flow table, its empty state, or the reason the
 * financial data is hidden (no commission:view, or an OWN scope on a mixed-attribution order).
 */
const flowsView = computed<"table" | "empty" | "noPermission" | "scopeLimited">(() => {
  if (flows.value.length) return "table";
  if (chain.value?.financeFlows) return "empty";
  if (!canSeeCommission.value) return "noPermission";
  if (userStore.isOwnScope) return "scopeLimited";
  return "empty";
});

/**
 * Text for a gated fund value: the amount when known, otherwise the reason it is hidden — no
 * permission, OWN scope on a mixed order — or the unverified placeholder when the funds block is
 * visible but the amount itself waits for the M0 Weidian settlement net (never a fake zero).
 */
function fundsText(value: string | null | undefined): string {
  if (value != null) return formatPrice(value, "CNY");
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

/** Product name of a finance flow, matched by its order-item id. */
function flowProductName(orderItemId: string): string {
  const item = detail.value?.items.find(candidate => candidate.id === orderItemId);
  return item?.itemName ?? t("order.detail.itemName");
}

const refundColumns = computed<DataColumn[]>(() => [
  { key: "refundNo", label: t("order.refund.no"), width: 150 },
  { key: "refundType", label: t("order.refund.typeColumn"), width: 100 },
  { key: "refundStatus", label: t("order.refund.statusColumn"), width: 100 },
  { key: "amount", label: t("order.refund.amount"), width: 100, amount: true },
  { key: "finishTime", label: t("order.refund.finishTime"), width: 170 }
]);

const withdrawalColumns = computed<DataColumn[]>(() => [
  { key: "applicationNo", label: t("order.chain.applicationNo"), width: 170 },
  { key: "occupiedCny", label: t("order.chain.withdrawalAmount"), width: 110, amount: true },
  { key: "status", label: t("finance.status"), width: 100 },
  { key: "paidAt", label: t("order.chain.paidAt"), width: 170 }
]);

const flowColumns = computed<DataColumn[]>(() => [
  { key: "flowNo", label: t("order.chain.flowNo"), width: 130 },
  { key: "shopName", label: t("order.chain.shopName"), width: 130 },
  { key: "type", label: t("order.chain.financeType"), width: 110 },
  { key: "direction", label: t("order.chain.direction"), width: 80 },
  { key: "amount", label: t("order.chain.financeAmount"), width: 110, amount: true },
  { key: "orderItemId", label: t("order.chain.product"), width: 160 },
  { key: "businessAt", label: t("order.chain.businessTime"), width: 170 },
  { key: "transferredAt", label: t("order.chain.transferTime"), width: 170 },
  { key: "withdrawalNo", label: t("order.chain.linkedSettlementNo"), width: 170 }
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
            <strong>{{ chain.funds?.payAmount ?? detail?.payAmount ?? "-" }}</strong>
          </article>
          <article class="chain-fund-card">
            <span>{{ t("order.refundTotal") }}</span>
            <strong>{{ refundTotalText }}</strong>
          </article>
          <article
            class="chain-fund-card"
            :class="{ 'chain-fund-card--pending': chain.funds?.kolCommission == null }"
          >
            <span>{{ t("order.chain.kolCommission") }}</span>
            <strong>{{ fundsText(chain.funds?.kolCommission) }}</strong>
          </article>
          <article class="chain-fund-card chain-fund-card--pending">
            <span>{{ t("order.chain.shopProfit") }}</span>
            <strong>{{ fundsText(chain.funds?.shopProfit) }}</strong>
          </article>
        </div>
        <p class="chain-funds-note">{{ t("order.chain.shopProfitFormula") }}</p>
      </section>

      <section class="chain-page__card chain-order-info" data-test="chain-order-info">
        <h3 class="chain-page__title">{{ t("order.chain.orderInfoSection") }}</h3>
        <div class="chain-page__card chain-page__card--nested" data-test="chain-header">
          <el-descriptions :column="4" border>
            <el-descriptions-item :label="t('order.orderId')">
              <CopyText :value="chain.orderNo" />
            </el-descriptions-item>
            <el-descriptions-item v-if="detail" :label="t('order.channel')">
              {{ channelText(detail.channel) }}
            </el-descriptions-item>
            <el-descriptions-item v-if="detail" :label="t('order.shop')">
              {{ shopLabel(detail.shopName, detail.shopCode) }}
            </el-descriptions-item>
            <el-descriptions-item v-if="detail" :label="t('order.kol')">
              <span v-if="detail.kolCode"
                ><NameCode :name="detail.kolName" :code="detail.kolCode"
              /></span>
              <span v-else>-</span>
            </el-descriptions-item>
            <el-descriptions-item v-if="detail" :label="t('order.orderStatus')">
              {{ orderStatusText(detail.orderStatus) }}
            </el-descriptions-item>
            <el-descriptions-item v-if="detail" :label="t('order.detail.orderTime')">
              {{ formatInstant(detail.orderTime) }}
            </el-descriptions-item>
          </el-descriptions>
        </div>

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
                {{ t("order.line.payAmount") }}: {{ line.payAmount ?? "-" }}
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
                      : formatPrice(line.commission.netCommission, "CNY")
                  }}
                </div>
                <div class="chain-block__text">
                  {{ t("order.chain.shopProfit") }}: {{ t("finance.unverified") }}
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
                  {{ formatPrice(line.commission.occupiedTotal, "CNY") }}
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
                  <template #cell-status="{ row }">{{ t(`finance.${row.status}`) }}</template>
                  <template #cell-occupiedCny="{ row }">{{ row.occupiedCny }}</template>
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
                <template #cell-refundType="{ row }">{{ refundTypeText(row.refundType) }}</template>
                <template #cell-refundStatus="{ row }">
                  <el-tag :type="refundTagType(row.refundStatus)">
                    {{ refundStatusText(row.refundStatus) }}
                  </el-tag>
                </template>
                <template #cell-amount="{ row }">{{ row.amount }}</template>
                <template #cell-finishTime="{ row }">{{ formatInstant(row.finishTime) }}</template>
              </DataTable>
              <div v-else class="chain-block__text">{{ t("order.chain.noRefund") }}</div>
            </div>
          </div>
        </section>

        <div v-if="!chain.lines.length" class="chain-page__card chain-page__empty">
          {{ t("order.chain.noLines") }}
        </div>
      </section>

      <section class="chain-page__card" data-test="chain-finance-ledger">
        <h3 class="chain-page__title">{{ t("order.chain.financeLedgerSection") }}</h3>
        <!-- Shop receivable flows wait for the M0 Weidian settlement net; the type stays reserved. -->
        <el-alert type="info" :closable="false" :title="t('order.chain.financeLedgerNote')" />
        <DataTable
          v-if="flowsView === 'table'"
          data-test="chain-flows"
          plain
          table-id="chain-finance-flows"
          :rows="flows"
          :columns="flowColumns"
          :total="flows.length"
          :paged="false"
        >
          <template #cell-type="{ row }">{{ t(`order.chain.flowType.${row.type}`) }}</template>
          <template #cell-direction="{ row }">
            {{
              t(
                `order.chain.${
                  row.direction === "INCREASE" ? "directionIncrease" : "directionDecrease"
                }`
              )
            }}
          </template>
          <template #cell-amount="{ row }">{{ row.amount }}</template>
          <template #cell-orderItemId="{ row }">{{ flowProductName(row.orderItemId) }}</template>
          <template #cell-businessAt="{ row }">{{ formatInstant(row.businessAt) }}</template>
          <template #cell-transferredAt="{ row }">{{ formatInstant(row.transferredAt) }}</template>
          <template #cell-withdrawalNo="{ row }">{{ row.withdrawalNo ?? "－" }}</template>
        </DataTable>
        <div v-else-if="flowsView === 'empty'" class="chain-ledger-empty">
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
          v-else
          type="warning"
          :closable="false"
          :title="t('finance.scopeLimited')"
          data-test="chain-flows-scope-limited"
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
  background: var(--el-fill-color-lighter);
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
.chain-funds-note {
  margin: var(--tcrm-space-3) 0 0;
  color: var(--tcrm-color-text-secondary);
  font-size: var(--tcrm-font-size-help);
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
