<script setup lang="ts">
import NameCode from "@/components/NameCode.vue";
import { computed, onMounted, ref } from "vue";
import { useI18n } from "vue-i18n";
import { useRoute, useRouter } from "vue-router";
import { getOrder, getOrderChain } from "@/api/order/order";
import type { ChainLineVO, OrderChainVO, OrderDetailVO } from "@/api/types";
import CopyText from "@/components/CopyText.vue";
import DataTable, { type DataColumn } from "@/components/DataTable.vue";
import { formatInstant, formatNameWithCode } from "@/utils/format";
import { notifyError } from "@/utils/notify";
import {
  channelText,
  orderStatusText,
  refundStatusText,
  refundTagType,
  refundTypeText
} from "@/utils/order";

defineOptions({ name: "OrderChainPage" });

/**
 * Order chain (requirement 09.04, task 40 §4): opens as a system tab from the order list since
 * task 59 (R-17). The chain endpoint only carries the per-line wiring, so the page also loads the
 * order detail for the header and the line names. Every product line walks copy job → creator
 * item link → order line → refunds; the settlement blocks (ledger, withdrawals) are out of this
 * task's scope and say "developing".
 */
const { t } = useI18n();

function shopLabel(name: string | null | undefined, code: string | null | undefined): string {
  if (!name && !code) return "-";
  return code ? formatNameWithCode(name ?? "-", code) : (name ?? "-");
}

const route = useRoute();
const router = useRouter();

const loading = ref(false);
const chain = ref<OrderChainVO | null>(null);
/** Header context; degrades to the bare chain when the detail call fails. */
const detail = ref<OrderDetailVO | null>(null);

/** Line titles come from the detail items matched by order-item id. */
function lineName(line: ChainLineVO): string {
  const item = detail.value?.items.find(candidate => candidate.id === line.orderItemId);
  return item?.itemName ?? t("order.detail.itemName");
}

function lineSku(line: ChainLineVO): string | null {
  return detail.value?.items.find(candidate => candidate.id === line.orderItemId)?.skuName ?? null;
}

const refundColumns = computed<DataColumn[]>(() => [
  { key: "refundNo", label: t("order.refund.no"), width: 150 },
  { key: "refundType", label: t("order.refund.typeColumn"), width: 100 },
  { key: "refundStatus", label: t("order.refund.statusColumn"), width: 100 },
  { key: "amount", label: t("order.refund.amount"), width: 100, amount: true },
  { key: "finishTime", label: t("order.refund.finishTime"), width: 170 }
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

onMounted(async () => {
  const id = String(route.params.id ?? "");
  if (!id) return;
  loading.value = true;
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
});
</script>

<template>
  <div v-loading="loading" class="chain-page">
    <template v-if="chain">
      <div class="chain-page__card" data-test="chain-header">
        <h3 class="chain-page__title">{{ t("route.orderChain") }}</h3>
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

          <div class="chain-block" data-test="chain-ledger">
            <div class="chain-block__title">{{ t("order.chain.ledger") }}</div>
            <div class="chain-block__text chain-block__developing">
              {{ t("common.developing") }}
            </div>
          </div>

          <div class="chain-block" data-test="chain-withdrawals">
            <div class="chain-block__title">{{ t("order.chain.withdrawals") }}</div>
            <div class="chain-block__text chain-block__developing">
              {{ t("common.developing") }}
            </div>
          </div>
        </div>
      </section>

      <div v-if="!chain.lines.length" class="chain-page__card chain-page__empty">
        {{ t("order.chain.noLines") }}
      </div>
    </template>
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
.chain-block__title {
  margin-bottom: var(--tcrm-space-2);
  font-weight: 600;
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
.chain-block__developing {
  color: var(--tcrm-color-text-secondary);
}
</style>
