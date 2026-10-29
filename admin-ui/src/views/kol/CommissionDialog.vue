<script setup lang="ts">
import NameCode from "@/components/NameCode.vue";
import { ElMessage } from "element-plus";
import type { FormInstance, FormRules } from "element-plus";
import { computed, reactive, ref, watch } from "vue";
import { useI18n } from "vue-i18n";
import { listLinkTemplates } from "@/api/link/template";
import {
  getPromotionConfig,
  listPromotionRecords,
  savePromotionConfig,
  type PromotionRecordVO
} from "@/api/kol/promotion";
import type { KolVO, LinkTemplateVO } from "@/api/types";
import DataTable, { type DataColumn } from "@/components/DataTable.vue";
import { formatInstant } from "@/utils/format";
import { ApiError } from "@/utils/http/error";
import { validateSheetUrl } from "@/utils/kol-validation";
import { notifyError } from "@/utils/notify";
import { incompletePromotionFields, promotionFieldDiffs } from "@/utils/promotion";
import { percentToRate, rateToPercent, validatePercent } from "@/utils/rate";
import { rule } from "@/utils/validation";

defineOptions({ name: "CommissionDialog" });

const RECORD_FIELD_KEYS = [
  "defaultRate",
  "platforms",
  "sheetUrl",
  "jumpTemplateId",
  "configVersion",
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

/**
 * Commission (04.03, task 32 §2.2): one dialog, two tabs — the configuration and its change
 * history. Required fields follow the customer supplement; there is no jump-platform picker (the backend takes the
 * first platform) and the only button is Save: a complete save flips the relation status to
 * "promoting" on the backend (task 33 §3.9).
 */
const props = defineProps<{
  modelValue: boolean;
  kol: KolVO | null;
}>();
const emit = defineEmits<{
  (event: "update:modelValue", value: boolean): void;
  (event: "saved"): void;
}>();

const { t, te } = useI18n();

const loading = ref(false);
const saving = ref(false);
const templates = ref<LinkTemplateVO[]>([]);
const records = ref<PromotionRecordVO[]>([]);
const recordsTotal = ref(0);
const recordsPage = ref(1);
const recordsSize = 10;
const formRef = ref<FormInstance>();
const activeTab = ref("config");
const incompleteAlert = ref("");
const incompleteKeys = ref<string[]>([]);

const form = reactive({
  percent: "",
  /** Selected platform ids; persisted addresses stay hidden and are reused on save. */
  platformIds: [] as string[],
  platforms: [] as Array<{ templateId: string; address: string }>,
  inviteCode: "",
  note: "",
  sheetUrl: ""
});

const rules: FormRules = {
  percent: [rule(validatePercent)],
  sheetUrl: [
    rule(value => (value.trim() ? validateSheetUrl(value) : t("validation.sheetUrlRequired")))
  ]
};

const title = computed(() =>
  props.kol
    ? t("promotion.configTitleWithName", { code: props.kol.kolCode, name: props.kol.name })
    : t("promotion.configTitle")
);

/** All enabled templates; the picker is searchable so 100+ platforms stay usable. */
const templateOptions = computed(() =>
  templates.value
    .filter(item => item.status === 1)
    .map(item => ({ id: item.id, label: item.templateCode }))
);

function templateName(id: string): string {
  const known = templates.value.find(item => item.id === id);
  if (known) return known.templateCode;
  return props.kol?.platforms.find(item => item.templateId === id)?.templateName ?? id;
}

function fieldLabel(key: string): string {
  return RECORD_FIELD_KEYS.includes(key) ? t(`promotion.field.${key}`) : key;
}

const recordColumns = computed<DataColumn[]>(() => [
  { key: "version", label: t("promotion.version"), width: 85 },
  { key: "effective", label: t("promotion.effectiveTime"), width: 170 },
  { key: "operator", align: "left" as const, label: t("promotion.recordOperator"), width: 120 },
  { key: "changes", label: t("promotion.recordChanges"), minWidth: 280 }
]);

function asRecord(row: object): PromotionRecordVO {
  return row as PromotionRecordVO;
}

function changeLine(diff: { key: string; from: string; to: string }): string {
  const show = (key: string, value: string) => {
    const messageKey =
      key === "relationStatus"
        ? `kol.relationStatusOption.${value}`
        : key === "reviewStatus"
          ? `kol.reviewStatusOption.${value}`
          : "";
    if (messageKey && te(messageKey)) return t(messageKey);
    // Old records may carry the jump-platform id; show the template name, not the raw id.
    if (key === "jumpTemplateId") {
      const id = String(value);
      return id === "-" ? value : templateName(id);
    }
    return value;
  };
  return t("promotion.changeLine", {
    label: fieldLabel(diff.key),
    from: show(diff.key, diff.from),
    to: show(diff.key, diff.to)
  });
}

function visibleChanges(record: PromotionRecordVO) {
  return promotionFieldDiffs(record.before, record.after).filter(
    diff => diff.key !== "inviteCode" && diff.key !== "platformAddress"
  );
}

watch(
  () => props.modelValue,
  open => {
    if (!open || !props.kol) return;
    incompleteAlert.value = "";
    incompleteKeys.value = [];
    activeTab.value = "config";
    formRef.value?.clearValidate();
    void loadAll();
  }
);

/** Selecting a platform adds its address row; deselecting removes it (04.03). */
function syncPlatforms(ids: string[]): void {
  form.platformIds = ids;
  form.platforms = ids.map(id => ({
    templateId: id,
    address:
      form.platforms.find(row => row.templateId === id)?.address ??
      templates.value.find(item => item.id === id)?.templateUrl ??
      ""
  }));
}

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
    form.platforms = (config.platforms ?? []).map(item => ({
      templateId: item.templateId,
      address: item.address ?? ""
    }));
    form.platformIds = form.platforms.map(item => item.templateId);
    form.inviteCode = config.inviteCode ?? "";
    form.note = config.remark ?? "";
    form.sheetUrl = config.sheetUrl ?? "";
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

function missingText(keys: string[]): string {
  return keys.map(fieldLabel).join("、");
}

function showIncomplete(keys: string[]): void {
  incompleteKeys.value = keys;
  incompleteAlert.value = keys.length
    ? t("promotion.configIncomplete", { fields: missingText(keys) })
    : "";
}

async function save() {
  const id = props.kol?.id;
  if (!id || saving.value) return;
  incompleteAlert.value = "";
  const formValid = formRef.value ? await formRef.value.validate().catch(() => false) : false;
  if (!formValid) return;
  const missing = incompletePromotionFields({
    defaultRate: percentToRate(form.percent),
    platforms: form.platforms,
    sheetUrl: form.sheetUrl.trim() || null,
    review: props.kol?.reviewStatus === "APPROVED"
  });
  if (missing.length) {
    showIncomplete(missing);
    return;
  }
  saving.value = true;
  try {
    await savePromotionConfig(id, {
      defaultRate: percentToRate(form.percent),
      platforms: form.platforms.map(platform => ({
        templateId: platform.templateId,
        address: platform.address.trim() || null
      })),
      inviteCode: form.inviteCode.trim() || null,
      // No jump platform any more: the backend defaults to the first selected platform (33 §3.10).
      jumpTemplateId: null,
      sheetUrl: form.sheetUrl.trim(),
      remark: form.note.trim()
    });
    ElMessage.success(t("promotion.saved"));
    emit("saved");
    await loadAll();
  } catch (error) {
    if (error instanceof ApiError && error.errorCode === "KOL_REVIEW_REQUIRED") {
      showIncomplete(["review"]);
    } else if (error instanceof ApiError && error.errorCode === "KOL_PROMOTION_CONFIG_INCOMPLETE") {
      const fields = Array.isArray(error.payload)
        ? error.payload.filter((item): item is string => typeof item === "string")
        : [];
      showIncomplete(fields);
    } else {
      notifyError(error);
    }
  } finally {
    saving.value = false;
  }
}

function onRecordsPage(page: number): void {
  void loadRecords(page);
}
</script>

<template>
  <el-dialog
    :model-value="modelValue"
    :title="title"
    width="760px"
    @update:model-value="emit('update:modelValue', $event)"
  >
    <NameCode v-if="kol" :name="kol?.name" :code="kol?.kolCode" />
    <div v-loading="loading">
      <el-tabs v-model="activeTab">
        <el-tab-pane :label="t('promotion.configTab')" name="config">
          <el-alert
            v-if="incompleteAlert"
            class="incomplete"
            :type="incompleteKeys.includes('review') ? 'warning' : 'error'"
            :closable="false"
            :title="incompleteAlert"
            data-test="incomplete-alert"
          />
          <el-form ref="formRef" :model="form" :rules="rules" label-position="top" @submit.prevent>
            <el-form-item :label="t('promotion.field.defaultRate')" prop="percent">
              <el-input
                v-model="form.percent"
                data-test="rate-input"
                :placeholder="t('kol.ratePlaceholder')"
                style="width: 200px"
              >
                <template #append>%</template>
              </el-input>
            </el-form-item>
            <el-form-item :label="t('promotion.platforms')" data-test="platform-select">
              <el-select
                :model-value="form.platformIds"
                multiple
                filterable
                :placeholder="t('promotion.platformPlaceholder')"
                style="width: 100%"
                @update:model-value="syncPlatforms($event as string[])"
              >
                <el-option
                  v-for="item in templateOptions"
                  :key="item.id"
                  :label="item.label"
                  :value="item.id"
                />
              </el-select>
            </el-form-item>
            <el-form-item :label="t('promotion.field.sheetUrl')" prop="sheetUrl">
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
                data-test="commission-note"
              />
            </el-form-item>
          </el-form>
        </el-tab-pane>
        <el-tab-pane :label="t('promotion.recordsTab')" name="records" lazy>
          <DataTable
            plain
            table-id="kol-commission-records"
            :rows="records"
            :columns="recordColumns"
            :total="recordsTotal"
            :page="recordsPage"
            :size="recordsSize"
            @update:page="onRecordsPage"
          >
            <template #cell-version="{ row }">{{ asRecord(row).versionNo ?? "-" }}</template>
            <template #cell-effective="{ row }">{{
              asRecord(row).effectiveTime ? formatInstant(asRecord(row).effectiveTime!) : "-"
            }}</template>
            <template #cell-operator="{ row }">{{ asRecord(row).operatorName ?? "-" }}</template>
            <template #cell-changes="{ row }">
              <div class="changes">
                <div v-for="line in visibleChanges(asRecord(row)).map(changeLine)" :key="line">
                  {{ line }}
                </div>
              </div>
            </template>
          </DataTable>
        </el-tab-pane>
      </el-tabs>
    </div>
    <template #footer>
      <el-button @click="emit('update:modelValue', false)">{{ t("common.close") }}</el-button>
      <el-button type="primary" data-test="commission-save" :loading="saving" @click="save">
        {{ t("common.save") }}
      </el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.incomplete {
  margin-bottom: 12px;
}
.changes {
  display: flex;
  flex-direction: column;
  gap: 2px;
}
</style>
