<script setup lang="ts">
import { computed, reactive, ref, watch } from "vue";
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from "element-plus";
import { useI18n } from "vue-i18n";
import { reviewWithdrawal } from "@/api/commission/finance";
import type { Withdrawal } from "@/api/commission/finance-types";
import { formatInstant } from "@/utils/format";
import { notifyError } from "@/utils/notify";

const props = defineProps<{ modelValue: boolean; withdrawal: Withdrawal | null }>();
const emit = defineEmits<{ "update:modelValue": [value: boolean]; saved: [] }>();
const { t } = useI18n();
const formRef = ref<FormInstance>();
const busy = ref(false);
const decision = ref<"APPROVE" | "REJECT">("APPROVE");
const form = reactive({ note: "" });
const errors = reactive({ note: "" });
const stage = computed(() => "operationsReview");
function amountCurrency(amount: string, currency: string) {
  return `${amount} ${currency}`;
}
const rules = computed<FormRules>(() => ({
  note: [
    {
      required: decision.value === "REJECT",
      whitespace: true,
      message: t("finance.required"),
      trigger: "blur"
    }
  ]
}));

watch(
  () => [props.modelValue, props.withdrawal?.id] as const,
  ([open]) => {
    if (!open) return;
    decision.value = "APPROVE";
    form.note = "";
    errors.note = "";
    formRef.value?.clearValidate();
  }
);

async function submit(next: "APPROVE" | "REJECT") {
  const row = props.withdrawal;
  if (!row || busy.value) return;
  decision.value = next;
  if (!(await formRef.value?.validate().catch(() => false))) return;
  busy.value = true;
  try {
    await ElMessageBox.confirm(
      t(next === "APPROVE" ? "finance.reviewConfirm" : "finance.rejectConfirm"),
      t(next === "APPROVE" ? "finance.approve" : "finance.reject"),
      { type: next === "REJECT" ? "warning" : "info" }
    );
    await reviewWithdrawal(row.id, next, form.note.trim());
    ElMessage.success(t("finance.saved"));
    emit("update:modelValue", false);
    emit("saved");
  } catch (error) {
    if (error !== "cancel" && error !== "close") {
      errors.note = t("finance.reviewerMustDiffer");
      notifyError(error);
    }
  } finally {
    busy.value = false;
  }
}
</script>

<template>
  <el-dialog
    :model-value="modelValue"
    :title="t(`finance.${stage}`)"
    width="min(940px, calc(100vw - 32px))"
    align-center
    class="withdrawal-review-dialog"
    :close-on-click-modal="false"
    @update:model-value="emit('update:modelValue', $event)"
  >
    <div v-if="withdrawal" class="review-content" data-test="withdrawal-review-dialog">
      <ol class="review-steps" :aria-label="t('finance.reviewProcess')">
        <li class="complete"><span>1</span>{{ t("finance.creatorApplied") }}</li>
        <li class="current"><span>2</span>{{ t("finance.operationsReview") }}</li>
        <li><span>3</span>{{ t("finance.financePay") }}</li>
      </ol>
      <el-descriptions :column="2" border class="application-info">
        <el-descriptions-item :label="t('finance.creator')">
          <strong>{{ withdrawal.kolName }}</strong
          ><small class="identity-id">{{ withdrawal.kolCode }}</small>
        </el-descriptions-item>
        <el-descriptions-item :label="t('finance.assignedBd')">{{
          withdrawal.bdName || "—"
        }}</el-descriptions-item>
        <el-descriptions-item :label="t('finance.amount')">{{
          amountCurrency(withdrawal.amount, withdrawal.currency)
        }}</el-descriptions-item>
        <el-descriptions-item :label="t('finance.createdAt')">{{
          formatInstant(withdrawal.createdAt)
        }}</el-descriptions-item>
        <el-descriptions-item :label="t('finance.recipient')" :span="2">
          {{ withdrawal.account.type }} · {{ withdrawal.account.account }} ·
          {{ withdrawal.account.holder }}
          <span v-if="withdrawal.account.network"> · {{ withdrawal.account.network }}</span>
        </el-descriptions-item>
        <el-descriptions-item :label="t('finance.note')" :span="2">{{
          withdrawal.note || "—"
        }}</el-descriptions-item>
        <el-descriptions-item :label="t('finance.estimated')">{{
          withdrawal.estimatedTotal ? amountCurrency(withdrawal.estimatedTotal, "CNY") : "—"
        }}</el-descriptions-item>
        <el-descriptions-item :label="t('finance.available')">{{
          withdrawal.availableTotal ? amountCurrency(withdrawal.availableTotal, "CNY") : "—"
        }}</el-descriptions-item>
      </el-descriptions>
      <el-form ref="formRef" :model="form" :rules="rules" label-position="top">
        <el-form-item
          :label="t(decision === 'REJECT' ? 'finance.rejectReason' : 'finance.reviewNote')"
          prop="note"
          :error="errors.note"
        >
          <el-input v-model="form.note" type="textarea" :rows="2" maxlength="500" show-word-limit />
        </el-form-item>
      </el-form>
    </div>
    <template #footer>
      <el-button :disabled="busy" @click="emit('update:modelValue', false)">{{
        t("finance.cancel")
      }}</el-button>
      <el-button
        type="danger"
        plain
        :loading="busy && decision === 'REJECT'"
        @click="submit('REJECT')"
        >{{ t("finance.reject") }}</el-button
      >
      <el-button
        type="primary"
        :loading="busy && decision === 'APPROVE'"
        data-test="withdrawal-review-approve"
        @click="submit('APPROVE')"
        >{{ t("finance.approve") }}</el-button
      >
    </template>
  </el-dialog>
</template>

<style scoped>
.review-content {
  display: flex;
  flex-direction: column;
  gap: var(--tcrm-space-3);
}
.review-steps {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  list-style: none;
  margin: 0;
  padding: 0;
  gap: var(--tcrm-space-2);
}
.review-steps li {
  display: flex;
  align-items: center;
  gap: var(--tcrm-space-2);
  min-width: 0;
  color: var(--tcrm-color-text-secondary);
  font-size: var(--tcrm-font-size-help);
}
.review-steps li span {
  display: inline-grid;
  place-items: center;
  flex: 0 0 24px;
  height: 24px;
  border-radius: 50%;
  background: var(--tcrm-color-muted);
}
.review-steps li.current,
.review-steps li.complete {
  color: var(--el-color-primary);
}
.review-steps li.current span,
.review-steps li.complete span {
  color: white;
  background: var(--el-color-primary);
}
.identity-id {
  display: block;
  color: var(--tcrm-color-text-secondary);
  font-size: var(--tcrm-font-size-help);
  line-height: 1.3;
}
.application-info :deep(.el-descriptions__cell) {
  padding: 8px 12px;
}
.review-content :deep(.el-form-item) {
  margin-bottom: 0;
}
@media (max-width: 767px) {
  .review-steps {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
  .application-info :deep(.el-descriptions__body .el-descriptions__table) {
    table-layout: fixed;
  }
}
</style>
