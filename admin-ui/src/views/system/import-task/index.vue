<script setup lang="ts">
import { ElMessage } from "element-plus";
import { computed, nextTick, onMounted, reactive, ref, watch } from "vue";
import { useI18n } from "vue-i18n";
import { useRoute } from "vue-router";
import {
  downloadTaskErrorFile,
  getTask,
  listImportTasks,
  type ImportTaskVO
} from "@/api/task/task";
import type { TaskStatus, TaskType } from "@/api/types";
import DataTable, { type DataColumn } from "@/components/DataTable.vue";
import ImportDialog from "@/components/ImportDialog.vue";
import ListSearch, { type SearchChip } from "@/components/ListSearch.vue";
import { useLocaleStore } from "@/stores/locale";
import { useUserStore } from "@/stores/user";
import { saveBlob } from "@/utils/download";
import { formatInstant } from "@/utils/format";
import {
  importTemplateFileName,
  visibleImportTypes,
  type ImportTypeBinding
} from "@/utils/import-types";
import { notifyError } from "@/utils/notify";
import { readPageSize } from "@/utils/table-prefs";

defineOptions({ name: "SystemImportTaskPage" });

/**
 * Uploads (task 32 §2.3, contract 33 §3.5): every Excel / CSV import task with its progress
 * and, when rows failed, the error file download.
 */
const { t } = useI18n();
const route = useRoute();
const me = useUserStore();
const availableTypes = computed(() => visibleImportTypes(me.user));
const typePickerVisible = ref(false);
const pickedType = ref<ImportTypeBinding["taskType"] | "">("");
const importVisible = ref(false);
const activeBinding = ref<ImportTypeBinding | null>(null);

function openTypePicker(): void {
  importVisible.value = false;
  pickedType.value = "";
  typePickerVisible.value = true;
}

function confirmType(): void {
  const binding = availableTypes.value.find(item => item.taskType === pickedType.value);
  if (!binding) return;
  activeBinding.value = binding;
  typePickerVisible.value = false;
  importVisible.value = true;
}

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

const zhWidth = computed(() => useLocaleStore().locale === "zh-CN");
/** One width per language: the action labels differ in length (norm 10 §3). */
const px = (zh: number, en: number): number => (zhWidth.value ? zh : en);

const columns = computed<DataColumn[]>(() => [
  {
    key: "fileName",
    align: "left" as const,
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
  {
    key: "submitter",
    align: "left" as const,
    label: t("importTask.submitter"),
    prop: "operatorName",
    width: 120
  },
  { key: "submitTime", label: t("importTask.submitTime"), width: 170 },
  { key: "finishTime", label: t("importTask.finishTime"), width: 170 },
  // Error file: zh DOM 130px / en 166px + 24px headroom (task 59 R-11 measurements).
  { key: "actions", label: t("common.actions"), width: px(154, 190) }
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
    const file = await downloadTaskErrorFile(row.id);
    saveBlob(file.blob, file.fileName ?? `task-${row.id}-errors.xlsx`);
  } catch (error) {
    notifyError(error);
  }
}

/** The row the sync dialog navigated to (R-02); highlighted until the filters change. */
const locatedTaskId = ref("");

function rowLocated({ row }: { row: object }): string {
  return asTask(row).id === locatedTaskId.value ? "import-task-row--located" : "";
}

/**
 * R-02: /system/tasks?taskId=… from the sync dialog's failed-jobs tab. Fetch the task, filter the
 * list to its type and status, and highlight the row — with a notice when it is not on page 1.
 */
async function locateTask(wanted: string): Promise<void> {
  try {
    const task = await getTask(wanted);
    query.type = task.taskType;
    query.status = task.status;
    applied.value = { type: task.taskType, status: task.status };
    query.page = 1;
    await load();
    if (rows.value.some(row => asTask(row).id === wanted)) {
      locatedTaskId.value = wanted;
      await nextTick();
      document
        .querySelector(".import-task-row--located")
        ?.scrollIntoView({ block: "center", behavior: "smooth" });
    } else {
      ElMessage.info(t("importTask.notOnPage"));
    }
  } catch (error) {
    notifyError(error);
  }
}

watch(
  () => route.query.taskId,
  taskId => {
    const wanted = typeof taskId === "string" ? taskId.trim() : "";
    if (!wanted) return;
    void locateTask(wanted);
  },
  { immediate: true }
);

onMounted(() => {
  // The immediate route watch above already searched when a taskId came with the URL.
  if (!route.query.taskId) void load();
});
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
      <template v-if="availableTypes.length > 0" #actions>
        <el-button type="primary" data-test="upload-entry" @click="openTypePicker">
          {{ t("importTask.upload") }}
        </el-button>
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
      :row-class-name="rowLocated"
      @update:page="onPage"
      @update:size="onSize"
      @refresh="load"
      @clear-filters="reset"
    >
      <template #cell-fileName="{ row }">{{ asTask(row).fileName ?? "-" }}</template>
      <template #cell-type="{ row }">{{
        asTask(row).type ? t(`importTask.typeOption.${asTask(row).type}`) : "-"
      }}</template>
      <template #cell-status="{ row }">
        <el-tag :type="statusTag(asTask(row).status)" data-test="task-status">
          {{ asTask(row).status ? t(`importTask.statusOption.${asTask(row).status}`) : "-" }}
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

    <el-dialog
      v-model="typePickerVisible"
      :title="t('importTask.chooseType')"
      width="420px"
      :close-on-click-modal="false"
      data-test="upload-type-dialog"
    >
      <div class="upload-type-group">
        <el-radio-group v-model="pickedType" data-test="upload-type-group">
          <el-radio
            v-for="binding in availableTypes"
            :key="binding.taskType"
            :value="binding.taskType"
            :data-test="`upload-type-${binding.taskType}`"
          >
            {{ t(`importTask.typeOption.${binding.taskType}`) }}
          </el-radio>
        </el-radio-group>
      </div>
      <template #footer>
        <el-button @click="typePickerVisible = false">{{ t("common.cancel") }}</el-button>
        <el-button
          type="primary"
          :disabled="pickedType === ''"
          data-test="upload-type-confirm"
          @click="confirmType"
        >
          {{ t("common.confirm") }}
        </el-button>
      </template>
    </el-dialog>

    <ImportDialog
      v-if="activeBinding"
      v-model="importVisible"
      :title="t(activeBinding.titleKey)"
      :limit-hint="t(activeBinding.limitHintKey)"
      :template-file-name="importTemplateFileName(activeBinding, key => t(key))"
      :task-type="activeBinding.taskType"
      :accept-csv="activeBinding.acceptCsv"
      :require-data-cutoff="activeBinding.requireDataCutoff"
      :download-template="activeBinding.downloadTemplate"
      :upload="activeBinding.upload"
      @finished="load"
    />
  </div>
</template>

<style scoped>
.ok {
  color: var(--tcrm-color-success);
}
.fail {
  color: var(--tcrm-color-danger);
}
.upload-type-group {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: var(--tcrm-space-3);
}
/* R-02: the row the sync dialog navigated to. */
:deep(.import-task-row--located) td {
  background: var(--tcrm-color-warning-bg);
}
</style>
