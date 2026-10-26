<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from "vue";
import { useI18n } from "vue-i18n";
import { getWithdrawal, getWithdrawalOrders } from "@/api/commission/finance";
import type { Withdrawal, WithdrawalOrder } from "@/api/commission/finance-types";
import DataTable, { type DataColumn } from "@/components/DataTable.vue";
import CopyText from "@/components/CopyText.vue";
import { formatInstant } from "@/utils/format";
import { notifyError } from "@/utils/notify";
import { subscribeNarrow } from "@/utils/sidebar";

const props = defineProps<{ modelValue: boolean; id: string }>();
const emit = defineEmits<{ "update:modelValue": [value: boolean] }>();
const { t } = useI18n();
const detail = ref<Withdrawal>();
const rows = ref<WithdrawalOrder[]>([]);
const loading = ref(false);
const ordersLoading = ref(false);
const failed = ref(false);
const ordersFailed = ref(false);
const page = ref(1);
const total = ref(0);
const pageSize = 10;
const narrow = ref(false);
let unsubscribe: (() => void) | undefined;
let detailSequence = 0;
let ordersSequence = 0;
onMounted(() => {
  unsubscribe = subscribeNarrow(value => (narrow.value = value));
});
onBeforeUnmount(() => {
  unsubscribe?.();
  ++detailSequence;
  ++ordersSequence;
});
const operationsNote = computed(
  () =>
    detail.value?.operationsNote ||
    detail.value?.history.find(event => event.action === "APPROVED")?.note ||
    "—"
);
const financeNote = computed(
  () =>
    detail.value?.financeNote ||
    detail.value?.payment?.financeNote ||
    detail.value?.history.find(event => event.action === "PENDING_PAYMENT")?.note ||
    "—"
);
const columns = computed<DataColumn[]>(() => [
  { key: "orderNo", label: t("finance.orderNo"), minWidth: 200 },
  { key: "product", label: t("finance.orderProduct"), minWidth: 380 },
  { key: "occupied", label: t("finance.orderOccupied"), minWidth: 140, amount: true },
  { key: "remainingSnapshot", label: t("finance.remainingSnapshot"), minWidth: 160, amount: true }
]);
async function loadDetail() {
  const sequence = ++detailSequence;
  const id = props.id;
  loading.value = true;
  failed.value = false;
  detail.value = undefined;
  try {
    const result = await getWithdrawal(id);
    if (sequence === detailSequence && props.modelValue && props.id === id) detail.value = result;
  } catch (error) {
    if (sequence === detailSequence) {
      failed.value = true;
      notifyError(error);
    }
  } finally {
    if (sequence === detailSequence) loading.value = false;
  }
}
async function loadOrders() {
  const sequence = ++ordersSequence;
  const id = props.id;
  ordersLoading.value = true;
  ordersFailed.value = false;
  rows.value = [];
  try {
    const result = await getWithdrawalOrders(id, { page: page.value, size: pageSize });
    if (sequence === ordersSequence && props.modelValue && props.id === id) {
      rows.value = result.items;
      total.value = Number(result.total);
    }
  } catch (error) {
    if (sequence === ordersSequence) {
      ordersFailed.value = true;
      total.value = 0;
      notifyError(error);
    }
  } finally {
    if (sequence === ordersSequence) ordersLoading.value = false;
  }
}
function changePage(value: number) {
  page.value = value;
  void loadOrders();
}
watch(
  () => [props.modelValue, props.id] as const,
  ([open]) => {
    ++detailSequence;
    ++ordersSequence;
    if (!open) return;
    page.value = 1;
    total.value = 0;
    void loadDetail();
    void loadOrders();
  },
  { immediate: true }
);
</script>

