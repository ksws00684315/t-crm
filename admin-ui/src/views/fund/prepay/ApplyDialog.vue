<script setup lang="ts">
import { computed, onBeforeUnmount, reactive, ref, watch } from "vue";
import { useI18n } from "vue-i18n";
import { ElMessage, type FormInstance, type FormRules } from "element-plus";
import {
  applyPrepay,
  getActivePrepay,
  type ApplyPrepay,
  type PrepayDetail
} from "@/api/commission/prepay";
import PrepayStatusTag from "./PrepayStatusTag.vue";
import CopyText from "@/components/CopyText.vue";
import { useUserStore } from "@/stores/user";
import { notifyError } from "@/utils/notify";
import { serverFieldErrors } from "@/utils/validation";
import { ApiError } from "@/utils/http/error";
import { prepayAbilities, validCny } from "@/utils/prepay";

const props = defineProps<{
  modelValue: boolean;
  kol: { id: string; kolCode: string; name: string | null } | null;
}>();
const emit = defineEmits<{ "update:modelValue": [value: boolean]; saved: [] }>();
const { t, locale } = useI18n();
const me = useUserStore();
const formRef = ref<FormInstance>();
const saving = ref(false);
const preparing = ref(false);
const locked = ref(false);
const errors = ref<Record<string, string>>({});
const form = reactive({ amountCny: "" });
const application = ref<PrepayDetail | null>(null);
let refreshTimer: ReturnType<typeof setInterval> | undefined;
let attempt: ApplyPrepay | null = null;
function beforeClose(done: () => void) {
  if (!saving.value && !preparing.value) done();
}
const rules = computed<FormRules>(() => ({
  amountCny: [
    {
      required: true,
      validator: (_rule, value: string, done) =>
        done(validCny(value) ? undefined : new Error(t("prepay.invalidAmount"))),
      trigger: "blur"
    }
  ]
}));
watch(
  () => [props.modelValue, props.kol?.id] as const,
  ([open]) => {
    clearInterval(refreshTimer);
    if (!open) return;
    application.value = null;
    Object.assign(form, { amountCny: "" });
    attempt = null;
    locked.value = false;
    errors.value = {};
    formRef.value?.clearValidate();
    void refreshApplication();
    clearInterval(refreshTimer);
    refreshTimer = setInterval(() => void refreshApplication(true), 10_000);
  },
  { immediate: true }
);
onBeforeUnmount(() => clearInterval(refreshTimer));
async function refreshApplication(quiet = false) {
  const kolId = props.kol?.id;
  if (!kolId || !props.modelValue) return;
  try {
    const data = await getActivePrepay(kolId);
    if (!props.modelValue || props.kol?.id !== kolId) return;
    application.value = data;
    if (data) {
      form.amountCny = data.amountCny;
      attempt = null;
      locked.value = true;
    } else {
      Object.assign(form, { amountCny: "" });
      attempt = null;
      locked.value = false;
    }
  } catch (error) {
    if (!quiet) notifyError(error);
  }
}
async function save() {
  if (preparing.value || saving.value || !props.kol || !prepayAbilities(me.user).apply) return;
  preparing.value = true;
  try {
    if (!(await formRef.value?.validate().catch(() => false))) return;
    if (!attempt)
      attempt = {
        requestId: crypto.randomUUID(),
        kolId: props.kol.id,
        amountCny: form.amountCny
      };
    saving.value = true;
    locked.value = true;
    try {
      application.value = await applyPrepay(attempt);
      form.amountCny = application.value.amountCny;
      locked.value = true;
      window.dispatchEvent(new Event("prepay-updated"));
      ElMessage.success(t("prepay.saved"));
      emit("saved");
    } catch (error) {
      errors.value = serverFieldErrors(error, Object.keys(form));
      notifyError(error);
      if (error instanceof ApiError && error.status === 409) await refreshApplication(true);
      if (error instanceof ApiError && error.status === 400) {
        attempt = null;
        locked.value = false;
      }
    } finally {
      saving.value = false;
    }
  } finally {
    preparing.value = false;
  }
}
</script>
<template>
  <el-dialog
    :model-value="modelValue"
    :title="t('kol.actionPrepay')"
    width="min(540px, 95vw)"
    :close-on-click-modal="false"
    :before-close="beforeClose"
    @update:model-value="emit('update:modelValue', $event)"
  >
    <template #header>
      <div class="prepay-identity" data-test="prepay-creator">
        <h2 data-test="prepay-creator-name">{{ kol?.name }}</h2>
        <p data-test="prepay-creator-code">{{ kol?.kolCode }}</p>
      </div>
    </template>
    <div data-test="prepay-apply">
      <el-alert
        v-if="locked && !application"
        :title="t('prepay.retrySame')"
        :closable="false"
        type="warning"
      />
      <el-form
        ref="formRef"
        :model="form"
        :rules="rules"
        :label-width="locale === 'zh-CN' ? '156px' : '220px'"
        label-position="left"
        class="prepay-form"
        :disabled="saving || preparing || !!application || locked"
      >
        <el-form-item prop="amountCny" :label="t('prepay.amount')" :error="errors.amountCny"
          ><el-input v-model="form.amountCny" inputmode="decimal" data-test="prepay-amount"
        /></el-form-item>
      </el-form>
      <div v-if="!application" class="prepay-status" data-test="prepay-application-status">
        <span>{{ t("prepay.applicationStatus") }}</span>
        <span>{{ t("prepay.notSubmitted") }}</span>
      </div>
      <template v-if="application">
        <div class="prepay-status" data-test="prepay-application-status">
          <span>{{ t("prepay.applicationStatus") }}</span>
          <PrepayStatusTag
            :status="application.status"
            :label="
              application.status === 'WAITING_DETAILS'
                ? t('prepay.applicationInProgress')
                : undefined
            "
          />
        </div>
        <div class="prepay-status">
          <span>{{ t("prepay.number") }}</span
          ><CopyText :value="application.prepayNo" />
        </div>
        <el-alert :title="t('prepay.completeInFinance')" :closable="false" type="info" />
      </template>
    </div>
    <template #footer
      ><div class="prepay-footer">
        <el-button
          :disabled="saving || preparing"
          data-test="prepay-abandon"
          @click="emit('update:modelValue', false)"
          >{{ t("prepay.cancelAction") }}</el-button
        ><el-button
          v-if="!application"
          type="primary"
          :loading="saving || preparing"
          :disabled="!prepayAbilities(me.user).apply"
          data-test="prepay-submit"
          @click="save"
          >{{ t("prepay.submitAction") }}</el-button
        >
      </div></template
    >
  </el-dialog>
</template>
<style scoped>
.prepay-identity h2 {
  margin: 0;
  font-size: var(--tcrm-font-size-title);
  line-height: var(--tcrm-line-height-title);
  font-weight: 600;
}
.prepay-identity p {
  margin: var(--tcrm-space-1) 0 0;
}
.prepay-form {
  margin-top: var(--tcrm-space-5);
}
.prepay-status {
  display: flex;
  align-items: baseline;
  gap: var(--tcrm-space-2);
  margin-top: var(--tcrm-space-5);
}
.prepay-footer {
  display: flex;
  justify-content: flex-end;
  gap: var(--tcrm-space-4);
  margin-top: var(--tcrm-space-6);
}
.prepay-footer :deep(.el-button) {
  min-width: 96px;
  margin-left: 0;
}
@media (max-width: 767px) {
  .prepay-form :deep(.el-form-item) {
    display: block;
  }
  .prepay-form :deep(.el-form-item__label) {
    width: auto !important;
  }
  .prepay-form :deep(.el-form-item__content) {
    margin-left: 0 !important;
  }
}
</style>
