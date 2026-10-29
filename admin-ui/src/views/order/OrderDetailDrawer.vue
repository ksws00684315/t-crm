<script setup lang="ts">
import NameCode from "@/components/NameCode.vue";
import { computed, ref, watch } from "vue";
import { useI18n } from "vue-i18n";
import { getOrder } from "@/api/order/order";
import type { OrderDetailItemVO, OrderDetailVO } from "@/api/types";
import CopyText from "@/components/CopyText.vue";
import DataTable, { type DataColumn } from "@/components/DataTable.vue";
import { useDescriptionColumns } from "@/composables/useDescriptionColumns";
import { formatInstant, formatNameWithCode, formatAmountWithCurrency } from "@/utils/format";
import { notifyError } from "@/utils/notify";
import {
  channelText,
  orderRefundStatusText,
  orderRefundTagType,
  orderStatusText
} from "@/utils/order";
import { i18n, translate } from "@/locales";
import OrderProductCell from "./OrderProductCell.vue";
defineOptions({ name: "OrderDetailDrawer" });
const props = defineProps<{ modelValue: boolean; orderId: string | null }>();
const emit = defineEmits<{ (event: "update:modelValue", value: boolean): void }>();
const { t, locale } = useI18n();
const detailRegion = ref<HTMLElement | null>(null);
const detailColumns = useDescriptionColumns(detailRegion);
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

function lineStatusText(status: string | null | undefined): string {
  if (!status) return "-";
  for (const key of [`order.line.status.${status}`, `order.status.${status}`]) {
    if (i18n.global.te(key)) return translate(key);
  }
  return status;
}

const columns = computed<DataColumn[]>(() => [
  { key: "product", align: "left", label: t("order.drawerProduct"), minWidth: 250 },
  { key: "kol", align: "center", label: t("order.kol"), minWidth: 145 },
  {
    key: "quantity",
    label: t("order.line.quantity"),
    width: locale.value.startsWith("en") ? 90 : 65
  },
  { key: "unitPrice", label: t("order.detail.unitPrice"), width: 110, amount: true },
  { key: "payAmount", label: t("order.actualPaidAmount"), width: 110, amount: true },
  { key: "status", label: t("common.status"), width: 100 }
]);
</script>
<template>
  <el-drawer
    class="order-detail-drawer"
    :model-value="modelValue"
    :title="t('order.detail.title')"
    size="min(var(--tcrm-order-detail-width), calc(100vw - 24px))"
    @update:model-value="emit('update:modelValue', $event)"
  >
    <div ref="detailRegion" v-loading="loading">
      <template v-if="detail">
        <el-descriptions :column="detailColumns" border data-test="order-detail">
          <el-descriptions-item :label="t('order.orderId')"
            ><CopyText :value="detail.orderNo"
          /></el-descriptions-item>
          <el-descriptions-item :label="t('order.channel')">{{
            channelText(detail.channel)
          }}</el-descriptions-item>
          <el-descriptions-item :label="t('order.shop')">{{
            formatNameWithCode(detail.shopName ?? "-", detail.shopCode ?? "-")
          }}</el-descriptions-item>
          <el-descriptions-item :label="t('order.detail.merchantNo')">{{
            detail.merchantNo ?? "-"
          }}</el-descriptions-item>
          <el-descriptions-item :label="t('order.orderStatus')"
            ><el-tag>{{ orderStatusText(detail.orderStatus) }}</el-tag></el-descriptions-item
          >
          <el-descriptions-item :label="t('order.refundStatusColumn')"
            ><el-tag :type="orderRefundTagType(detail.refundStatus)">{{
              orderRefundStatusText(detail.refundStatus)
            }}</el-tag></el-descriptions-item
          >
          <el-descriptions-item :label="t('order.detail.itemAmount')">{{
            formatAmountWithCurrency(detail.itemAmount)
          }}</el-descriptions-item>
          <el-descriptions-item :label="t('order.freight')">{{
            formatAmountWithCurrency(detail.freight)
          }}</el-descriptions-item>
          <el-descriptions-item :label="t('order.detail.totalAmount')">{{
            formatAmountWithCurrency(detail.totalAmount)
          }}</el-descriptions-item>
          <el-descriptions-item :label="t('order.payAmount')">{{
            formatAmountWithCurrency(detail.payAmount)
          }}</el-descriptions-item>
          <el-descriptions-item :label="t('order.detail.orderTime')">{{
            formatInstant(detail.orderTime)
          }}</el-descriptions-item>
          <el-descriptions-item :label="t('order.payTime')">{{
            formatInstant(detail.payTime)
          }}</el-descriptions-item>
        </el-descriptions>
        <DataTable
          plain
          class="drawer-products"
          table-id="order-drawer-products-v2"
          :rows="detail.items"
          :columns="columns"
          :total="detail.items.length"
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
          <template #cell-unitPrice="{ row }"
            ><span class="drawer-money">{{
              formatAmountWithCurrency(asLine(row).unitPrice)
            }}</span></template
          >
          <template #cell-payAmount="{ row }"
            ><span class="drawer-money">{{
              formatAmountWithCurrency(asLine(row).payAmount)
            }}</span></template
          >
          <template #cell-status="{ row }">{{ lineStatusText(asLine(row).status) }}</template>
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
</style>
