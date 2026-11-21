<script setup lang="ts">
import { ElDialog, ElDrawer, ElMessage } from "element-plus";
import type { FormInstance, FormRules } from "element-plus";
import { computed, reactive, ref, watch } from "vue";
import { useI18n } from "vue-i18n";
import {
  createLibraryContent,
  updateLibraryContent,
  type LibraryContentRequest,
  type LibraryContentVO
} from "@/api/kol-library/kol";
import {
  LIBRARY_CONTENT_FORMS,
  LIBRARY_SAMPLE_STATUSES,
  libraryOptionLabel,
  sampleTagType
} from "@/utils/kol-library";
import { formatAmountWithCurrency } from "@/utils/format";
import { notifyError } from "@/utils/notify";

defineOptions({ name: "LibraryContentDialog" });

const { t } = useI18n();

const props = defineProps<{
  modelValue: boolean;
  kolId: string;
  /** The record being edited; null creates a new one. */
  content: LibraryContentVO | null;
  canEdit?: boolean;
}>();
const emit = defineEmits<{
  (event: "update:modelValue", value: boolean): void;
  (event: "saved"): void;
}>();

const editing = computed(() => props.content !== null);
const readOnly = ref(false);
const title = computed(() =>
  readOnly.value
    ? t("library.content.viewTitle")
    : editing.value
      ? t("library.content.editTitle")
      : t("library.content.createTitle")
);
const formRef = ref<FormInstance>();
const saving = ref(false);

function emptyForm(): LibraryContentRequest {
  return {
    publishUrl: "",
    publishTitle: "",
    publishDate: "",
    contentForm: "SHORT_VIDEO",
    sampleCost: null,
    slotFee: null,
    commissionRate: null,
    nextAction: null,
    sampleStatus: null,
    remark: null
  };
}

const form = reactive<LibraryContentRequest>(emptyForm());

const rules = computed<FormRules<LibraryContentRequest>>(() => ({
  publishUrl: [{ required: true, whitespace: true, message: t("library.validation.urlRequired") }],
  publishTitle: [{ required: true, whitespace: true, message: t("library.content.titleRequired") }],
  publishDate: [{ required: true, message: t("library.validation.dateRequired") }],
  contentForm: [{ required: true, message: t("library.content.formRequired") }]
}));

function fill(content: LibraryContentVO | null): void {
  if (!content) {
    Object.assign(form, emptyForm());
    return;
  }
  Object.assign(form, {
    publishUrl: content.publishUrl,
    publishTitle: content.publishTitle ?? "",
    publishDate: content.publishDate,
    contentForm: content.contentForm,
    sampleCost: content.sampleCost,
    slotFee: content.slotFee,
    commissionRate: content.commissionRate,
    nextAction: content.nextAction,
    sampleStatus: content.sampleStatus ?? null,
    remark: content.remark ?? null
  });
}

watch(
  () => props.modelValue,
  open => {
    if (!open) return;
    readOnly.value = !!props.content;
    fill(props.content);
    formRef.value?.clearValidate();
  }
);

function close(): void {
  emit("update:modelValue", false);
}

/** Blank optional inputs travel as null; the backend counts them as 0 (requirement 1.2.6 ③). */
function toRequest(): LibraryContentRequest {
  const clean = (value: string | null): string | null => {
    const text = (value ?? "").trim();
    return text === "" ? null : text;
  };
  return {
    ...form,
    publishUrl: form.publishUrl.trim(),
    publishTitle: form.publishTitle.trim(),
    sampleCost: clean(form.sampleCost),
    slotFee: clean(form.slotFee),
    commissionRate: clean(form.commissionRate),
    nextAction: clean(form.nextAction),
    sampleStatus: form.sampleStatus || null,
    remark: clean(form.remark)
  };
}

async function submit(): Promise<void> {
  if (readOnly.value) return;
  if (!formRef.value || !(await formRef.value.validate().catch(() => false))) return;
  saving.value = true;
  try {
    if (props.content) {
      await updateLibraryContent(props.kolId, props.content.id, toRequest());
    } else {
      await createLibraryContent(props.kolId, toRequest());
    }
    ElMessage.success(editing.value ? t("library.content.saved") : t("library.content.created"));
    emit("saved");
    close();
  } catch (error) {
    notifyError(error);
  } finally {
    saving.value = false;
  }
}
</script>

