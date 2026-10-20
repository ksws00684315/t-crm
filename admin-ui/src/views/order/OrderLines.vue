<script setup lang="ts">
import { computed } from "vue";
import { useI18n } from "vue-i18n";
import type { OrderItemVO, OrderVO } from "@/api/types";
import DataTable, { type DataColumn } from "@/components/DataTable.vue";
import { attributionTypeText } from "@/utils/order";

defineOptions({ name: "OrderLines" });

const props = defineProps<{ order: OrderVO }>();

const { t } = useI18n();

function asLine(row: object): OrderItemVO {
  return row as OrderItemVO;
}

const columns = computed<DataColumn[]>(() => [
  { key: "itemId", label: t("order.line.itemId"), width: 120 },
  { key: "itemName", label: t("order.line.itemName"), prop: "itemName", minWidth: 160 },
  { key: "sku", label: t("order.line.sku"), prop: "skuName", minWidth: 100 },
  { key: "quantity", label: t("order.line.quantity"), width: 70 },
  { key: "payAmount", label: t("order.line.payAmount"), width: 110, amount: true },
  { key: "refund", label: t("order.line.refund"), width: 90, amount: true },
  { key: "kolCode", label: t("order.line.kolCode"), width: 130 },
  { key: "attribution", label: t("order.line.attribution"), width: 100 },
  {
    key: "manualReason",
    label: t("order.line.manualReason"),
    prop: "manualReason",
    minWidth: 140,
    tooltip: true
  }
]);
</script>

<template>
  <div class="order-lines" data-test="order-lines">
    <DataTable
      plain
      table-id="order-lines"
      :rows="props.order.items ?? []"
      :columns="columns"
      :total="(props.order.items ?? []).length"
      :paged="false"
    >
      <template #cell-itemId="{ row }">{{ asLine(row).weidianItemId }}</template>
      <template #cell-quantity="{ row }">{{ asLine(row).quantity ?? "-" }}</template>
      <template #cell-payAmount="{ row }">{{ asLine(row).payAmount ?? "-" }}</template>
      <template #cell-refund="{ row }">{{ asLine(row).refundAmount ?? "-" }}</template>
      <template #cell-kolCode="{ row }">{{ asLine(row).kolCode ?? "-" }}</template>
      <template #cell-attribution="{ row }">
        {{ attributionTypeText(asLine(row).attributionType) }}
      </template>
    </DataTable>
  </div>
</template>

<style scoped>
.order-lines {
  padding: 4px 12px 8px 56px;
}
</style>
