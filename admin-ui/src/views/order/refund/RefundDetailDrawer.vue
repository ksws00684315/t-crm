<script setup lang="ts">
import NameCode from "@/components/NameCode.vue";
import { computed, ref, watch } from "vue";
import { useI18n } from "vue-i18n";
import { getRefund } from "@/api/order/order";
import type { RefundItemVO, RefundDetailVO } from "@/api/types";
import CopyText from "@/components/CopyText.vue";
import DataTable, { type DataColumn } from "@/components/DataTable.vue";
import { useDescriptionColumns } from "@/composables/useDescriptionColumns";
import { formatInstant, formatNameWithCode, formatAmountWithCurrency } from "@/utils/format";
import { notifyError } from "@/utils/notify";
import { channelText, refundStatusText, refundTagType, refundTypeText } from "@/utils/order";

import OrderProductCell from "../OrderProductCell.vue";
defineOptions({ name: "RefundDetailDrawer" });
const props = defineProps<{ modelValue: boolean; refundId: string | null }>();
const emit = defineEmits<{ (event: "update:modelValue", value: boolean): void }>();
const { t, locale } = useI18n();
const detailRegion = ref<HTMLElement | null>(null);
const detailColumns = useDescriptionColumns(detailRegion);
const loading = ref(false);
const detail = ref<RefundDetailVO | null>(null);
watch(
  () => [props.modelValue, props.refundId] as const,
  async ([open, id]) => {
    if (!open || !id) return;
    loading.value = true;
    detail.value = null;
    try {
      detail.value = await getRefund(id);
    } catch (error) {
      notifyError(error);
      emit("update:modelValue", false);
    } finally {
      loading.value = false;
    }
  }
);
function asLine(row: object): RefundItemVO {
  return row as RefundItemVO;
}

const columns = computed<DataColumn[]>(() => [
  { key: "product", align: "left", label: t("order.drawerProduct"), minWidth: 270 },
  { key: "kol", align: "center", label: t("order.kol"), minWidth: 165 },
  {
    key: "quantity",
    label: t("order.line.quantity"),
    width: locale.value.startsWith("en") ? 90 : 65
  },
  { key: "refundAmount", label: t("order.refund.amount"), width: 125, amount: true }
]);
</script>
<template>
  <el-drawer
    class="refund-detail-drawer"
    :model-value="modelValue"
    :title="t('order.refund.detailTitle')"
    size="min(var(--tcrm-refund-detail-width), calc(100vw - 24px))"
    @update:model-value="emit('update:modelValue', $event)"
  >
    <div ref="detailRegion" v-loading="loading">
      <template v-if="detail">
        <el-descriptions :column="detailColumns" border data-test="refund-detail">
          <el-descriptions-item :label="t('order.refund.no')"
            ><CopyText :value="detail.refundNo"
          /></el-descriptions-item>
          <el-descriptions-item :label="t('order.orderId')"
            ><CopyText :value="detail.orderNo"
          /></el-descriptions-item>
          <el-descriptions-item :label="t('order.channel')">{{
            channelText(detail.channel)
          }}</el-descriptions-item>
          <el-descriptions-item :label="t('order.shop')">{{
            formatNameWithCode(detail.shopName ?? "-", detail.shopCode ?? "-")
          }}</el-descriptions-item>
          <el-descriptions-item :label="t('order.kol')"
            ><span class="refund-detail-identity"
              ><NameCode v-if="detail.kolCode" :name="detail.kolName" :code="detail.kolCode" /><span
                v-else
                >-</span
              ></span
            ></el-descriptions-item
          >
          <el-descriptions-item :label="t('order.detail.merchantNo')">{{
            detail.merchantNo ?? "-"
          }}</el-descriptions-item>
          <el-descriptions-item :label="t('order.refund.typeColumn')"
            ><el-tag type="info">{{
              refundTypeText(detail.refundType)
            }}</el-tag></el-descriptions-item
          >
          <el-descriptions-item :label="t('order.refund.statusColumn')"
            ><el-tag :type="refundTagType(detail.refundStatus)">{{
              refundStatusText(detail.refundStatus)
            }}</el-tag></el-descriptions-item
          >
          <el-descriptions-item :label="t('order.refund.amount')">{{
            formatAmountWithCurrency(detail.amount)
          }}</el-descriptions-item>
          <el-descriptions-item :label="t('order.refund.applyTime')">{{
            formatInstant(detail.applyTime)
          }}</el-descriptions-item>
          <el-descriptions-item :label="t('order.refund.finishTime')" :span="detailColumns">{{
            formatInstant(detail.finishTime)
          }}</el-descriptions-item>
        </el-descriptions>
        <DataTable
          plain
          class="drawer-products"
          table-id="refund-drawer-products-v2"
          :rows="detail.lines"
          :columns="columns"
          :total="detail.lines.length"
          :paged="false"
          :fixed-columns="false"
        >
          <template #cell-product="{ row }"
            ><OrderProductCell
              :image-url="asLine(row).imageUrl"
              :name="asLine(row).itemName"
              :product-id="asLine(row).weidianItemId"
              :sku="asLine(row).skuName"
          /></template>
          <template #cell-kol="{ row }"
            ><NameCode
              v-if="asLine(row).kolCode"
              :name="asLine(row).kolName"
              :code="asLine(row).kolCode"
            /><span v-else>-</span></template
          >
          <template #cell-quantity="{ row }">{{ asLine(row).quantity ?? "-" }}</template>
          <template #cell-refundAmount="{ row }"
            ><span class="drawer-money">{{
              formatAmountWithCurrency(asLine(row).refundAmount)
            }}</span></template
          >
        </DataTable>
      </template>
    </div>
  </el-drawer>
</template>
<style scoped>
:deep(.el-descriptions__label) {
  width: 112px;
}
.drawer-products {
  margin-top: var(--tcrm-space-4);
}
.drawer-money {
  white-space: nowrap;
  font-variant-numeric: tabular-nums;
}
.refund-detail-identity :deep(.name-code) {
  align-items: flex-start;
  text-align: left;
  width: auto;
}
</style>
