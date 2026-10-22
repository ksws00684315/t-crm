<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from "vue";
import { useI18n } from "vue-i18n";
import { useRoute, useRouter } from "vue-router";
import {
  listPrepays,
  exportPrepays,
  type PrepayRow,
  type PrepayStatus
} from "@/api/commission/prepay";
import CopyText from "@/components/CopyText.vue";
import DataTable, { type DataColumn } from "@/components/DataTable.vue";
import ListSearch, { type SearchChip } from "@/components/ListSearch.vue";
import RowActions, { type RowAction } from "@/components/RowActions.vue";
import { useUserStore } from "@/stores/user";
import { formatInstant } from "@/utils/format";
import { notifyError } from "@/utils/notify";
import { saveBlob } from "@/utils/download";
import { PREPAY_STATUSES, prepayAbilities } from "@/utils/prepay";
import { readPageSize } from "@/utils/table-prefs";
import DetailDrawer from "./DetailDrawer.vue";
import PrepayStatusTag from "./PrepayStatusTag.vue";

defineOptions({ name: "PrepayTasksPage" });
const { t, locale } = useI18n();
const route = useRoute();
const router = useRouter();
const me = useUserStore();
const abilities = computed(() => prepayAbilities(me.user));
const filters = reactive({ prepayNo: "", kolCode: "", status: "" as PrepayStatus | "" });
const applied = ref({ ...filters });
const page = ref(1);
const size = ref(readPageSize("prepay-tasks", 20));
const rows = ref<PrepayRow[]>([]);
const total = ref(0);
const loading = ref(false);
const exporting = ref(false);
const detailId = ref<string | null>(null);
const visible = ref(false);
const initialAction = ref("");
const columns = computed<DataColumn[]>(() => [
  { key: "prepayNo", label: t("prepay.number"), minWidth: 220 },
  { key: "kolCode", label: t("prepay.kolId"), prop: "kolCode", minWidth: 160 },
  { key: "kolName", label: t("prepay.kol"), prop: "kolName", minWidth: 160, tooltip: true },
  { key: "amountCny", label: t("prepay.amount"), prop: "amountCny", minWidth: 160, amount: true },
  { key: "applicantName", label: t("prepay.applicant"), prop: "applicantName", minWidth: 140 },
  { key: "currency", label: t("prepay.currency"), prop: "currency", width: 100 },
  { key: "status", label: t("common.status"), minWidth: 150 },
  { key: "createdAt", label: t("prepay.createdAt"), minWidth: 180 },
  { key: "actions", label: t("common.actions"), width: locale.value === "zh-CN" ? 130 : 210 }
]);
const chips = computed<SearchChip[]>(() =>
  Object.entries(applied.value)
    .filter(([, value]) => value)
    .map(([key, value]) => ({
      key,
      label: t(`prepay.${key === "prepayNo" ? "number" : key === "status" ? "statusLabel" : key}`),
      text: key === "status" ? t(`prepay.status.${value}`) : value
    }))
);
function params() {
  return {
    prepayNo: applied.value.prepayNo || undefined,
    kolCode: applied.value.kolCode || undefined,
    status: applied.value.status || undefined
  };
}
let sequence = 0;
async function load() {
  const request = ++sequence;
  loading.value = true;
  try {
    const result = await listPrepays({ ...params(), page: page.value, size: size.value });
    if (request === sequence) {
      rows.value = result.items;
      total.value = Number(result.total);
    }
  } catch (error) {
    if (request === sequence) {
      rows.value = [];
      total.value = 0;
      notifyError(error);
    }
  } finally {
    if (request === sequence) loading.value = false;
  }
}
function search() {
  page.value = 1;
  applied.value = {
    prepayNo: filters.prepayNo.trim(),
    kolCode: filters.kolCode.trim(),
    status: filters.status
  };
  void load();
}
function reset() {
  Object.assign(filters, { prepayNo: "", kolCode: "", status: "" });
  search();
}
function removeChip(key: string) {
  if (key === "prepayNo" || key === "kolCode" || key === "status") filters[key] = "";
  search();
}
function open(row: PrepayRow, action = "") {
  detailId.value = row.id;
  initialAction.value = action;
  visible.value = true;
}
function asRow(row: object) {
  return row as PrepayRow;
}
function actions(row: PrepayRow): RowAction[] {
  // Element Plus invokes column slots with an empty row while registering columns.
  if (!row.id) return [];
  const mode = processMode(row);
  return [
    {
      key: "entry",
      label: processLabel(row) || t("prepay.detail"),
      testId: mode ? "prepay-row-process" : "prepay-row-detail",
      onClick: () => open(row, mode)
    }
  ];
}
function processMode(row: PrepayRow) {
  if (row.status === "WAITING_DETAILS" && abilities.value.apply && row.applicantId === me.user?.id)
    return "complete";
  if (row.status === "PENDING_OPERATIONS_REVIEW" && abilities.value.operationsReview)
    return "approve";
  if (row.status === "PENDING_FINANCE_REVIEW" && abilities.value.financeReview) return "approve";
  if (row.status === "APPROVED" && abilities.value.pay) return "pay";
  return "";
}
function processLabel(row: PrepayRow) {
  const mode = processMode(row);
  return mode === "complete"
    ? t("prepay.completeApplication")
    : mode === "approve"
      ? t("prepay.reviewAction")
      : mode === "pay"
        ? t("prepay.registerPayment")
        : "";
}
async function exportRows() {
  if (!abilities.value.export || exporting.value) return;
  exporting.value = true;
  try {
    const file = await exportPrepays(params());
    saveBlob(file.blob, file.fileName ?? "prepays.csv");
  } catch (error) {
    notifyError(error);
  } finally {
    exporting.value = false;
  }
}
function close(open: boolean) {
  visible.value = open;
  if (!open) {
    detailId.value = null;
    const query = { ...route.query };
    delete query.id;
    void router.replace({ query });
  }
}
watch(
  () => route.query.id,
  id => {
    if (typeof id === "string" && id && abilities.value.view) {
      detailId.value = id;
      initialAction.value = "";
      visible.value = true;
    }
  },
  { immediate: true }
);
onMounted(load);
</script>
<template>
  <div class="list-page" data-test="prepay-tasks">
    <ListSearch :chips="chips" @search="search" @reset="reset" @remove="removeChip">
      <template #primary>
        <el-form-item :label="t('prepay.number')"
          ><el-input v-model="filters.prepayNo" clearable data-test="prepay-filter-number"
        /></el-form-item>
        <el-form-item :label="t('prepay.kolCode')"
          ><el-input v-model="filters.kolCode" clearable data-test="prepay-filter-kol"
        /></el-form-item>
        <el-form-item :label="t('common.status')"
          ><el-select
            v-model="filters.status"
            clearable
            data-test="prepay-filter-status"
            style="width: 180px"
            ><el-option
              v-for="status in PREPAY_STATUSES"
              :key="status"
              :value="status"
              :label="t(`prepay.status.${status}`)" /></el-select
        ></el-form-item>
      </template>
    </ListSearch>
    <DataTable
      table-id="prepay-tasks"
      :rows="rows"
      :columns="columns"
      :total="total"
      :page="page"
      :size="size"
      :loading="loading"
      :filter-count="chips.length"
      :exportable="abilities.export"
      :exporting="exporting"
      @refresh="load"
      @clear-filters="reset"
      @export="exportRows"
      @update:page="
        page = $event;
        load();
      "
      @update:size="
        size = $event;
        page = 1;
        load();
      "
    >
      <template #cell-prepayNo="{ row }"><CopyText :value="asRow(row).prepayNo" /></template>
      <template #cell-applicantName="{ row }"
        >{{ asRow(row).applicantName || t("prepay.historicalPerson") }} ·
        {{ t("prepay.actorRole.BUSINESS") }}</template
      >
      <template #cell-status="{ row }"
        ><PrepayStatusTag v-if="asRow(row).status" :status="asRow(row).status"
      /></template>
      <template #cell-createdAt="{ row }">{{ formatInstant(asRow(row).createdAt) }}</template>
      <template #actions="{ row }"><RowActions :actions="actions(asRow(row))" /></template>
    </DataTable>
    <DetailDrawer
      :model-value="visible"
      :id="detailId"
      :initial-action="initialAction"
      @update:model-value="close"
      @saved="load"
    />
  </div>
</template>
