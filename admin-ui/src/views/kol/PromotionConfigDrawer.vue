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
  updateRelationStatus
} from "@/api/kol/promotion";
import type { KolVO, LinkTemplateVO } from "@/api/types";
import DataTable, { type DataColumn } from "@/components/DataTable.vue";
import { formatInstant } from "@/utils/format";
import { ApiError } from "@/utils/http/error";
import { validateInviteCode, validateSheetUrl } from "@/utils/kol-validation";
import { notifyError } from "@/utils/notify";
import {
  incompletePromotionFields,
  missingPromotionFields,
  promotionFieldDiffs
} from "@/utils/promotion";
import { percentToRate, rateToPercent, validatePercent } from "@/utils/rate";
import { rule } from "@/utils/validation";

defineOptions({ name: "PromotionConfigDrawer" });

const RECORD_FIELD_KEYS = [
  "defaultRate",
  "platforms",
  "platformAddress",
  "inviteCode",
  "sheetUrl",
  "jumpTemplateId",
  "relationStatus",
  "reviewStatus",
  "name",
  "phone",
  "whatsapp",
  "primaryPlatform",
  "primaryHandle",
  "country",
  "remark"
];

const props = defineProps<{
  modelValue: boolean;
  kol: KolVO | null;
  readOnly?: boolean;
}>();
const emit = defineEmits<{
  (event: "update:modelValue", value: boolean): void;
  (event: "saved"): void;
  (event: "openReview"): void;
}>();

const { t, te } = useI18n();

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
const incompleteKeys = ref<string[]>([]);

const form = reactive({
  percent: "",
  platforms: [] as Array<{ templateId: string; address: string }>,
  inviteCode: "",
  sheetUrl: "",
  jumpTemplateId: null as string | null,
  note: ""
});

const rules: FormRules = {
  percent: [rule(validatePercent)],
  inviteCode: [rule(validateInviteCode)],
  sheetUrl: [rule(validateSheetUrl)]
};

const title = computed(() =>
  props.kol
    ? t("promotion.configTitleWithName", { code: props.kol.kolCode, name: props.kol.name })
    : t("promotion.configTitle")
);

/** Read-only views use names already included in the KOL list row. */
const templateOptions = computed(() =>
  props.readOnly
    ? (props.kol?.platforms ?? []).map(item => ({
        id: item.templateId,
        label: item.templateName || item.templateId
      }))
    : templates.value
        .filter(item => item.status === 1)
        .map(item => ({ id: item.id, label: item.templateName }))
);

/** Jump platform dropdown lists only the platforms already selected. */
const jumpTemplateOptions = computed(() =>
  templateOptions.value.filter(item =>
    form.platforms.some(platform => platform.templateId === item.id)
  )
);

function fieldLabel(key: string): string {
  return RECORD_FIELD_KEYS.includes(key) ? t(`promotion.field.${key}`) : key;
}

function missingText(keys: string[]): string {
  return keys.map(fieldLabel).join("、");
}

const recordColumns = computed<DataColumn[]>(() => [
  { key: "version", label: t("promotion.version"), width: 85 },
  { key: "effective", label: t("promotion.effectiveTime"), width: 170, mono: true },
  { key: "time", label: t("promotion.recordTime"), width: 170, mono: true },
  { key: "operator", label: t("promotion.recordOperator"), width: 120 },
  { key: "type", label: t("promotion.recordType"), width: 80 },
  { key: "changes", label: t("promotion.recordChanges"), minWidth: 260 },
  { key: "remark", label: t("promotion.recordRemark"), minWidth: 120, tooltip: true }
]);

function asRecord(row: object): PromotionRecordVO {
  return row as PromotionRecordVO;
}

/** Unknown types (or the empty row Element Plus uses for column measuring) render as "-". */
function typeText(record: PromotionRecordVO): string {
  const key = `promotion.recordTypeOption.${record?.changeType}`;
  return record?.changeType && te(key) ? t(key) : "-";
}

/** One "label: from → to" line; a status value renders like the status tags do. */
function changeLine(diff: { key: string; from: string; to: string }): string {
  const show = (key: string, value: string) => {
    const messageKey =
      key === "relationStatus"
        ? `kol.relationStatusOption.${value}`
        : key === "reviewStatus"
          ? `kol.reviewStatusOption.${value}`
          : "";
    return messageKey && te(messageKey) ? t(messageKey) : value;
  };
  return t("promotion.changeLine", {
    label: fieldLabel(diff.key),
    from: show(diff.key, diff.from),
    to: show(diff.key, diff.to)
  });
}

watch(
  () => props.modelValue,
  open => {
    if (!open || !props.kol) return;
    incompleteAlert.value = "";
    incompleteKeys.value = [];
    formRef.value?.clearValidate();
    void loadAll();
  }
);

