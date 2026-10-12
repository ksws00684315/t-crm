<script setup lang="ts">
import { computed, onMounted, reactive, ref } from "vue";
import { useI18n } from "vue-i18n";
import { listOperLogs, type OperLogVO } from "@/api/system/oper-log";
import DataTable, { type DataColumn } from "@/components/DataTable.vue";
import ListSearch from "@/components/ListSearch.vue";
import { formatInstant } from "@/utils/format";
import { notifyError } from "@/utils/notify";
import { readPageSize } from "@/utils/table-prefs";

defineOptions({ name: "SystemOperLogPage" });

const { t } = useI18n();

const loading = ref(false);
const rows = ref<OperLogVO[]>([]);
const total = ref(0);
const paging = reactive({ page: 1, size: readPageSize("oper-log", 20) });

function asLog(row: object): OperLogVO {
  return row as OperLogVO;
}

const columns = computed<DataColumn[]>(() => [
  { key: "time", label: t("audit.time"), width: 170, mono: true },
  { key: "username", label: t("audit.operator"), prop: "username", width: 130 },
  { key: "module", label: t("audit.module"), prop: "module", width: 100 },
  { key: "action", label: t("audit.action"), prop: "action", minWidth: 140 },
  { key: "result", label: t("audit.result"), prop: "result", width: 100 },
  { key: "requestUri", label: t("audit.uri"), prop: "requestUri", minWidth: 200, tooltip: true },
  { key: "ip", label: t("audit.ip"), prop: "ip", width: 140, mono: true },
  { key: "costMs", label: t("audit.cost"), prop: "costMs", width: 100 }
]);

async function load() {
  loading.value = true;
  try {
    const page = await listOperLogs(paging.page, paging.size);
    rows.value = page.items;
    total.value = Number(page.total);
  } catch (error) {
    notifyError(error);
  } finally {
    loading.value = false;
  }
}

function onPage(page: number): void {
  paging.page = page;
  void load();
}

function onSize(size: number): void {
  paging.size = size;
}

onMounted(load);
</script>

<template>
  <el-card shadow="never">
    <ListSearch @search="load" @reset="load" />
    <DataTable
      table-id="oper-log"
      :rows="rows"
      :columns="columns"
      :loading="loading"
      :total="total"
      :page="paging.page"
      :size="paging.size"
      @update:page="onPage"
      @update:size="onSize"
      @refresh="load"
    >
      <template #cell-time="{ row }">{{ formatInstant(asLog(row).operTime) }}</template>
    </DataTable>
  </el-card>
</template>
