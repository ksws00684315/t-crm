<script setup lang="ts">
import { ElMessage } from "element-plus";
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
  LIBRARY_SAMPLE_TYPES,
  libraryOptionLabel
} from "@/utils/kol-library";
import { notifyError } from "@/utils/notify";

defineOptions({ name: "LibraryContentDialog" });

const { t } = useI18n();

const props = defineProps<{
  modelValue: boolean;
  kolId: string;
  /** The record being edited; null creates a new one. */
  content: LibraryContentVO | null;
}>();
const emit = defineEmits<{
  (event: "update:modelValue", value: boolean): void;
  (event: "saved"): void;
}>();

const editing = computed(() => props.content !== null);
const title = computed(() =>
  editing.value ? t("library.content.editTitle") : t("library.content.createTitle")
);
const formRef = ref<FormInstance>();
const saving = ref(false);
const serverErrors = ref<Record<string, string>>({});

function emptyForm(): LibraryContentRequest {
  return {
    publishUrl: "",
    publishDate: "",
    contentForm: "SHORT_VIDEO",
    clicks: null,
    orders: null,
    gmv: null,
    sampleType: null,
    sampleCost: null,
    slotFee: null,
    commissionRate: null,
    nextAction: null
  };
}

const form = reactive<LibraryContentRequest>(emptyForm());

const rules = computed<FormRules>(() => ({
  publishUrl: [
    {
      validator: (_rule, value: string, callback: (error?: Error) => void) => {
        if (!value.trim()) callback(new Error(t("library.validation.urlRequired")));
        else callback();
      },
      trigger: "blur"
    }
  ],
  publishDate: [
    {
      validator: (_rule, value: string, callback: (error?: Error) => void) => {
        if (!value) callback(new Error(t("library.validation.dateRequired")));
        else callback();
      },
      trigger: "change"
    }
  ]
}));

/** Decimal string arithmetic: amounts never go through floating point. */
function decimal(value: unknown): bigint | null {
  const text = String(value ?? "").trim();
  if (!/^\d*(\.\d*)?$/.test(text) || text === "" || text === ".") return null;
  return scaled(text);
}

/** The text as a number of hundredths (two decimals), or null when it is not a plain amount. */
function scaled(text: string): bigint {
  const [integer = "0", fraction = ""] = text.split(".");
  const hundredths = `${integer || "0"}${(fraction + "00").slice(0, 2)}`;
  return BigInt(hundredths);
}

/** Formats hundredths back into a two-decimal string. */
function formatHundredths(value: bigint): string {
  const negative = value < BigInt(0);
  const digits = (negative ? -value : value).toString().padStart(3, "0");
  const whole = digits.slice(0, -2);
  const fraction = digits.slice(-2);
  return `${negative ? "-" : ""}${whole}.${fraction}`;
}

/** Divides with two-decimal rounding, half up, in integer arithmetic. */
function divideHundredths(amount: bigint, divisor: bigint): string {
  const scale = BigInt(100);
  const scaledAmount = amount * scale;
  const rounded = (scaledAmount * BigInt(2) + divisor) / (divisor * BigInt(2));
  return formatHundredths(rounded);
}

/**
 * Live calculated fields (requirement 1.2.5 ⑥), the same formulas as the backend (1.2.6 ③): an
 * empty amount or rate counts as 0, the commission is GMV × rate ÷ 100, the total cost adds the
 * sample cost, slot fee and commission, and the ROI is the GMV over the total cost (— at 0 cost).
 * The saved values are the backend's answer; this is the preview while typing.
 */
const calculated = computed(() => {
  const gmv = decimal(form.gmv) ?? BigInt(0);
  const rate = decimal(form.commissionRate) ?? BigInt(0);
  const sample = decimal(form.sampleCost) ?? BigInt(0);
  const slot = decimal(form.slotFee) ?? BigInt(0);
  // rate is hundredths of a percent (e.g. 1250 = 12.50%): commission = gmv × rate / 10000.
  const commission = (gmv * rate + BigInt(5000)) / BigInt(10000);
  const cost = sample + slot + commission;
  const roi = cost === BigInt(0) ? null : divideHundredths(gmv, cost);
  return {
    commission: formatHundredths(commission),
    cost: formatHundredths(cost),
    roi
  };
});