watch(
  () => form.platforms.map(item => item.templateId),
  ids => {
    if (ids.length === 1) {
      form.jumpTemplateId = ids[0] ?? null;
      return;
    }
    if (form.jumpTemplateId && !ids.includes(form.jumpTemplateId)) {
      form.jumpTemplateId = null;
    }
  }
);

async function loadAll() {
  const id = props.kol?.id;
  if (!id) return;
  loading.value = true;
  try {
    const templateRequest = props.readOnly
      ? Promise.resolve([] as LinkTemplateVO[])
      : listLinkTemplates().catch(() => [] as LinkTemplateVO[]);
    const [config, templateList, recordPage] = await Promise.all([
      getPromotionConfig(id),
      templateRequest,
      listPromotionRecords(id, { page: 1, size: recordsSize })
    ]);
    templates.value = templateList;
    form.percent = rateToPercent(config.defaultRate);
    form.platforms = (config.platforms ?? []).map(item => ({
      templateId: item.templateId,
      address: item.address
    }));
    form.inviteCode = config.inviteCode ?? "";
    form.sheetUrl = config.sheetUrl ?? "";
    form.jumpTemplateId = config.jumpTemplateId ?? null;
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
    platforms: form.platforms.map(platform => ({
      templateId: platform.templateId,
      address: platform.address.trim()
    })),
    inviteCode: form.inviteCode.trim() || null,
    sheetUrl: form.sheetUrl.trim() || null,
    jumpTemplateId: form.jumpTemplateId,
    remark: form.note.trim() || null
  };
}