<template>
  <el-dialog
    :model-value="modelValue"
    :title="t('finance.withdrawalDetailTitle')"
    width="min(1120px, calc(100vw - 32px))"
    align-center
    class="withdrawal-view-dialog"
    destroy-on-close
    @update:model-value="emit('update:modelValue', $event)"
  >
    <div v-loading="loading" class="withdrawal-detail" data-test="withdrawal-detail">
      <el-result v-if="failed" icon="error" :title="t('finance.loadFailed')">
        <template #extra
          ><el-button @click="loadDetail">{{ t("finance.retry") }}</el-button></template
        >
      </el-result>
      <template v-if="detail">
        <el-descriptions :column="narrow ? 1 : 2" border class="withdrawal-info">
          <el-descriptions-item :label="t('finance.applicationNo')"
            ><CopyText :value="detail.applicationNo"
          /></el-descriptions-item>
          <el-descriptions-item :label="t('finance.status')">
            <el-tag :type="detail.status === 'PAID' ? 'success' : 'warning'">{{
              t(`finance.${detail.status}`)
            }}</el-tag>
            <el-tag v-if="detail.paused" type="danger">{{ t("finance.paused") }}</el-tag>
          </el-descriptions-item>
          <el-descriptions-item :label="t('finance.creator')">
            <div class="creator-name">{{ detail.kolName }}</div>
            <small class="identity-id">{{ detail.kolCode }}</small>
          </el-descriptions-item>
          <el-descriptions-item :label="t('finance.withdrawalAmount')"
            ><strong>{{ detail.amount }} {{ detail.currency }}</strong></el-descriptions-item
          >
          <el-descriptions-item :label="t('finance.assignedBd')">{{
            detail.bdName || "—"
          }}</el-descriptions-item>
          <el-descriptions-item :label="t('finance.createdAt')">{{
            formatInstant(detail.createdAt)
          }}</el-descriptions-item>
          <el-descriptions-item :label="t('finance.recipient')" :span="narrow ? 1 : 2">
            {{
              [
                detail.account.type,
                detail.account.account,
                detail.account.holder,
                detail.account.currency,
                detail.account.network
              ]
                .filter(Boolean)
                .join(" · ")
            }}
          </el-descriptions-item>
          <el-descriptions-item :label="t('finance.note')" :span="narrow ? 1 : 2">{{
            detail.note || "—"
          }}</el-descriptions-item>
          <el-descriptions-item :label="t('finance.operationsNote')">{{
            operationsNote
          }}</el-descriptions-item>
          <el-descriptions-item :label="t('finance.financeNote')">{{
            financeNote
          }}</el-descriptions-item>
        </el-descriptions>
        <el-alert
          v-if="detail.paused"
          :title="t('finance.refundPause')"
          type="warning"
          :closable="false"
        />
        <header class="orders-heading">
          <strong>{{ t("finance.associatedOrders", { count: total }) }}</strong>
          <span>{{ t("finance.moneyUnit", { currency: detail.currency }) }}</span>
        </header>
        <el-result v-if="ordersFailed" icon="error" :title="t('finance.loadFailed')">
          <template #extra
            ><el-button data-test="withdrawal-orders-retry" @click="loadOrders">{{
              t("finance.retry")
            }}</el-button></template
          >
        </el-result>
        <template v-else>
          <DataTable
            table-id="withdrawal-associated-orders-v2"
            :rows="rows"
            :columns="columns"
            :total="total"
            :loading="ordersLoading"
            :paged="false"
            plain
          >
            <template #cell-orderNo="{ row }"><CopyText :value="row.orderNo" /></template>
            <template #cell-product="{ row }">
              <div class="products">
                <div v-for="product in row.products" :key="product.id" class="product-row">
                  <el-image
                    :src="product.image"
                    :alt="product.product"
                    class="product-image"
                    fit="cover"
                  >
                    <template #error
                      ><span>{{ t("finance.noImage") }}</span></template
                    >
                  </el-image>
                  <div class="product-copy">
                    <span class="product-name">{{ product.product }}</span
                    ><small>{{ product.sku || "—" }}</small>
                  </div>
                </div>
              </div>
            </template>
            <template #cell-occupied="{ row }"
              ><span class="order-money">{{ row.occupied }}</span></template
            >
            <template #cell-remainingSnapshot="{ row }"
              ><span class="order-money">{{
                row.remainingSnapshot ?? t("finance.unverified")
              }}</span></template
            >
          </DataTable>
          <div v-if="total > 0" class="orders-pager">
            <span>{{ t("finance.ordersPerPage", { count: total, size: pageSize }) }}</span>
            <el-pagination
              data-test="withdrawal-orders-pager"
              background
              layout="prev, pager, next"
              :total="total"
              :page-size="pageSize"
              :current-page="page"
              @current-change="changePage"
            />
          </div>
          <p class="snapshot-note">{{ t("finance.orderSnapshotNote") }}</p>
        </template>
      </template>
    </div>
    <template #footer
      ><el-button data-test="withdrawal-detail-close" @click="emit('update:modelValue', false)">{{
        t("finance.close")
      }}</el-button></template
    >
  </el-dialog>
