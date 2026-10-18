<script setup lang="ts">
import { ElMessage } from "element-plus";
import type { FormInstance, FormRules } from "element-plus";
import { computed, reactive, ref, watch } from "vue";
import { useI18n } from "vue-i18n";
import { listLinkTemplates } from "@/api/link/template";
import type { PromotionRecordVO } from "@/api/kol/promotion";
import {
  getPromotionConfig,
  listPromotionRecords,
  savePromotionConfig,
  updatePromotionStatus
} from "@/api/kol/promotion";
import type { DisplayStatus, KolVO, LinkTemplateVO } from "@/api/types";
import DataTable, { type DataColumn } from "@/components/DataTable.vue";
import { formatInstant } from "@/utils/format";
import { ApiError } from "@/utils/http/error";
import { validateInviteCode, validateLandingUrl, validateSheetUrl } from "@/utils/kol-validation";
import { notifyError } from "@/utils/notify";
import {
  incompletePromotionFields,
  missingPromotionFields,
  promotionFieldDiffs,
  promotionStatusLabelKey
} from "@/utils/promotion";
import { percentToRate, rateToPercent, validatePercent } from "@/utils/rate";
import { rule } from "@/utils/validation";

defineOptions({ name: "PromotionConfigDrawer" });

const RECORD_FIELD_KEYS = [
  "defaultRate",
  "platformTemplateIds",
  "landingUrl",
  "inviteCode",
  "sheetUrl",
  "status"
];

const props = defineProps<{
  modelValue: boolean;
  kol: KolVO | null;
}>();
const emit = defineEmits<{
  (event: "update:modelValue", value: boolean): void;
  (event: "saved"): void;
}>();

const { t } = useI18n();

const loading = ref(false);
const saving = ref(false);
const starting = ref(false);
const templates = ref<LinkTemplateVO[]>([]);
const records = ref<PromotionRecordVO[]>([]);
const recordsTotal = ref(0);
const recordsPage = ref(1);
const recordsSize = 10;
const formRef = ref<FormInstance>();
const incompleteAlert = ref("");

const form = reactive({
  percent: "",
  platformTemplateIds: [] as string[],
  landingUrl: "",
  inviteCode: "",
  sheetUrl: "",
  note: ""
});

const rules: FormRules = {
  percent: [rule(validatePercent)],
  landingUrl: [rule(validateLandingUrl)],
  inviteCode: [rule(validateInviteCode)],
  sheetUrl: [rule(validateSheetUrl)]
};

const title = computed(() =>
  props.kol
    ? t("promotion.configTitleWithName", { code: props.kol.kolCode, name: props.kol.name })
    : t("promotion.configTitle")
);

/** Enabled templates of this company are the multi-select options (ADR-023 §3). */
const templateOptions = computed(() =>
  templates.value
    .filter(item => item.status === 1)
    .map(item => ({ id: item.id, label: item.templateName }))
);

function templateName(id: string): string {
  return templates.value.find(item => item.id === id)?.templateName ?? id;
}

function fieldLabel(key: string): string {
  return RECORD_FIELD_KEYS.includes(key) ? t(`promotion.field.${key}`) : key;
}

function missingText(keys: string[]): string {
  return keys.map(fieldLabel).join("、");
}

const recordColumns = computed<DataColumn[]>(() => [
  { key: "time", label: t("promotion.recordTime"), width: 170, mono: true },
  { key: "operator", label: t("promotion.recordOperator"), width: 120 },
  { key: "type", label: t("promotion.recordType"), width: 80 },
  { key: "changes", label: t("promotion.recordChanges"), minWidth: 260 },
  { key: "remark", label: t("promotion.recordRemark"), minWidth: 120, tooltip: true }
]);

function asRecord(row: object): PromotionRecordVO {
  return row as PromotionRecordVO;
}

function typeText(record: PromotionRecordVO): string {
  return t(`promotion.recordTypeOption.${record.changeType}`);
}

