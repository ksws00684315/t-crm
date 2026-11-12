<script setup lang="ts">
import { ElMessage } from "element-plus";
import { ref } from "vue";
import { useI18n } from "vue-i18n";
import { messageForErrorCode } from "@/api/error-codes";
import {
  downloadLibraryWorkbookTemplate,
  importLibraryWorkbook,
  type LibraryWorkbookImportResult
} from "@/api/kol-library/kol";
import { saveBlob } from "@/utils/download";
import { notifyError } from "@/utils/notify";

const emit = defineEmits<{ imported: []; viewList: [] }>();
const { t } = useI18n();
const visible = ref(false);
const downloading = ref(false);
const importing = ref(false);
const file = ref<File | null>(null);
const result = ref<LibraryWorkbookImportResult | null>(null);

async function download(): Promise<void> {
  if (downloading.value) return;
  downloading.value = true;
  try {
    const template = await downloadLibraryWorkbookTemplate();
    saveBlob(template.blob, template.fileName ?? "creator-library-template.xlsx");
  } catch (error) {
    notifyError(error);
  } finally {
    downloading.value = false;
  }
}

function chooseFile(event: Event): void {
  const input = event.target as HTMLInputElement;
  file.value = null;
  result.value = null;
  const selected = input.files?.[0];
  if (!selected) return;
  if (!/\.xlsx$/i.test(selected.name) || selected.size > 2 * 1024 * 1024) {
    const code = /\.xlsx$/i.test(selected.name)
      ? "KOL_LIBRARY_IMPORT_FILE_TOO_LARGE"
      : "TASK_FILE_INVALID";
    ElMessage.error(messageForErrorCode(code));
    input.value = "";
    return;
  }
  file.value = selected;
}

async function submit(): Promise<void> {
  if (!file.value || importing.value || result.value) return;
  importing.value = true;
  try {
    result.value = await importLibraryWorkbook(file.value);
    if (result.value.created) emit("imported");
  } catch (error) {
    notifyError(error);
  } finally {
    importing.value = false;
  }
}

function reset(): void {
  file.value = null;
  result.value = null;
}
</script>

<template>
  <div class="workbook-actions">
    <el-button :loading="downloading" data-test="library-download-template" @click="download">
      {{ t("library.workbook.download") }}
    </el-button>
    <el-button type="primary" plain data-test="library-upload-workbook" @click="visible = true">
      {{ t("library.workbook.upload") }}
    </el-button>
  </div>
  <el-dialog
    v-model="visible"
    :title="t('library.workbook.title')"
    width="min(560px, calc(100vw - 32px))"
    append-to-body
    destroy-on-close
    :close-on-click-modal="false"
    :close-on-press-escape="!importing"
    :show-close="!importing"
    @closed="reset"
  >
    <div class="workbook-dialog">
      <p>{{ t("library.workbook.guide") }}</p>
      <p>{{ t("library.workbook.rules") }}</p>
      <label class="workbook-file">
        <span>{{ t("library.workbook.file") }}</span>
        <input
          type="file"
          accept=".xlsx,application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
          :disabled="importing"
          data-test="library-workbook-file"
          @change="chooseFile"
        />
      </label>
      <section v-if="result" class="workbook-result" aria-live="polite">
        <p data-test="library-workbook-result">
          {{
            t("library.workbook.result", {
              created: result.created,
              failed: result.errors.length
            })
          }}
        </p>
        <ul v-if="result.errors.length" class="workbook-errors">
          <li v-for="error in result.errors" :key="error.row">
            <strong>{{ t("library.workbook.row", { row: error.row }) }}</strong>
            {{
              error.column
                ? t("library.workbook.invalidCell", { column: error.column })
                : messageForErrorCode(error.errorCode)
            }}
          </li>
        </ul>
      </section>
    </div>
    <template #footer>
      <el-button :disabled="importing" @click="visible = false">{{ t("common.close") }}</el-button>
      <el-button v-if="result?.created" @click="emit('viewList')">
        {{ t("library.workbook.viewList") }}
      </el-button>
      <el-button
        v-if="!result"
        type="primary"
        :disabled="!file"
        :loading="importing"
        data-test="library-workbook-submit"
        @click="submit"
      >
        {{ t("library.workbook.submit") }}
      </el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.workbook-actions {
  display: flex;
  flex-wrap: wrap;
  gap: var(--tcrm-space-2);
}
.workbook-actions :deep(.el-button + .el-button) {
  margin-left: 0;
}
.workbook-dialog {
  display: grid;
  gap: var(--tcrm-space-2);
}
.workbook-dialog p {
  margin: 0;
}
.workbook-file {
  display: grid;
  gap: var(--tcrm-space-1);
  margin-block: var(--tcrm-space-2);
}
.workbook-file input {
  max-width: 100%;
}
.workbook-result {
  padding: var(--tcrm-space-3);
  border-radius: var(--tcrm-radius-control);
  background: var(--tcrm-color-page);
}
.workbook-result p {
  font-weight: 600;
}
.workbook-errors {
  max-height: 200px;
  margin: var(--tcrm-space-2) 0 0;
  padding-inline-start: var(--tcrm-space-5);
  overflow-y: auto;
}
</style>
