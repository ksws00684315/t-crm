<script setup lang="ts">
import { computed, onMounted, reactive, ref } from "vue";
import { useI18n } from "vue-i18n";
import { downloadTaskErrorFile, listImportTasks, type ImportTaskVO } from "@/api/task/task";
import type { TaskStatus, TaskType } from "@/api/types";
import DataTable, { type DataColumn } from "@/components/DataTable.vue";
import ListSearch, { type SearchChip } from "@/components/ListSearch.vue";
import { formatInstant } from "@/utils/format";
import { notifyError } from "@/utils/notify";
import { readPageSize } from "@/utils/table-prefs";

defineOptions({ name: "SystemImportTaskPage" });

/**
 * Uploads (task 32 §2.3, contract 33 §3.5): every Excel / CSV import task with its progress
 * and, when rows failed, the error file download.
 */
const { t } = useI18n();

const TYPES: TaskType[] = [
  "PRODUCT_IMPORT",
  "MAPPING_IMPORT",
  "ORDER_IMPORT",
  "REFUND_IMPORT",
  "KOL_IMPORT"
];
const STATUSES: TaskStatus[] = ["WAITING", "RUNNING", "SUCCESS", "PARTIAL", "FAILED"];

const loading = ref(false);
const rows = ref<ImportTaskVO[]>([]);
const total = ref(0);
const query = reactive({
  page: 1,
  size: readPageSize("import-tasks", 20),
  type: undefined as TaskType | undefined,
  status: undefined as TaskStatus | undefined
});
const applied = ref({
  type: undefined as TaskType | undefined,
  status: undefined as TaskStatus | undefined
});

function asTask(row: object): ImportTaskVO {
  return row as ImportTaskVO;
}

const chips = computed<SearchChip[]>(() => {
  const list: SearchChip[] = [];
  if (applied.value.type)
    list.push({
      key: "type",
      label: t("importTask.type"),
      text: t(`importTask.typeOption.${applied.value.type}`)
    });
  if (applied.value.status)
    list.push({
      key: "status",
      label: t("importTask.status"),
      text: t(`importTask.statusOption.${applied.value.status}`)
    });
  return list;
});

const columns = computed<DataColumn[]>(() => [
  {
    key: "fileName",
    label: t("importTask.fileName"),
    prop: "fileName",
    minWidth: 200,
    tooltip: true
  },
  { key: "type", label: t("importTask.type"), width: 130 },
  { key: "status", label: t("importTask.status"), width: 100 },
  { key: "totalRows", label: t("importTask.totalRows"), width: 90 },
  { key: "successRows", label: t("importTask.successRows"), width: 90 },
  { key: "failedRows", label: t("importTask.failedRows"), width: 90 },
  { key: "submitter", label: t("importTask.submitter"), prop: "operatorName", width: 120 },
  { key: "submitTime", label: t("importTask.submitTime"), width: 170 },
  { key: "finishTime", label: t("importTask.finishTime"), width: 170 },
  { key: "actions", label: t("common.actions"), width: 130 }
]);

async function load() {
  loading.value = true;
  try {
    const page = await listImportTasks(query.page, query.size, {
      type: applied.value.type,
      status: applied.value.status
    });
    rows.value = page.items;
    total.value = Number(page.total);
  } catch (error) {
    notifyError(error);
  } finally {
    loading.value = false;
  }
}

function search() {
  applied.value = { type: query.type, status: query.status };
  query.page = 1;
  void load();
}

function reset() {
  query.type = undefined;
  query.status = undefined;
  search();
}

function removeChip(key: string): void {
  if (key === "type") query.type = undefined;
  if (key === "status") query.status = undefined;
  search();
}

function onPage(page: number): void {
  query.page = page;
  void load();
}

function onSize(size: number): void {
  query.size = size;
}

function statusTag(status: TaskStatus): "info" | "primary" | "success" | "warning" | "danger" {
  if (status === "SUCCESS") return "success";
  if (status === "PARTIAL") return "warning";
  if (status === "FAILED") return "danger";
  if (status === "RUNNING") return "primary";
  return "info";
}

async function downloadError(row: ImportTaskVO) {
  try {
    await downloadTaskErrorFile(row.id);
  } catch (error) {
    notifyError(error);
  }
}

onMounted(load);
</script>

<template>
  <div class="list-page">
    <ListSearch :chips="chips" @search="search" @reset="reset" @remove="removeChip">
      <template #primary>
        <el-form-item :label="t('importTask.type')">
          <el-select
            v-model="query.type"
            clearable
            :placeholder="t('common.all')"
            data-test="filter-type"
            style="width: 160px"
          >
            <el-option
              v-for="type in TYPES"
              :key="type"
              :label="t(`importTask.typeOption.${type}`)"
              :value="type"
            />
          </el-select>
        </el-form-item>
        <el-form-item :label="t('importTask.status')">
          <el-select
            v-model="query.status"
            clearable
            :placeholder="t('common.all')"
            data-test="filter-status"
            style="width: 140px"
          >
            <el-option
              v-for="status in STATUSES"
              :key="status"
              :label="t(`importTask.statusOption.${status}`)"
              :value="status"
            />
          </el-select>
        </el-form-item>
      </template>
    </ListSearch>

    <DataTable
      table-id="import-tasks"
      :rows="rows"
      :columns="columns"
      :loading="loading"
      :total="total"
      :page="query.page"
      :size="query.size"
      :filter-count="chips.length"
      @update:page="onPage"
      @update:size="onSize"
      @refresh="load"
      @clear-filters="reset"
    >
      <template #cell-fileName="{ row }">{{ asTask(row).fileName ?? "-" }}</template>
      <template #cell-type="{ row }">{{ t(`importTask.typeOption.${asTask(row).type}`) }}</template>
      <template #cell-status="{ row }">
        <el-tag :type="statusTag(asTask(row).status)" data-test="task-status">
          {{ t(`importTask.statusOption.${asTask(row).status}`) }}
        </el-tag>
      </template>
      <template #cell-totalRows="{ row }">{{ asTask(row).totalRows }}</template>
      <template #cell-successRows="{ row }"
        ><span class="ok">{{ asTask(row).successRows }}</span></template
      >
      <template #cell-failedRows="{ row }"
        ><span class="fail">{{ asTask(row).failedRows }}</span></template
      >
      <template #cell-submitter="{ row }">{{ asTask(row).operatorName ?? "-" }}</template>
      <template #cell-submitTime="{ row }">{{ formatInstant(asTask(row).createTime) }}</template>
      <template #cell-finishTime="{ row }">
        {{ asTask(row).finishTime ? formatInstant(asTask(row).finishTime) : "-" }}
      </template>
      <template #actions="{ row }">
        <el-button
          v-if="asTask(row).hasErrorFile"
          link
          type="primary"
          data-test="download-error"
          @click="downloadError(asTask(row))"
          >{{ t("importTask.errorFile") }}</el-button
        >
      </template>
    </DataTable>
  </div>
</template>

<style scoped>
.ok {
  color: var(--tcrm-color-success);
}
.fail {
  color: var(--tcrm-color-danger);
}
</style>