<template>
  <component
    :is="content ? ElDrawer : ElDialog"
    :model-value="modelValue"
    :title="title"
    v-bind="
      content
        ? {
            size: 'min(680px, 100vw)',
            headerClass: readOnly ? 'content-record-drawer-header' : undefined
          }
        : { width: '720px', top: '6vh' }
    "
    :close-on-click-modal="!saving"
    :close-on-press-escape="!saving"
    data-test="content-dialog"
    @update:model-value="emit('update:modelValue', $event)"
  >
    <!-- The title and this footer stay visible; only the body scrolls (norm 13 §3.7). -->
    <div v-if="readOnly" class="content-record">
      <dl class="content-record__group content-record__group--header">
        <div class="content-record__field">
          <dt>{{ t("library.content.publishTitle") }}</dt>
          <dd class="content-record__title">{{ form.publishTitle || "—" }}</dd>
        </div>
        <div class="content-record__field">
          <dt>{{ t("library.content.publishUrl") }}</dt>
          <dd>
            <a
              v-if="form.publishUrl"
              class="content-record__link"
              :href="form.publishUrl"
              target="_blank"
              rel="noopener noreferrer"
            >
              {{ form.publishUrl }}
            </a>
            <span v-else class="content-record__empty">—</span>
          </dd>
        </div>
      </dl>
      <dl class="content-record__group">
        <div class="content-record__field">
          <dt>{{ t("library.content.publishDate") }}</dt>
          <dd>{{ form.publishDate }}</dd>
        </div>
        <div class="content-record__field">
          <dt>{{ t("library.content.contentForm") }}</dt>
          <dd>{{ libraryOptionLabel("contentForm", form.contentForm) }}</dd>
        </div>
        <div class="content-record__field">
          <dt>{{ t("library.content.sampleStatus") }}</dt>
          <dd>
            <el-tag v-if="form.sampleStatus" size="small" :type="sampleTagType(form.sampleStatus)">
              {{ libraryOptionLabel("sampleStatus", form.sampleStatus) }}
            </el-tag>
            <span v-else class="content-record__empty">—</span>
          </dd>
        </div>
      </dl>
      <dl class="content-record__group content-record__group--numbers">
        <div
          v-for="field in ['sampleCost', 'slotFee'] as const"
          :key="field"
          class="content-record__field"
        >
          <dt>{{ t(`library.content.${field}`) }}</dt>
          <dd v-if="form[field] != null">{{ formatAmountWithCurrency(form[field], "CNY") }}</dd>
          <dd v-else class="content-record__empty">—</dd>
        </div>
        <div class="content-record__field">
          <dt>{{ t("library.content.commissionRate") }}</dt>
          <dd v-if="form.commissionRate != null">{{ form.commissionRate }}%</dd>
          <dd v-else class="content-record__empty">—</dd>
        </div>
      </dl>
      <dl class="content-record__group content-record__group--notes">
        <div
          v-for="field in ['nextAction', 'remark'] as const"
          :key="field"
          class="content-record__field"
        >
          <dt>{{ t(`library.content.${field}`) }}</dt>
          <dd v-if="form[field]?.trim()">{{ form[field] }}</dd>
          <dd v-else class="content-record__empty">—</dd>
        </div>
      </dl>
    </div>
    <el-form
      v-else
      ref="formRef"
      :model="form"
      :rules="rules"
      label-width="120px"
      class="content-dialog__body"
      @submit.prevent
    >
      <el-form-item :label="t('library.content.publishTitle')" prop="publishTitle">
        <el-input v-model="form.publishTitle" maxlength="200" data-test="content-title" />
      </el-form-item>
      <el-form-item :label="t('library.content.publishUrl')" prop="publishUrl">
        <el-input v-model="form.publishUrl" maxlength="512" data-test="content-url" />
      </el-form-item>
      <el-form-item :label="t('library.content.publishDate')" prop="publishDate">
        <el-date-picker
          v-model="form.publishDate"
          type="date"
          value-format="YYYY-MM-DD"
          data-test="content-publish-date"
        />
      </el-form-item>
      <el-form-item :label="t('library.content.contentForm')" prop="contentForm">
        <el-select v-model="form.contentForm" data-test="content-form">
          <el-option
            v-for="option in LIBRARY_CONTENT_FORMS"
            :key="option.value"
            :label="libraryOptionLabel('contentForm', option.value)"
            :value="option.value"
          />
        </el-select>
      </el-form-item>
      <el-form-item :label="t('library.content.sampleStatus')" prop="sampleStatus">
        <el-select
          v-model="form.sampleStatus"
          clearable
          :value-on-clear="null"
          data-test="content-sample-status"
        >
          <el-option
            v-for="option in LIBRARY_SAMPLE_STATUSES"
            :key="option.value"
            :label="libraryOptionLabel('sampleStatus', option.value)"
            :value="option.value"
          />
        </el-select>
      </el-form-item>
      <el-form-item :label="t('library.content.sampleCost')" prop="sampleCost">
        <el-input
          v-model="form.sampleCost"
          type="number"
          min="0"
          step="0.01"
          data-test="content-sample-cost"
          :placeholder="t('library.content.amountPlaceholder')"
        />
      </el-form-item>
      <el-form-item :label="t('library.content.slotFee')" prop="slotFee">
        <el-input
          v-model="form.slotFee"
          type="number"
          min="0"
          step="0.01"
          data-test="content-slot-fee"
          :placeholder="t('library.content.amountPlaceholder')"
        />
      </el-form-item>
      <el-form-item :label="t('library.content.commissionRate')" prop="commissionRate">
        <el-input
          v-model="form.commissionRate"
          type="number"
          min="0"
          max="100"
          step="0.01"
          data-test="content-rate"
          :placeholder="t('library.content.ratePlaceholder')"
        />
      </el-form-item>
      <el-form-item :label="t('library.content.nextAction')" prop="nextAction">
        <el-input
          v-model="form.nextAction"
          type="textarea"
          :rows="2"
          maxlength="1000"
          data-test="content-next-action"
        />
      </el-form-item>
      <el-form-item :label="t('library.content.remark')" prop="remark">
        <el-input
          v-model="form.remark"
          type="textarea"
          :rows="2"
          maxlength="1000"
          data-test="content-remark"
        />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button data-test="content-cancel" @click="close">{{ t("common.cancel") }}</el-button>
      <el-button
        v-if="readOnly && canEdit"
        type="primary"
        data-test="content-enable-edit"
        @click="readOnly = false"
      >
        {{ t("common.edit") }}
      </el-button>
      <el-button
        v-if="!readOnly"
        type="primary"
        :loading="saving"
        data-test="content-save"
        @click="submit"
      >
        {{ t("common.save") }}
      </el-button>
    </template>
  </component>