/** One "label: from → to" line; a status value renders like the status tags do. */
function changeLine(diff: { key: string; from: string; to: string }): string {
  const show = (value: string) =>
    diff.key === "status" && value !== "-"
      ? t(promotionStatusLabelKey(value as DisplayStatus))
      : value;
  return t("promotion.changeLine", {
    label: fieldLabel(diff.key),
    from: show(diff.from),
    to: show(diff.to)
  });
}

watch(
  () => props.modelValue,
  open => {
    if (!open || !props.kol) return;
    incompleteAlert.value = "";
    formRef.value?.clearValidate();
    void loadAll();
  }
);

async function loadAll() {
  const id = props.kol?.id;
  if (!id) return;
  loading.value = true;
  try {
    const [config, templateList, recordPage] = await Promise.all([
      getPromotionConfig(id),
      listLinkTemplates().catch(() => [] as LinkTemplateVO[]),
      listPromotionRecords(id, { page: 1, size: recordsSize })
    ]);
    templates.value = templateList;
    form.percent = rateToPercent(config.defaultRate);
    form.platformTemplateIds = [...(config.platformTemplateIds ?? [])];
    form.landingUrl = config.landingUrl ?? "";
    form.inviteCode = config.inviteCode ?? "";
    form.sheetUrl = config.sheetUrl ?? "";
    form.note = "";
    records.value = recordPage.items;
    recordsTotal.value = Number(recordPage.total);
    recordsPage.value = 1;
  } catch (error) {
    notifyError(error);
  } finally {
    loading.value = false;
  }
}

async function loadRecords(page: number) {
  const id = props.kol?.id;
  if (!id) return;
  try {
    const result = await listPromotionRecords(id, { page, size: recordsSize });
    records.value = result.items;
    recordsTotal.value = Number(result.total);
    recordsPage.value = page;
  } catch (error) {
    notifyError(error);
  }
}

function requestBody() {
  return {
    defaultRate: percentToRate(form.percent),
    platformTemplateIds: [...form.platformTemplateIds],
    landingUrl: form.landingUrl.trim() || null,
    inviteCode: form.inviteCode.trim() || null,
    sheetUrl: form.sheetUrl.trim() || null,
    remark: form.note.trim() || null
  };
}

async function validateForm(): Promise<boolean> {
  return formRef.value ? await formRef.value.validate().catch(() => false) : false;
}

/** Saves the configuration; resolves false when the request failed. */
async function persistConfig(): Promise<boolean> {
  const id = props.kol?.id;
  if (!id) return false;
  await savePromotionConfig(id, requestBody());
  return true;
}

async function save() {
  incompleteAlert.value = "";
  if (!(await validateForm())) return;
  saving.value = true;
  try {
    await persistConfig();
    ElMessage.success(t("promotion.saved"));
    emit("saved");
    await loadAll();
  } catch (error) {
    notifyError(error);
  } finally {
    saving.value = false;
  }
}

/** 422 details, or the client mirror; either way the drawer stays open with the list shown. */
function showIncomplete(keys: string[]): void {
  incompleteAlert.value = t("promotion.configIncomplete", { fields: missingText(keys) });
}

async function saveAndStart() {
  incompleteAlert.value = "";
  if (!(await validateForm())) return;
  const missing = incompletePromotionFields({
    defaultRate: percentToRate(form.percent),
    platformTemplateIds: form.platformTemplateIds,
    landingUrl: form.landingUrl.trim() || null,
    inviteCode: form.inviteCode.trim() || null
  });
  if (missing.length > 0) {
    showIncomplete(missing);
    return;
  }
  const id = props.kol?.id;
  if (!id) return;
  starting.value = true;
  try {
    await persistConfig();
    try {
      await updatePromotionStatus(id, "PROMOTING");
      ElMessage.success(t("promotion.started"));
      emit("saved");
      emit("update:modelValue", false);
    } catch (error) {
      if (error instanceof ApiError && error.errorCode === "KOL_PROMOTION_CONFIG_INCOMPLETE") {
        showIncomplete(missingPromotionFields(error.payload));
      } else {
        notifyError(error);
      }
    }
  } catch (error) {
    // The configuration save itself failed; the status was not touched.
    notifyError(error);
  } finally {
    starting.value = false;
  }
}