async function validateForm(): Promise<boolean> {
  const formValid = formRef.value ? await formRef.value.validate().catch(() => false) : false;
  if (!formValid) return false;
  const missing = incompletePromotionFields({
    defaultRate: percentToRate(form.percent),
    platforms: form.platforms,
    inviteCode: form.inviteCode.trim() || null,
    jumpTemplateId: form.jumpTemplateId,
    sheetUrl: form.sheetUrl.trim() || null
  });
  for (const platform of form.platforms) {
    if (
      platform.address &&
      !/^https:\/\/[^\s/?#]+\S*$/.test(platform.address.trim()) &&
      !missing.includes("platformAddress")
    )
      missing.push("platformAddress");
  }
  if (missing.length) showIncomplete(missing);
  return missing.length === 0;
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
  incompleteKeys.value = [];
  saving.value = true;
  try {
    await persistConfig();
    ElMessage.success(t("promotion.saved"));
    emit("saved");
    await loadAll();
  } catch (error) {
    if (error instanceof ApiError && error.errorCode === "KOL_REVIEW_REQUIRED") {
      incompleteKeys.value = ["review"];
      incompleteAlert.value = t("promotion.reviewRequired");
    } else notifyError(error);
  } finally {
    saving.value = false;
  }
}

/** 422 details, or the client mirror; either way the drawer stays open with the list shown. */
function showIncomplete(keys: string[]): void {
  incompleteKeys.value = keys;
  incompleteAlert.value = keys.length
    ? t("promotion.configIncomplete", { fields: missingText(keys) })
    : t("errors.KOL_PROMOTION_CONFIG_INCOMPLETE");
}

function goToField(key: string): void {
  if (key === "review") {
    emit("openReview");
    emit("update:modelValue", false);
    return;
  }
  const selector =
    key === "defaultRate"
      ? '[data-test="rate-input"]'
      : key === "platforms" || key === "platformAddress"
        ? '[data-test="platform-rows"]'
        : key === "inviteCode"
          ? '[data-test="creator-invite-code"]'
          : key === "jumpTemplateId"
            ? '[data-test="jump-template"]'
            : key === "sheetUrl"
              ? '[data-test="sheet-url"]'
              : null;
  if (selector)
    document.querySelector(selector)?.scrollIntoView({ behavior: "smooth", block: "center" });
}

async function saveAndStart() {
  incompleteAlert.value = "";
  if (!(await validateForm())) return;
  const missing = incompletePromotionFields({
    defaultRate: percentToRate(form.percent),
    platforms: form.platforms,
    inviteCode: form.inviteCode.trim() || null,
    jumpTemplateId: form.jumpTemplateId,
    sheetUrl: form.sheetUrl.trim() || null,
    review: props.kol?.reviewStatus === "APPROVED"
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
      await updateRelationStatus(id, "PROMOTING");
      ElMessage.success(t("promotion.started"));
      emit("saved");
      emit("update:modelValue", false);
    } catch (error) {
      if (error instanceof ApiError && error.errorCode === "KOL_PROMOTION_CONFIG_INCOMPLETE") {
        showIncomplete(missingPromotionFields(error.payload));
      } else if (error instanceof ApiError && error.errorCode === "KOL_REVIEW_REQUIRED") {
        incompleteKeys.value = ["review"];
        incompleteAlert.value = t("promotion.reviewRequired");
      } else {
        notifyError(error);
      }
    }
  } catch (error) {
    // The configuration save itself failed; the status was not touched.
    if (error instanceof ApiError && error.errorCode === "KOL_REVIEW_REQUIRED") {
      incompleteKeys.value = ["review"];
      incompleteAlert.value = t("promotion.reviewRequired");
    } else notifyError(error);
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
      <div v-if="incompleteKeys.length" class="missing-fields" data-test="missing-fields">
        <span v-for="key in incompleteKeys" :key="key" class="missing-field">
          {{ fieldLabel(key) }}
          <el-button link type="primary" @click="goToField(key)">{{
            t("promotion.goConfig")
          }}</el-button>
        </span>
      </div>
      <el-form
        ref="formRef"
        :model="form"
        :rules="rules"
        :disabled="readOnly"
        label-width="130px"
        @submit.prevent
      >
        <el-form-item :label="t('kol.rateLabel')" prop="percent">
          <el-input
            v-model="form.percent"
            data-test="rate-input"
            :placeholder="t('kol.ratePlaceholder')"
            style="width: 200px"
          >
            <template #append>%</template>
          </el-input>
        </el-form-item>
        <el-form-item :label="t('promotion.platforms')">
          <div class="platform-list" data-test="platform-rows">
            <div v-for="(platform, index) in form.platforms" :key="index" class="platform-row">
              <el-select
                v-model="platform.templateId"
                filterable
                :placeholder="t('promotion.platformPlaceholder')"
                data-test="platform-select"
              >
                <el-option
                  v-for="item in templateOptions"
                  :key="item.id"
                  :label="item.label"
                  :value="item.id"
                  :disabled="
                    form.platforms.some(
                      (other, otherIndex) => otherIndex !== index && other.templateId === item.id
                    )
                  "
                />
              </el-select>
              <el-input
                v-model="platform.address"
                :placeholder="t('promotion.platformAddressPlaceholder')"
                data-test="platform-address"
              />
              <el-button
                v-if="!readOnly"
                link
                type="danger"
                @click="form.platforms.splice(index, 1)"
                >{{ t("common.delete") }}</el-button
              >
            </div>
            <el-button
              v-if="!readOnly"
              data-test="add-platform"
              @click="form.platforms.push({ templateId: '', address: '' })"
              >{{ t("promotion.addPlatform") }}</el-button
            >
          </div>
        </el-form-item>
        <el-form-item :label="t('promotion.jumpTemplate')" data-test="jump-template">
          <el-select
            v-model="form.jumpTemplateId"
            clearable
            filterable
            :placeholder="t('promotion.jumpTemplatePlaceholder')"
            style="width: 100%"
          >
            <el-option
              v-for="item in jumpTemplateOptions"
              :key="item.id"
              :label="item.label"
              :value="item.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item :label="t('promotion.creatorInviteCode')" prop="inviteCode">
          <el-input v-model="form.inviteCode" data-test="creator-invite-code" maxlength="64" />
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
        <el-button @click="emit('update:modelValue', false)">{{ t("common.close") }}</el-button>
        <el-button v-if="!readOnly" data-test="config-save" :loading="saving" @click="save">
          {{ t("common.save") }}
        </el-button>
        <el-button
          v-if="!readOnly"
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
        <template #cell-version="{ row }">{{ asRecord(row).versionNo ?? "-" }}</template>
        <template #cell-effective="{ row }">{{
          asRecord(row).effectiveTime ? formatInstant(asRecord(row).effectiveTime!) : "-"
        }}</template>
        <template #cell-operator="{ row }">{{ asRecord(row).operatorName ?? "-" }}</template>
        <template #cell-type="{ row }">{{ typeText(asRecord(row)) }}</template>
        <template #cell-changes="{ row }">
          <div class="changes">
            <div
              v-for="line in promotionFieldDiffs(asRecord(row).before, asRecord(row).after).map(
                changeLine
              )"
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
.missing-fields {
  display: flex;
  flex-wrap: wrap;
  gap: 8px 16px;
  margin: -4px 0 16px;
}
.missing-field {
  display: inline-flex;
  align-items: center;
  gap: 4px;
}
.platform-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
  width: 100%;
}
.platform-row {
  display: grid;
  grid-template-columns: minmax(160px, 0.7fr) minmax(220px, 1fr) auto;
  gap: 8px;
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
