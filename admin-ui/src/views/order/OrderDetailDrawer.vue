<script setup lang="ts">
import NameCode from "@/components/NameCode.vue";
import { computed, ref, watch } from "vue";
import { useI18n } from "vue-i18n";
import { getOrder } from "@/api/order/order";
import type { OrderDetailItemVO, OrderDetailVO } from "@/api/types";
import CopyText from "@/components/CopyText.vue";
import DataTable, { type DataColumn } from "@/components/DataTable.vue";
import { useDescriptionColumns } from "@/composables/useDescriptionColumns";
import { formatInstant, formatNameWithCode } from "@/utils/format";
import { notifyError } from "@/utils/notify";
import {
  channelText,
  orderRefundStatusText,
  orderRefundTagType,
  orderSourceText,
  orderStatusText
} from "@/utils/order";
import { i18n, translate } from "@/locales";

defineOptions({ name: "OrderDetailDialog" });

const props = defineProps<{ modelValue: boolean; orderId: string | null }>();
const emit = defineEmits<{ (event: "update:modelValue", value: boolean): void }>();

const { t } = useI18n();
const detailRegion = ref<HTMLElement | null>(null);
const detailColumns = useDescriptionColumns(detailRegion, 4);
const longFieldSpan = computed(() => (detailColumns.value === 4 ? 2 : detailColumns.value));

function shopLabel(name: string | null | undefined, code: string | null | undefined): string {
  if (!name && !code) return "-";
  return code ? formatNameWithCode(name ?? "-", code) : (name ?? "-");
}

const loading = ref(false);
const detail = ref<OrderDetailVO | null>(null);

watch(
  () => [props.modelValue, props.orderId] as const,
  async ([open, id]) => {
    if (!open || !id) return;
    loading.value = true;
    detail.value = null;
    try {
      detail.value = await getOrder(id);
    } catch (error) {
      notifyError(error);
      emit("update:modelValue", false);
    } finally {
      loading.value = false;
    }
  }
);

function asLine(row: object): OrderDetailItemVO {
  return row as OrderDetailItemVO;
}

/** Raw Weidian line status: translate known keys, pass anything else through (M0 pending). */
function lineStatusText(status: string | null | undefined): string {
  if (!status) return "-";
  const key = `order.line.status.${status}`;
  return i18n.global.te(key) ? translate(key) : status;
}

const columns = computed<DataColumn[]>(() => [
  { key: "image", label: t("order.detail.image"), width: 70 },
  {
    key: "itemName",
    align: "left" as const,
    label: t("order.detail.itemName"),
    prop: "itemName",
    minWidth: 160,
    tooltip: true
  },
  { key: "itemId", align: "left" as const, label: t("order.line.itemId"), width: 120 },
  {
    key: "skuName",
    align: "left" as const,
    label: t("order.line.sku"),
    prop: "skuName",
    minWidth: 100
  },
  { key: "kol", align: "left" as const, label: t("order.kol"), minWidth: 140 },
  { key: "quantity", label: t("order.line.quantity"), width: 70 },
  { key: "unitPrice", label: t("order.detail.unitPrice"), width: 90, amount: true },
  { key: "payAmount", label: t("order.line.payAmount"), width: 100, amount: true },
  { key: "refundAmount", label: t("order.detail.lineRefund"), width: 90, amount: true },
  { key: "status", label: t("common.status"), width: 100 }
]);
</script>