function fill(content: LibraryContentVO | null): void {
  if (!content) {
    Object.assign(form, emptyForm());
    return;
  }
  Object.assign(form, {
    publishUrl: content.publishUrl,
    publishDate: content.publishDate,
    contentForm: content.contentForm,
    clicks: content.clicks,
    orders: content.orders,
    gmv: content.gmv,
    sampleType: content.sampleType,
    sampleCost: content.sampleCost,
    slotFee: content.slotFee,
    commissionRate: content.commissionRate,
    nextAction: content.nextAction
  });
}

watch(
  () => props.modelValue,
  open => {
    if (!open) return;
    serverErrors.value = {};
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
    gmv: clean(form.gmv),
    sampleCost: clean(form.sampleCost),
    slotFee: clean(form.slotFee),
    commissionRate: clean(form.commissionRate),
    clicks: clean(form.clicks),
    orders: clean(form.orders),
    nextAction: clean(form.nextAction)
  };
}

async function submit(): Promise<void> {
  serverErrors.value = {};
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
  <el-dialog
    :model-value="modelValue"
    :title="title"
    width="720px"
    top="6vh"
    data-test="content-dialog"
    @update:model-value="emit('update:modelValue', $event)"
  >
    <!-- The title and this footer stay visible; only the body scrolls (norm 13 §3.7). -->
    <el-form
      ref="formRef"
      :model="form"
      :rules="rules"
      label-width="120px"
      class="content-dialog__body"
      @submit.prevent
    >
      <el-form-item
        :label="t('library.content.publishUrl')"
        prop="publishUrl"
        :error="serverErrors.publishUrl"
      >
        <el-input v-model="form.publishUrl" maxlength="512" data-test="content-url" />
      </el-form-item>
      <el-form-item
        :label="t('library.content.publishDate')"
        prop="publishDate"
        :error="serverErrors.publishDate"
      >
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
      <el-form-item :label="t('library.content.clicks')" prop="clicks">
        <el-input v-model="form.clicks" type="number" min="0" step="1" data-test="content-clicks" />
      </el-form-item>
      <el-form-item :label="t('library.content.orders')" prop="orders">
        <el-input v-model="form.orders" type="number" min="0" step="1" data-test="content-orders" />
      </el-form-item>
      <el-form-item :label="t('library.content.gmv')" prop="gmv">
        <el-input
          v-model="form.gmv"
          type="number"
          min="0"
          step="0.01"
          data-test="content-gmv"
          :placeholder="t('library.content.amountPlaceholder')"
        />
      </el-form-item>
      <el-form-item :label="t('library.content.sampleType')" prop="sampleType">
        <el-select v-model="form.sampleType" clearable data-test="content-sample-type">
          <el-option
            v-for="option in LIBRARY_SAMPLE_TYPES"
            :key="option.value"
            :label="libraryOptionLabel('sampleType', option.value)"
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
      <!-- Calculated, read-only, refreshed while typing (requirement 1.2.5 ⑥). -->
      <el-form-item :label="t('library.content.estimatedCommission')">
        <span class="content-dialog__calculated" data-test="calculated-commission">
          ¥{{ calculated.commission }}
        </span>
      </el-form-item>
      <el-form-item :label="t('library.content.totalCost')">
        <span class="content-dialog__calculated" data-test="calculated-cost">
          ¥{{ calculated.cost }}
        </span>
      </el-form-item>
      <el-form-item :label="t('library.content.actualRoi')">
        <span class="content-dialog__calculated" data-test="calculated-roi">
          {{ calculated.roi ?? "—" }}
        </span>
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
    </el-form>
    <template #footer>
      <el-button data-test="content-cancel" @click="close">{{ t("common.cancel") }}</el-button>
      <el-button type="primary" :loading="saving" data-test="content-save" @click="submit">
        {{ t("common.save") }}
      </el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.content-dialog__body {
  max-height: 64vh;
  overflow-y: auto;
  padding-right: var(--tcrm-space-2);
}
.content-dialog__calculated {
  font-weight: 600;
}
</style>
