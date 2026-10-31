<script setup lang="ts">
import { computed } from "vue";
import { useI18n } from "vue-i18n";
import type { CommissionLine } from "@/api/commission/finance-types";
import DataTable, { type DataColumn } from "@/components/DataTable.vue";
import CopyText from "@/components/CopyText.vue";
import { formatRate } from "@/utils/rate";
import { formatInstant, formatPrice } from "@/utils/format";
defineProps<{ rows: CommissionLine[]; tableId: string; settlement?: boolean }>();
const { t } = useI18n();
const columns = computed<DataColumn[]>(() =>
  [
    "orderNo",
    "product",
    "itemId",
    "base",
    "rate",
    "rateVersion",
    "ruleVersion",
    "commission",
    "adjustment",
    "releaseBasis",
    "businessAt"
  ].map(key => ({
    key,
    prop: key,
    label: t(`finance.${key}`),
    minWidth: ["releaseBasis", "businessAt", "product"].includes(key) ? 210 : 160,
    amount: ["base", "commission", "adjustment"].includes(key),
    align: ["orderNo", "product", "itemId"].includes(key) ? "left" : "center",
    tooltip: true
  }))
);
</script>
<template>
  <DataTable
    :table-id="tableId"
    :rows="rows"
    :columns="columns"
    :total="rows.length"
    :paged="false"
    plain
  >
    <template #cell-orderNo="{ row }"><CopyText :value="row.orderNo" /></template>
    <template #cell-base="{ row }">{{
      row.base === null ? t("finance.unverified") : formatPrice(row.base, "CNY")
    }}</template>
    <template #cell-rate="{ row }">{{
      row.rate == null ? t("finance.unverified") : formatRate(row.rate)
    }}</template>
    <template #cell-rateVersion="{ row }">{{
      row.rateVersion ?? t("finance.unverified")
    }}</template>
    <template #cell-commission="{ row }">{{
      row.commission === null ? t("finance.unverified") : formatPrice(row.commission, "CNY")
    }}</template>
    <template #cell-adjustment="{ row }"
      ><span data-test="commission-adjustment">{{
        row.releaseBasis === "UNVERIFIED" || row.adjustment === null
          ? t("finance.unverified")
          : formatPrice(row.adjustment, "CNY")
      }}</span></template
    >
    <template #cell-releaseBasis="{ row }"
      ><el-tag :type="row.releaseBasis === 'CONFIRMED' ? 'success' : 'warning'">{{
        t(`finance.${row.releaseBasis ?? "UNVERIFIED"}`)
      }}</el-tag></template
    >
    <template #cell-businessAt="{ row }">{{ formatInstant(row.businessAt) }}</template>
  </DataTable>
</template>