<template>
  <el-dialog
    class="order-detail-dialog"
    :model-value="modelValue"
    :title="t('order.detail.title')"
    width="94vw"
    @update:model-value="emit('update:modelValue', $event)"
  >
    <div ref="detailRegion" v-loading="loading">
      <template v-if="detail">
        <el-descriptions :column="detailColumns" border data-test="order-detail">
          <el-descriptions-item :label="t('order.orderId')" :span="longFieldSpan">
            <CopyText :value="detail.orderNo" />
          </el-descriptions-item>
          <el-descriptions-item :label="t('order.shop')" :span="longFieldSpan">
            {{ shopLabel(detail.shopName, detail.shopCode) }}
          </el-descriptions-item>
          <el-descriptions-item :label="t('order.channel')" :span="detailColumns === 2 ? 2 : 1">
            {{ channelText(detail.channel) }}
          </el-descriptions-item>
          <el-descriptions-item :label="t('order.kol')" :span="longFieldSpan">
            <span v-if="detail.kolCode"
              ><NameCode :name="detail.kolName" :code="detail.kolCode"
            /></span>
            <span v-else>-</span>
          </el-descriptions-item>
          <el-descriptions-item :label="t('order.orderStatus')">
            {{ orderStatusText(detail.orderStatus) }}
          </el-descriptions-item>
          <el-descriptions-item :label="t('order.refundStatusColumn')">
            <el-tag :type="orderRefundTagType(detail.refundStatus)">
              {{ orderRefundStatusText(detail.refundStatus) }}
            </el-tag>
          </el-descriptions-item>
          <el-descriptions-item :label="t('order.detail.merchantNo')" :span="longFieldSpan">
            {{ detail.merchantNo ?? "-" }}
          </el-descriptions-item>
          <el-descriptions-item :label="t('order.detail.itemAmount')">
            {{ detail.itemAmount ?? "-" }}
          </el-descriptions-item>
          <el-descriptions-item :label="t('order.freight')">
            {{ detail.freight ?? "-" }}
          </el-descriptions-item>
          <el-descriptions-item :label="t('order.detail.totalAmount')">
            {{ detail.totalAmount ?? "-" }}
          </el-descriptions-item>
          <el-descriptions-item :label="t('order.payAmount')">
            {{ detail.payAmount ?? "-" }}
          </el-descriptions-item>
          <el-descriptions-item :label="t('order.detail.orderTime')">
            {{ formatInstant(detail.orderTime) }}
          </el-descriptions-item>
          <el-descriptions-item :label="t('order.payTime')">
            {{ formatInstant(detail.payTime) }}
          </el-descriptions-item>
          <el-descriptions-item :label="t('order.detail.source')">
            {{ orderSourceText(detail.source) }}
          </el-descriptions-item>
          <el-descriptions-item :label="t('order.detail.lastSyncTime')">
            {{ formatInstant(detail.lastSyncTime) }}
          </el-descriptions-item>
        </el-descriptions>
        <h4 class="title">{{ t("order.detail.items") }}</h4>
        <DataTable
          plain
          table-id="order-detail-items"
          :rows="detail.items"
          :columns="columns"
          :total="detail.items.length"
          :paged="false"
        >
          <template #cell-image="{ row }">
            <el-image
              v-if="asLine(row).imageUrl"
              class="item-image"
              :src="asLine(row).imageUrl ?? ''"
              :preview-src-list="[asLine(row).imageUrl ?? '']"
              fit="cover"
              preview-teleported
              alt=""
              data-test="item-image"
            />
            <span v-else>-</span>
          </template>
          <template #cell-itemId="{ row }">{{ asLine(row).weidianItemId }}</template>
          <template #cell-skuName="{ row }">{{ asLine(row).skuName ?? "-" }}</template>
          <template #cell-kol="{ row }">
            <span v-if="asLine(row).kolCode"
              ><NameCode :name="asLine(row).kolName" :code="asLine(row).kolCode"
            /></span>
            <span v-else>-</span>
          </template>
          <template #cell-quantity="{ row }">{{ asLine(row).quantity ?? "-" }}</template>
          <template #cell-unitPrice="{ row }">{{ asLine(row).unitPrice ?? "-" }}</template>
          <template #cell-payAmount="{ row }">{{ asLine(row).payAmount ?? "-" }}</template>
          <template #cell-refundAmount="{ row }">{{ asLine(row).refundAmount ?? "-" }}</template>
          <template #cell-status="{ row }">{{ lineStatusText(asLine(row).status) }}</template>
        </DataTable>
      </template>
    </div>
  </el-dialog>
</template>

<style>
.order-detail-dialog {
  max-width: 1440px;
}

.order-detail-dialog .el-dialog__body {
  max-height: calc(90vh - 112px);
  overflow-x: hidden;
  overflow-y: auto;
}

.order-detail-dialog .el-table__body-wrapper,
.order-detail-dialog .el-table__header-wrapper {
  overflow-x: hidden;
}

.order-detail-dialog .el-table {
  width: 100%;
}

.title {
  margin: 20px 0 8px;
}
.item-image {
  width: 40px;
  height: 40px;
  border-radius: var(--tcrm-radius-tag);
}
</style>