</template>

<style scoped>
.withdrawal-detail {
  display: flex;
  flex-direction: column;
  gap: var(--tcrm-space-3);
  min-width: 0;
}
.withdrawal-info :deep(.el-descriptions__label) {
  width: 120px;
  font-weight: 400;
  color: var(--tcrm-color-text-secondary);
}
.withdrawal-info :deep(.el-descriptions__cell) {
  padding: 8px 12px;
  overflow-wrap: anywhere;
}
.withdrawal-info :deep(.el-descriptions__content) {
  color: var(--el-text-color-primary);
}
.creator-name {
  font-weight: 400;
  color: var(--el-text-color-primary);
}
.identity-id,
.product-copy small,
.snapshot-note {
  color: var(--tcrm-color-text-secondary);
  font-size: var(--tcrm-font-size-help);
  line-height: var(--tcrm-line-height-help);
}
.orders-heading,
.orders-pager {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--tcrm-space-3);
}
.orders-heading strong {
  font-size: var(--tcrm-font-size-section);
}
.orders-heading span,
.orders-pager > span {
  color: var(--tcrm-color-text-secondary);
}
.products {
  display: flex;
  flex-direction: column;
  gap: var(--tcrm-space-2);
}
.product-row {
  display: flex;
  align-items: center;
  gap: var(--tcrm-space-2);
  min-width: 0;
  min-height: 44px;
}
.product-image {
  width: 40px;
  height: 40px;
  flex: 0 0 40px;
  border-radius: var(--tcrm-radius-control);
}
.product-copy {
  display: flex;
  flex-direction: column;
  min-width: 0;
}
.product-name {
  white-space: normal;
  overflow-wrap: anywhere;
  line-height: var(--tcrm-line-height-body);
}
.product-copy small {
  white-space: normal;
  overflow-wrap: anywhere;
}
.order-money {
  display: block;
  padding-right: 20px;
  font-variant-numeric: tabular-nums;
  white-space: nowrap;
}
.snapshot-note {
  margin: 0;
}
.withdrawal-detail :deep(.copy-text) {
  white-space: nowrap;
}
.withdrawal-detail :deep(.data-table .el-table .el-table__cell) {
  padding-block: 6px;
}
@media (max-width: 767px) {
  .orders-heading,
  .orders-pager {
    flex-wrap: wrap;
  }
  .withdrawal-info :deep(.el-descriptions__label) {
    width: 90px;
  }
}
</style>
<style>
.withdrawal-view-dialog {
  display: flex;
  flex-direction: column;
  max-height: calc(100dvh - 32px);
}
.withdrawal-view-dialog .el-dialog__body {
  min-height: 0;
  overflow-y: auto;
  overscroll-behavior: contain;
}
.withdrawal-view-dialog .el-dialog__header,
.withdrawal-view-dialog .el-dialog__footer {
  flex-shrink: 0;
}
.withdrawal-view-dialog .el-dialog__footer {
  margin-top: 12px;
  padding-top: 12px;
  border-top: 1px solid var(--tcrm-color-border);
}
</style>
