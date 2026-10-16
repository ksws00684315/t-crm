<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from "vue";
import { useI18n } from "vue-i18n";
import { readSheetSource } from "@/api/product/sheet-source";
import { downloadTaskErrorFile, getTask, isFinished } from "@/api/task/task";
import type { SheetReadResultVO, SheetSourceVO, TaskVO } from "@/api/types";
import { saveBlob } from "@/utils/download";
import { notifyError } from "@/utils/notify";
import SkippedRows from "./SkippedRows.vue";

defineOptions({ name: "SheetReadDialog" });

const { t } = useI18n();
const POLL_INTERVAL_MS = 1000;

const props = defineProps<{ modelValue: boolean; source: SheetSourceVO | null }>();
const emit = defineEmits<{
  (event: "update:modelValue", value: boolean): void;
  (event: "finished"): void;
}>();

const reading = ref(false);
const result = ref<SheetReadResultVO | null>(null);
const task = ref<TaskVO | null>(null);
let timer: ReturnType<typeof setTimeout> | null = null;

const percentage = computed(() => {
  const current = task.value;
  if (!current || current.total <= 0) return 0;
  return Math.min(100, Math.floor((current.done / current.total) * 100));
});

const taskText = computed(() => {
  const current = task.value;
  if (!current) return "";
  switch (current.status) {
    case "WAITING":
      return t("import.waiting");
    case "RUNNING":
      return t("import.running", { done: current.done, total: current.total });
    case "SUCCESS":
      return t("import.success", { total: current.total });
    case "PARTIAL":
      return t("import.partial", {
        succeeded: current.done - current.failed,
        failed: current.failed
      });
    default:
      return current.hasErrorFile
        ? t("import.failedRows", { failed: current.failed })
        : t("import.failedFile");
  }
});

function stop() {
  if (timer !== null) clearTimeout(timer);
  timer = null;
}

async function poll(id: string) {
  try {
    task.value = await getTask(id);
  } catch (error) {
    notifyError(error);
    return;
  }
  if (isFinished(task.value.status)) {
    emit("finished");
    return;
  }
  timer = setTimeout(() => void poll(id), POLL_INTERVAL_MS);
}

async function start() {
  if (!props.source) return;
  stop();
  result.value = null;
  task.value = null;
  reading.value = true;
  try {
    result.value = await readSheetSource(props.source.id);
    void poll(result.value.taskId);
  } catch (error) {
    notifyError(error);
    emit("finished");
  } finally {
    reading.value = false;
  }
}

async function downloadErrors() {
  if (!task.value) return;
  try {
    const file = await downloadTaskErrorFile(task.value.id);
    saveBlob(file.blob, file.fileName ?? `task-${task.value.id}-errors.xlsx`);
  } catch (error) {
    notifyError(error);
  }
}

watch(
  () => props.modelValue,
  open => {
    if (open) void start();
    else stop();
  }
);

onBeforeUnmount(stop);
</script>

<template>
  <el-dialog
    :model-value="modelValue"
    :title="t('sheetSource.readTitle', { name: source?.sourceName ?? '' })"
    width="560px"
    :close-on-click-modal="false"
    @update:model-value="emit('update:modelValue', $event)"
  >
    <div v-loading="reading" class="read-body">
      <template v-if="result">
        <p data-test="read-rows">{{ t("sheetSource.readStarted", { rows: result.rowCount }) }}</p>
        <el-progress :percentage="percentage" />
        <p data-test="read-task">{{ taskText }}</p>
        <el-button
          v-if="task?.hasErrorFile"
          link
          type="primary"
          data-test="read-errors"
          @click="downloadErrors"
        >
          {{ t("import.downloadErrors") }}
        </el-button>
        <SkippedRows :skipped="result.skipped" />
      </template>
    </div>
    <template #footer>
      <el-button @click="emit('update:modelValue', false)">{{ t("common.close") }}</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.read-body {
  min-height: 80px;
}
</style>
