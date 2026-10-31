<script setup lang="ts">
import { ElMessage } from "element-plus";
import { computed, onBeforeUnmount, ref, watch } from "vue";
import { useI18n } from "vue-i18n";
import { downloadTaskErrorFile, getTask, isFinished } from "@/api/task/task";
import type { TaskType, TaskVO } from "@/api/types";
import type { DownloadedFile } from "@/utils/http";
import { saveBlob } from "@/utils/download";
import { ApiError } from "@/utils/http/error";
import { notifyError } from "@/utils/notify";

defineOptions({ name: "ImportDialog" });

const { t } = useI18n();

const POLL_INTERVAL_MS = 1000;
const POLL_MAX_FAILURES = 3;
const IMPORT_MAX_BYTES = 20 * 1024 * 1024;

const props = withDefaults(
  defineProps<{
    modelValue: boolean;
    title: string;
    /** Shown in the first step, e.g. "One file, at most 20MB and 20,000 rows". */
    limitHint: string;
    templateFileName: string;
    taskType: TaskType;
    downloadTemplate: () => Promise<DownloadedFile>;
    upload: (file: File, dataCutoffTime?: string) => Promise<{ taskId: string }>;
    /** Order / refund imports also accept .csv (task 28 §3.6). */
    acceptCsv?: boolean;
    /** Requires the "data cutoff time" field (task 40 §3.5): when the file's data ends. */
    requireDataCutoff?: boolean;
  }>(),
  { acceptCsv: false, requireDataCutoff: false }
);

const emit = defineEmits<{
  (event: "update:modelValue", value: boolean): void;
  (event: "finished"): void;
}>();

const file = ref<File | null>(null);
const fileInput = ref<HTMLInputElement>();
/** When the uploaded file's data ends; defaults to "now" and may not be in the future. */
const dataCutoffTime = ref<Date | null>(null);
const uploading = ref(false);
const task = ref<TaskVO | null>(null);
let timer: ReturnType<typeof setTimeout> | null = null;
let finishedAny = false;

const running = computed(() => task.value !== null && !isFinished(task.value.status));
const percentage = computed(() => {
  const current = task.value;
  if (!current || current.total <= 0) return 0;
  return Math.min(100, Math.floor((current.done / current.total) * 100));
});
const succeeded = computed(() => (task.value ? task.value.done - task.value.failed : 0));
const resultText = computed(() => {
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
      return t("import.partial", { succeeded: succeeded.value, failed: current.failed });
    default:
      return current.hasErrorFile
        ? t("import.failedRows", { failed: current.failed })
        : t("import.failedFile");
  }
});

function stopPolling() {
  if (timer !== null) clearTimeout(timer);
  timer = null;
}

function reset() {
  stopPolling();
  file.value = null;
  task.value = null;
  if (fileInput.value) fileInput.value.value = "";
  dataCutoffTime.value = props.requireDataCutoff ? new Date() : null;
}

/** Days after today stay unpickable; a future time on today is caught at submit. */
function futureDateDisabled(date: Date): boolean {
  const today = new Date();
  today.setHours(23, 59, 59, 999);
  return date.getTime() > today.getTime();
}

function cutoffProblem(): string | null {
  if (!props.requireDataCutoff) return null;
  if (!dataCutoffTime.value) return t("import.cutoffRequired");
  if (dataCutoffTime.value.getTime() > Date.now()) return t("import.cutoffFuture");
  return null;
}

watch(
  () => props.modelValue,
  open => {
    if (open) {
      finishedAny = false;
      reset();
    } else {
      stopPolling();
      if (finishedAny) emit("finished");
    }
  },
  { immediate: true }
);

onBeforeUnmount(stopPolling);

async function poll(id: string, failures = 0) {
  timer = null;
  try {
    const next = await getTask(id);
    if (!props.modelValue) return;
    task.value = next;
    if (isFinished(next.status)) {
      finishedAny = true;
      return;
    }
    timer = setTimeout(() => void poll(id, 0), POLL_INTERVAL_MS);
  } catch (error) {
    if (!props.modelValue) return;
    if (error instanceof ApiError && error.status === 401) {
      notifyError(error);
      return;
    }
    const nextFailures = failures + 1;
    if (nextFailures >= POLL_MAX_FAILURES) {
      notifyError(error);
      ElMessage.error(t("import.pollFailed"));
      return;
    }
    timer = setTimeout(() => void poll(id, nextFailures), POLL_INTERVAL_MS);
  }
}

function onFileChange(event: Event) {
  file.value = (event.target as HTMLInputElement).files?.[0] ?? null;
}

function checkFile(selected: File): string | null {
  const name = selected.name.toLowerCase();
  const ok = props.acceptCsv
    ? name.endsWith(".xlsx") || name.endsWith(".csv")
    : name.endsWith(".xlsx");
  if (!ok) return props.acceptCsv ? t("import.xlsxOrCsv") : t("import.xlsxOnly");
  if (selected.size > IMPORT_MAX_BYTES) return t("import.tooLarge");
  return null;
}

