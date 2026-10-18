<script setup lang="ts">
import { ElMessage } from "element-plus";
import { computed, ref, watch } from "vue";
import { useI18n } from "vue-i18n";
import {
  getCleanupConfig,
  listCleanupPreview,
  saveCleanupConfig,
  type CleanupPreviewVO
} from "@/api/kol/promotion";
import DataTable, { type DataColumn } from "@/components/DataTable.vue";
import { formatInstant } from "@/utils/format";
import { notifyError } from "@/utils/notify";

defineOptions({ name: "CleanupConfigDialog" });

const MIN_DAYS = 7;
const MAX_DAYS = 365;

const props = defineProps<{ modelValue: boolean }>();
const emit = defineEmits<{ (event: "update:modelValue", value: boolean): void }>();

const { t } = useI18n();

const loading = ref(false);
const saving = ref(false);
const enabled = ref(false);
const idleDays = ref<number>(30);
const errorText = ref("");
const previewing = ref(false);
const previewVisible = ref(false);
const previewRows = ref<CleanupPreviewVO[]>([]);
const previewTotal = ref(0);
const previewPage = ref(1);
const previewSize = 10;

const daysValid = computed(
  () => Number.isInteger(idleDays.value) && idleDays.value >= MIN_DAYS && idleDays.value <= MAX_DAYS
);

const previewColumns = computed<DataColumn[]>(() => [
  { key: "kolCode", label: t("kol.code"), width: 150, mono: true },
  { key: "name", label: t("common.name"), prop: "name", minWidth: 110 },
  { key: "bd", label: t("kol.bd"), minWidth: 110 },
  { key: "lastOrderAt", label: t("cleanup.lastOrderAt"), width: 170, mono: true },
  { key: "idleSince", label: t("cleanup.idleSince"), width: 170, mono: true }
]);

function asPreview(row: object): CleanupPreviewVO {
  return row as CleanupPreviewVO;
}

watch(
  () => props.modelValue,
  open => {
    if (!open) return;
    enabled.value = false;
    idleDays.value = 30;
    errorText.value = "";
    previewVisible.value = false;
    previewRows.value = [];
    previewTotal.value = 0;
    previewPage.value = 1;
    void load();
  }
);

async function load() {
  loading.value = true;
  try {
    const config = await getCleanupConfig();
    enabled.value = config.enabled;
    idleDays.value = config.idleDays;
  } catch (error) {
    notifyError(error);
  } finally {
    loading.value = false;
  }
}

async function loadPreview(page: number) {
  previewing.value = true;
  try {
    const result = await listCleanupPreview({ page, size: previewSize });
    previewRows.value = result.items;
    previewTotal.value = Number(result.total);
    previewPage.value = page;
    previewVisible.value = true;
  } catch (error) {
    notifyError(error);
  } finally {
    previewing.value = false;
  }
}

/** The preview reflects the saved configuration, so it is fetched unchanged. */
function preview() {
  void loadPreview(1);
}

async function save() {
  if (!daysValid.value) {
    errorText.value = t("validation.cleanupIdleDays");
    return;
  }
  errorText.value = "";
  saving.value = true;
  try {
    await saveCleanupConfig({ enabled: enabled.value, idleDays: idleDays.value });
    ElMessage.success(t("cleanup.saved"));
    emit("update:modelValue", false);
  } catch (error) {
    notifyError(error);
  } finally {
    saving.value = false;
  }
}

function onPreviewPage(page: number): void {
  void loadPreview(page);
}
</script>

<template>
  <el-dialog
    :model-value="modelValue"
    :title="t('cleanup.title')"
    width="min(760px, calc(100vw - 24px))"
    @update:model-value="emit('update:modelValue', $event)"
  >
    <div v-loading="loading">
      <el-alert class="cleanup-desc" type="info" :closable="false" :title="t('cleanup.desc')" />
      <el-alert
        v-if="errorText"
        class="cleanup-desc"
        type="error"
        :closable="false"
        :title="errorText"
        data-test="cleanup-error"
      />
      <el-form label-width="130px" @submit.prevent>
        <el-form-item :label="t('cleanup.enabled')">
          <div class="cleanup-enabled">
            <el-switch v-model="enabled" data-test="cleanup-enabled" />
            <span class="cleanup-hint">{{ t("cleanup.enabledHint") }}</span>
          </div>
        </el-form-item>
        <el-form-item :label="t('cleanup.idleDays')">
          <div class="cleanup-days">
            <el-input-number
              v-model="idleDays"
              data-test="cleanup-days"
              :min="MIN_DAYS"
              :max="MAX_DAYS"
              :step="1"
              step-strictly
            />
            <span>{{ t("cleanup.idleDaysUnit") }}</span>
          </div>
        </el-form-item>
      </el-form>

      <div class="preview-head">
        <el-button data-test="cleanup-preview" :loading="previewing" @click="preview">
          {{ t("cleanup.preview") }}
        </el-button>
      </div>
      <template v-if="previewVisible">
        <p class="preview-title">{{ t("cleanup.previewTitle") }}</p>
        <DataTable
          plain
          table-id="kol-cleanup-preview"
          :rows="previewRows"
          :columns="previewColumns"
          :total="previewTotal"
          :page="previewPage"
          :size="previewSize"
          @update:page="onPreviewPage"
        >
          <template #cell-bd="{ row }">
            {{ asPreview(row).bdUserName ?? t("common.unassigned") }}
          </template>
          <template #cell-lastOrderAt="{ row }">
            {{ formatInstant(asPreview(row).lastOrderAt) }}
          </template>
          <template #cell-idleSince="{ row }">
            {{ formatInstant(asPreview(row).idleSince) }}
          </template>
        </DataTable>
      </template>
    </div>
    <template #footer>
      <el-button @click="emit('update:modelValue', false)">{{ t("common.cancel") }}</el-button>
      <el-button type="primary" data-test="cleanup-save" :loading="saving" @click="save">
        {{ t("common.save") }}
      </el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.cleanup-desc {
  margin-bottom: 12px;
}
.cleanup-enabled {
  display: flex;
  align-items: center;
  gap: 12px;
}
.cleanup-hint {
  color: var(--el-text-color-secondary);
}
.cleanup-days {
  display: flex;
  align-items: center;
  gap: 8px;
}
.preview-head {
  display: flex;
  justify-content: flex-end;
  margin-bottom: 8px;
}
.preview-title {
  margin: 0 0 8px;
  font-weight: 600;
}
</style>