function onRecordsPage(page: number): void {
  void loadRecords(page);
}
</script>

<template>
  <el-drawer
    :model-value="modelValue"
    :title="title"
    size="720px"
    @update:model-value="emit('update:modelValue', $event)"
  >
    <div v-loading="loading">
      <el-alert
        v-if="incompleteAlert"
        class="incomplete"
        type="warning"
        :closable="false"
        :title="incompleteAlert"
        data-test="incomplete-alert"
      />
      <el-form ref="formRef" :model="form" :rules="rules" label-width="130px" @submit.prevent>
        <el-form-item :label="t('kol.rateLabel')" prop="percent">
          <el-input
            v-model="form.percent"
            :placeholder="t('kol.ratePlaceholder')"
            style="width: 200px"
          >
            <template #append>%</template>
          </el-input>
        </el-form-item>
        <el-form-item :label="t('promotion.platforms')">
          <el-select
            v-model="form.platformTemplateIds"
            data-test="platform-select"
            multiple
            clearable
            filterable
            :placeholder="t('common.all')"
            style="width: 100%"
          >
            <el-option
              v-for="item in templateOptions"
              :key="item.id"
              :label="item.label"
              :value="item.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item :label="t('promotion.landingUrl')" prop="landingUrl">
          <el-input
            v-model="form.landingUrl"
            data-test="landing-url"
            maxlength="512"
            :placeholder="t('promotion.landingUrlPlaceholder')"
          />
        </el-form-item>
        <el-form-item :label="t('kol.inviteCode')" prop="inviteCode">
          <el-input v-model="form.inviteCode" maxlength="64" />
        </el-form-item>
        <el-form-item :label="t('kol.sheetUrl')" prop="sheetUrl">
          <el-input
            v-model="form.sheetUrl"
            data-test="sheet-url"
            maxlength="512"
            :placeholder="t('kol.sheetUrlPlaceholder')"
          />
        </el-form-item>
        <el-form-item :label="t('promotion.noteLabel')" prop="note">
          <el-input
            v-model="form.note"
            type="textarea"
            :rows="2"
            maxlength="255"
            :placeholder="t('promotion.notePlaceholder')"
          />
        </el-form-item>
      </el-form>
      <div class="actions">
        <el-button data-test="config-save" :loading="saving" @click="save">
          {{ t("common.save") }}
        </el-button>
        <el-button
          type="primary"
          data-test="config-save-start"
          :loading="starting"
          @click="saveAndStart"
        >
          {{ t("promotion.saveAndStart") }}
        </el-button>
      </div>

      <h4 class="records-title">{{ t("promotion.records") }}</h4>
      <DataTable
        plain
        table-id="kol-promotion-records"
        :rows="records"
        :columns="recordColumns"
        :total="recordsTotal"
        :page="recordsPage"
        :size="recordsSize"
        @update:page="onRecordsPage"
      >
        <template #cell-time="{ row }">{{ formatInstant(asRecord(row).createTime) }}</template>
        <template #cell-operator="{ row }">{{ asRecord(row).operatorName ?? "-" }}</template>
        <template #cell-type="{ row }">{{ typeText(asRecord(row)) }}</template>
        <template #cell-changes="{ row }">
          <div class="changes">
            <div
              v-for="line in promotionFieldDiffs(
                asRecord(row).before,
                asRecord(row).after,
                templateName
              ).map(changeLine)"
              :key="line"
            >
              {{ line }}
            </div>
          </div>
        </template>
        <template #cell-remark="{ row }">{{ asRecord(row).remark ?? "-" }}</template>
      </DataTable>
    </div>
  </el-drawer>
</template>

<style scoped>
.incomplete {
  margin-bottom: 12px;
}
.actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  margin-bottom: 8px;
}
.records-title {
  margin: 20px 0 8px;
  font-weight: 600;
}
.changes {
  display: flex;
  flex-direction: column;
  gap: 2px;
}
</style>