async function submitUpload() {
  if (!file.value) {
    ElMessage.warning(t("import.chooseFile"));
    return;
  }
  const problem = checkFile(file.value) ?? cutoffProblem();
  if (problem) {
    ElMessage.error(problem);
    return;
  }
  uploading.value = true;
  try {
    const { taskId } = await props.upload(
      file.value,
      props.requireDataCutoff && dataCutoffTime.value
        ? dataCutoffTime.value.toISOString()
        : undefined
    );
    task.value = {
      id: taskId,
      taskType: props.taskType,
      status: "WAITING",
      total: 0,
      done: 0,
      failed: 0,
      message: null,
      hasErrorFile: false,
      createTime: new Date().toISOString(),
      startedAt: null,
      finishedAt: null
    };
    timer = setTimeout(() => void poll(taskId), POLL_INTERVAL_MS);
  } catch (error) {
    notifyError(error);
  } finally {
    uploading.value = false;
  }
}

async function onDownloadTemplate() {
  try {
    const result = await props.downloadTemplate();
    saveBlob(result.blob, result.fileName ?? props.templateFileName);
  } catch (error) {
    notifyError(error);
  }
}

async function downloadErrors() {
  if (!task.value) return;
  try {
    const result = await downloadTaskErrorFile(task.value.id);
    saveBlob(result.blob, result.fileName ?? `task-${task.value.id}-errors.xlsx`);
  } catch (error) {
    notifyError(error);
  }
}

function again() {
  reset();
}

function close() {
  emit("update:modelValue", false);
}
</script>

<template>
  <el-dialog
    :model-value="modelValue"
    :title="title"
    width="560px"
    :close-on-click-modal="false"
    @update:model-value="emit('update:modelValue', $event)"
  >
    <ol class="steps">
      <li>
        {{ t("import.stepTemplate", { limitHint: props.limitHint }) }}
        <el-button link type="primary" @click="onDownloadTemplate">
          {{ t("import.downloadTemplate") }}
        </el-button>
      </li>
      <li>{{ t(props.acceptCsv ? "import.stepUploadCsv" : "import.stepUpload") }}</li>
    </ol>

    <div v-if="!task" class="picker">
      <!-- el-date-picker renders a fragment root, so the test hook lives on this wrapper. -->
      <div v-if="props.requireDataCutoff" data-test="data-cutoff">
        <el-date-picker
          v-model="dataCutoffTime"
          type="datetime"
          :placeholder="t('import.cutoffPlaceholder')"
          :disabled-date="futureDateDisabled"
          style="width: 100%; margin-bottom: 12px"
        />
      </div>
      <div v-if="props.requireDataCutoff" class="cutoff-hint" data-test="data-cutoff-hint">
        {{ t("import.cutoffHint") }}
      </div>
      <input
        ref="fileInput"
        type="file"
        :accept="
          props.acceptCsv
            ? '.xlsx,.csv,application/vnd.openxmlformats-officedocument.spreadsheetml.sheet,text/csv'
            : '.xlsx,application/vnd.openxmlformats-officedocument.spreadsheetml.sheet'
        "
        data-test="file"
        @change="onFileChange"
      />
    </div>

    <div v-else class="result" data-test="result">
      <el-progress
        :percentage="percentage"
        :status="
          task.status === 'SUCCESS'
            ? 'success'
            : task.status === 'FAILED'
              ? 'exception'
              : task.status === 'PARTIAL'
                ? 'warning'
                : undefined
        "
      />
      <p>{{ resultText }}</p>
      <el-button
        v-if="task.hasErrorFile && !running"
        type="warning"
        data-test="error-file"
        @click="downloadErrors"
      >
        {{ t("import.downloadErrors") }}
      </el-button>
    </div>

    <template #footer>
      <el-button @click="close">
        {{ running ? t("import.closeRunning") : t("import.close") }}
      </el-button>
      <el-button v-if="task && !running" @click="again">{{ t("import.again") }}</el-button>
      <el-button
        v-if="!task"
        type="primary"
        :loading="uploading"
        data-test="upload"
        @click="submitUpload"
      >
        {{ t("import.submit") }}
      </el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.steps {
  margin: 0 0 16px;
  padding-left: 20px;
  line-height: 2;
}
.picker {
  padding: 12px 0;
}
.cutoff-hint {
  margin-bottom: 8px;
  color: var(--tcrm-color-text-secondary);
  font-size: var(--tcrm-font-size-help);
  line-height: var(--tcrm-line-height-help);
}
.result p {
  margin: 12px 0;
}
.picker input[type="file"] {
  width: 100%;
  min-width: 0;
  color: var(--tcrm-color-text-secondary);
  font: inherit;
}
.picker input[type="file"]::file-selector-button {
  margin-inline-end: var(--tcrm-space-2);
  padding: var(--tcrm-space-1) var(--tcrm-space-3);
  min-height: var(--tcrm-control-height);
  border: 1px solid var(--tcrm-color-input-border);
  border-radius: var(--tcrm-radius-control);
  background: var(--tcrm-color-card);
  color: var(--tcrm-color-text);
  font: inherit;
  cursor: pointer;
}
.picker input[type="file"]::file-selector-button:hover {
  background: var(--tcrm-color-muted);
}
@media (max-width: 767px) {
  .picker input[type="file"]::file-selector-button {
    min-height: var(--tcrm-control-height-mobile);
  }
}
</style>
