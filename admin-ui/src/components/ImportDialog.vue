<script setup lang="ts">
import { ElMessage } from "element-plus";
import { computed, onBeforeUnmount, ref, watch } from "vue";
import { downloadTaskErrorFile, getTask, isFinished } from "@/api/task/task";
import type { TaskType, TaskVO } from "@/api/types";
import type { DownloadedFile } from "@/utils/http";
import { saveBlob } from "@/utils/download";
import { ApiError } from "@/utils/http/error";
import { notifyError } from "@/utils/notify";

defineOptions({ name: "ImportDialog" });

const POLL_INTERVAL_MS = 1000;
const POLL_MAX_FAILURES = 3;
const IMPORT_MAX_BYTES = 20 * 1024 * 1024;

const props = defineProps<{
  modelValue: boolean;
  title: string;
  /** Shown in the first step, e.g. "单个文件不超过 20MB、20000 行". */
  limitHint: string;
  templateFileName: string;
  taskType: TaskType;
  downloadTemplate: () => Promise<DownloadedFile>;
  upload: (file: File) => Promise<{ taskId: string }>;
}>();

const emit = defineEmits<{
  (event: "update:modelValue", value: boolean): void;
  (event: "finished"): void;
}>();

const file = ref<File | null>(null);
const fileInput = ref<HTMLInputElement>();
const uploading = ref(false);
const task = ref<TaskVO | null>(null);
let timer: ReturnType<typeof setTimeout> | null = null;
let finishedAny = false;

const running = computed(() => task.value !== null && !isFinished(task.value.status));
const percentage = computed(() => {
  const t = task.value;
  if (!t || t.total <= 0) return 0;
  return Math.min(100, Math.floor((t.done / t.total) * 100));
});
const succeeded = computed(() => (task.value ? task.value.done - task.value.failed : 0));
const resultText = computed(() => {
  const t = task.value;
  if (!t) return "";
  switch (t.status) {
    case "WAITING":
      return "排队中，请稍候…";
    case "RUNNING":
      return `正在导入：已处理 ${t.done} / ${t.total} 行`;
    case "SUCCESS":
      return `导入完成：成功 ${t.total} 行`;
    case "PARTIAL":
      return `导入完成：成功 ${succeeded.value} 行，失败 ${t.failed} 行，请下载错误文件查看原因`;
    default:
      return t.hasErrorFile
        ? `导入失败：${t.failed} 行全部未导入，请下载错误文件查看原因`
        : "导入失败：文件无法处理或任务被中断，请检查文件后重新上传";
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
  }
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
      ElMessage.error("进度查询连续失败，已停止刷新；任务仍在后台执行，可关闭后稍后在列表查看");
      return;
    }
    timer = setTimeout(() => void poll(id, nextFailures), POLL_INTERVAL_MS);
  }
}

function onFileChange(event: Event) {
  file.value = (event.target as HTMLInputElement).files?.[0] ?? null;
}

function checkFile(selected: File): string | null {
  if (!selected.name.toLowerCase().endsWith(".xlsx")) return "请上传 .xlsx 格式的 Excel 文件";
  if (selected.size > IMPORT_MAX_BYTES) return "文件超过 20MB，请拆分后再上传";
  return null;
}

async function upload() {
  if (!file.value) {
    ElMessage.warning("请先选择文件");
    return;
  }
  const problem = checkFile(file.value);
  if (problem) {
    ElMessage.error(problem);
    return;
  }
  uploading.value = true;
  try {
    const { taskId } = await props.upload(file.value);
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

async function downloadTemplate() {
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
        下载模板并按列填写（{{ limitHint }}）。
        <el-button link type="primary" @click="downloadTemplate">下载导入模板</el-button>
      </li>
      <li>选择填好的 .xlsx 文件并上传，导入在后台进行。</li>
    </ol>

    <div v-if="!task" class="picker">
      <input
        ref="fileInput"
        type="file"
        accept=".xlsx,application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
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
        下载错误文件
      </el-button>
    </div>

    <template #footer>
      <el-button @click="close">{{ running ? "后台继续，关闭窗口" : "关闭" }}</el-button>
      <el-button v-if="task && !running" @click="again">继续导入</el-button>
      <el-button
        v-if="!task"
        type="primary"
        :loading="uploading"
        data-test="upload"
        @click="upload"
      >
        上传并导入
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
.result p {
  margin: 12px 0;
}
</style>
