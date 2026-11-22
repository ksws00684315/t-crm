<script setup lang="ts">
import NameCode from "@/components/NameCode.vue";
import { computed, ref, watch } from "vue";
import { useI18n } from "vue-i18n";
import { getOrder } from "@/api/order/order";
import type { OrderDetailItemVO, OrderDetailVO } from "@/api/types";
import CopyText from "@/components/CopyText.vue";
import DataTable, { type DataColumn } from "@/components/DataTable.vue";
import { useDescriptionColumns } from "@/composables/useDescriptionColumns";
import { formatAmountWithCurrency, formatInstant } from "@/utils/format";
import { notifyError } from "@/utils/notify";
import {
  channelText,
  orderRefundStatusText,
  orderRefundTagType,
  orderStatusText
} from "@/utils/order";
import { i18n, translate } from "@/locales";
import OrderAmounts from "./OrderAmounts.vue";
import OrderProductCell from "./OrderProductCell.vue";
defineOptions({ name: "OrderDetailDrawer" });
const props = defineProps<{ modelValue: boolean; orderId: string | null }>();
const emit = defineEmits<{ (event: "update:modelValue", value: boolean): void }>();
const { t, locale } = useI18n();
const detailRegion = ref<HTMLElement | null>(null);
const detailColumns = useDescriptionColumns(detailRegion);
const detailLabelWidth = computed(() => (locale.value.startsWith("en") ? 150 : 112));
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
  { key: "product", align: "left", label: t("order.drawerProductIdentity"), minWidth: 190 },
  { key: "sku", align: "left", label: t("order.drawerSku"), minWidth: 100 },
  { key: "kol", align: "center", label: t("order.drawerCreator"), minWidth: 120 },
  { key: "unitPrice", label: t("order.detail.unitPrice"), width: 115, amount: true },
  {
    key: "quantity",
    label: t("order.line.quantity"),
    width: locale.value.startsWith("en") ? 90 : 65
  },
  { key: "totalAmount", label: t("order.amounts.productTotal"), width: 130, amount: true },
  { key: "status", label: t("common.status"), width: 85 }
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
    <div ref="detailRegion" v-loading="loading" class="drawer-detail">
      <template v-if="detail">
        <el-descriptions
          :column="detailColumns"
          :label-width="detailLabelWidth"
          border
          data-test="order-detail"
        >
          <el-descriptions-item :label="t('order.orderId')"
            ><CopyText :value="detail.orderNo"
          /></el-descriptions-item>
          <el-descriptions-item :label="t('order.channel')">{{
            channelText(detail.channel)
          }}</el-descriptions-item>
          <el-descriptions-item :label="t('order.drawerShopName')">{{
            detail.shopName || "-"
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
          <el-descriptions-item :label="t('order.detail.orderTime')">{{
            formatInstant(detail.orderTime)
          }}</el-descriptions-item>
          <el-descriptions-item :label="t('order.payTime')">{{
            formatInstant(detail.payTime)
          }}</el-descriptions-item>
        </el-descriptions>
        <OrderAmounts
          class="drawer-amounts"
          :detail="detail"
          :columns="detailColumns"
          :label-width="detailLabelWidth"
          show-settlement
        />
        <DataTable
          plain
          class="drawer-products"
          table-id="order-drawer-products-v3"
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
              separate-sku
          /></template>
          <template #cell-sku="{ row }">{{ asLine(row).skuName || "-" }}</template>
          <template #cell-kol="{ row }"
            ><NameCode
              v-if="asLine(row).kolName || asLine(row).kolCode"
              :name="asLine(row).kolName"
              :code="asLine(row).kolCode"
            /><span v-else>-</span></template
          >
          <template #cell-quantity="{ row }">{{ asLine(row).quantity ?? "-" }}</template>
          <template #cell-unitPrice="{ row }"
            ><span class="drawer-money">{{
              asLine(row).unitPrice == null
                ? t("order.missingHistoricalPrice")
                : formatAmountWithCurrency(asLine(row).unitPrice)
            }}</span></template
          >
          <template #cell-totalAmount="{ row }"
            ><span class="drawer-money">{{
              asLine(row).totalAmount == null
                ? t("order.missingHistoricalPrice")
                : formatAmountWithCurrency(asLine(row).totalAmount)
            }}</span></template
          >
          <template #cell-status="{ row }">{{ lineStatusText(asLine(row).status) }}</template>
        </DataTable>
      </template>
    </div>
  </el-drawer>
</template>
<style scoped>
.drawer-detail :deep(.el-descriptions__table) {
  table-layout: fixed;
}
.drawer-detail :deep(.el-descriptions__label.is-bordered-label) {
  background-color: var(--tcrm-color-muted);
}
.drawer-detail :deep(.el-descriptions__content) {
  overflow-wrap: anywhere;
}
.drawer-amounts,
.drawer-products {
  margin-top: var(--tcrm-space-4);
}
.drawer-money {
  white-space: nowrap;
  font-variant-numeric: tabular-nums;
}
</style>