</template>

<style scoped>
:global(.el-drawer__header.content-record-drawer-header) {
  margin-bottom: 0;
}
.content-record {
  --content-label-width: 10rem;
  color: var(--tcrm-color-text);
  font-size: var(--tcrm-font-size-body);
  line-height: var(--tcrm-line-height-body);
}
.content-record__group {
  display: grid;
  gap: var(--tcrm-space-3);
  margin: 0;
  padding: var(--tcrm-space-4);
}
.content-record__group--header {
  background: var(--tcrm-color-page);
  border-radius: var(--tcrm-radius-card);
}
.content-record__group--numbers,
.content-record__group--notes {
  border-top: 1px solid var(--tcrm-color-border);
}
.content-record__field {
  display: grid;
  grid-template-columns: var(--content-label-width) minmax(0, 1fr);
  align-items: baseline;
  column-gap: var(--tcrm-space-4);
}
.content-record__field dt,
.content-record__empty {
  color: var(--tcrm-color-text-secondary);
}
.content-record__field dd {
  margin: 0;
  overflow-wrap: anywhere;
}
.content-record__title {
  font-size: var(--tcrm-font-size-section);
  line-height: var(--tcrm-line-height-section);
  font-weight: 600;
}
.content-record__group--numbers dd {
  font-variant-numeric: tabular-nums;
}
.content-record__group--notes dd {
  white-space: pre-wrap;
}
.content-record__link {
  color: var(--tcrm-color-primary-text);
  text-decoration: underline;
  text-underline-offset: 0.18em;
}
.content-record__link:hover {
  color: var(--tcrm-color-primary-hover);
}
.content-dialog__body {
  max-height: 64vh;
  overflow-y: auto;
  padding-right: var(--tcrm-space-2);
}
@media (max-width: 520px) {
  .content-record__field {
    grid-template-columns: minmax(0, 1fr);
    row-gap: var(--tcrm-space-1);
  }
}
</style>
