<script setup lang="ts">
import { computed, reactive, ref, watch } from "vue";
import { useI18n } from "vue-i18n";
import { listMissingProducts } from "@/api/product/sheet-source";
import type { SheetMissingVO, SheetSourceVO } from "@/api/types";
import CopyText from "@/components/CopyText.vue";
import DataTable, { type DataColumn } from "@/components/DataTable.vue";
import { formatInstant } from "@/utils/format";
import { notifyError } from "@/utils/notify";

defineOptions({ name: "MissingProductsDrawer" });

const { t } = useI18n();
const props = defineProps<{ modelValue: boolean; source: SheetSourceVO | null }>();
const emit = defineEmits<{ (event: "update:modelValue", value: boolean): void }>();

const loading = ref(false);
const rows = ref<SheetMissingVO[]>([]);
const total = ref(0);
const query = reactive({ page: 1, size: 20 });

function asMissing(row: object): SheetMissingVO {
  return row as SheetMissingVO;
}

const columns = computed<DataColumn[]>(() => [
  { key: "productCode", align: "left" as const, label: t("product.code"), width: 130 },
  {
    key: "productName",
    align: "left" as const,
    label: t("product.name"),
    prop: "productName",
    minWidth: 160
  },
  {
    key: "shopName",
    align: "left" as const,
    label: t("product.shop"),
    prop: "shopName",
    minWidth: 120
  },
  { key: "weidianItemId", align: "left" as const, label: t("product.weidianItemId"), width: 150 },
  { key: "status", label: t("common.status"), width: 90 },
  { key: "sheetRow", label: t("sheetSource.row"), prop: "sheetRow", width: 80 },
  { key: "lastSeen", label: t("sheetSource.lastSeen"), width: 170 }
]);

async function load() {
  if (!props.source) return;
  loading.value = true;
  try {
    const page = await listMissingProducts(props.source.id, query.page, query.size);
    rows.value = page.items;
    total.value = Number(page.total);
  } catch (error) {
    notifyError(error);
  } finally {
    loading.value = false;
  }
}

function onPage(page: number) {
  query.page = page;
  void load();
}

function onSize(size: number) {
  query.size = size;
}

watch(
  () => props.modelValue,
  open => {
    if (!open) return;
    query.page = 1;
    rows.value = [];
    total.value = 0;
    void load();
  }
);
</script>

<template>
  <el-drawer
    :model-value="modelValue"
    :title="t('sheetSource.missingTitle')"
    size="900px"
    @update:model-value="emit('update:modelValue', $event)"
  >
    <el-alert :title="t('sheetSource.missingHint')" type="info" :closable="false" show-icon />
    <DataTable
      plain
      table-id="sheet-missing"
      row-key="productId"
      :rows="rows"
      :columns="columns"
      :loading="loading"
      :total="total"
      :page="query.page"
      :size="query.size"
      @update:page="onPage"
      @update:size="onSize"
      @refresh="load"
    >
      <template #cell-productCode="{ row }">
        <CopyText :value="asMissing(row).productCode" />
      </template>
      <template #cell-weidianItemId="{ row }">
        <CopyText :value="asMissing(row).weidianItemId" />
      </template>
      <template #cell-status="{ row }">
        <el-tag :type="asMissing(row).productStatus === 1 ? 'success' : 'info'">
          {{ asMissing(row).productStatus === 1 ? t("product.onSale") : t("product.offShelf") }}
        </el-tag>
      </template>
      <template #cell-lastSeen="{ row }">{{ formatInstant(asMissing(row).lastSeenTime) }}</template>
    </DataTable>
  </el-drawer>
</template>
