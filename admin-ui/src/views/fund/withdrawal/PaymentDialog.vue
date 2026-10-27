<script setup lang="ts">
import { computed, reactive, ref, watch } from "vue";
import { useI18n } from "vue-i18n";
import { ElMessage, type FormInstance, type FormRules } from "element-plus";
import { payWithdrawal, uploadWithdrawalVoucher } from "@/api/commission/finance";
import type { PaymentRegistration, Withdrawal } from "@/api/commission/finance-types";
import { cents, validMoney } from "@/utils/finance";
import { notifyError } from "@/utils/notify";
import { serverFieldErrors } from "@/utils/validation";
import { randomUuid } from "@/utils/uuid";
const props = defineProps<{ modelValue: boolean; withdrawal: Withdrawal | null }>();
const emit = defineEmits<{ "update:modelValue": [value: boolean]; saved: [] }>();
const { t } = useI18n();
const formRef = ref<FormInstance>();
const busy = ref(false);
const file = ref<File>();
const errors = ref<Record<string, string>>({});
let key = "";
const form = reactive<PaymentRegistration>({
  actualAmount: "",
  actualCurrency: "USD",
  amountCny: "",
  paidAt: "",
  method: "PAYPAL",
  financeNote: "",
  conversionNote: "",
  voucher: ""
});
watch(
  () => props.modelValue,
  open => {
    if (open && props.withdrawal) {
      Object.assign(form, {
        actualAmount: "",
        actualCurrency: props.withdrawal.account.currency,
        amountCny: props.withdrawal.amount,
        paidAt: new Date().toISOString(),
        method: props.withdrawal.account.type,
        financeNote: "",
        conversionNote: "",
        voucher: ""
      });
      file.value = undefined;
      errors.value = {};
      key = randomUuid();
      formRef.value?.clearValidate();
    }
  }
);
const rules = computed<FormRules>(() => ({
  actualAmount: [
    {
      validator: (_rule, value, done) =>
        done(validMoney(value) ? undefined : new Error(t("finance.validAmount"))),
      trigger: "blur"
    }
  ],
  amountCny: [
    {
      validator: (_rule, value, done) =>
        done(
          validMoney(value) && props.withdrawal && cents(value) === cents(props.withdrawal.amount)
            ? undefined
            : new Error(t("finance.writeOffMismatch"))
        ),
      trigger: "blur"
    }
  ],
  paidAt: [{ required: true, message: t("finance.required"), trigger: "change" }],
  method: [{ required: true, message: t("finance.required"), trigger: "blur" }],
  conversionNote: [
    {
      required: form.actualCurrency !== "CNY",
      whitespace: true,
      message: t("finance.required"),
      trigger: "blur"
    }
  ],
  voucher: [{ required: true, message: t("finance.invalidVoucher"), trigger: "change" }]
}));
function select(event: Event) {
  const selected = (event.target as HTMLInputElement).files?.[0];
  file.value = undefined;
  form.voucher = "";
  if (
    !selected ||
    selected.size > 10 * 1024 * 1024 ||
    !["image/png", "image/jpeg", "image/webp", "application/pdf"].includes(selected.type)
  ) {
    errors.value.voucher = t("finance.invalidVoucher");
    return;
  }
  errors.value.voucher = "";
  file.value = selected;
  form.voucher = selected.name;
  void formRef.value?.validateField("voucher");
}
async function submit() {
  if (
    busy.value ||
    !props.withdrawal ||
    props.withdrawal.paused ||
    !(await formRef.value?.validate().catch(() => false))
  )
    return;
  busy.value = true;
  try {
    if (!file.value) return;
    const uploaded = await uploadWithdrawalVoucher(file.value);
    await payWithdrawal(props.withdrawal.id, { ...form, voucher: uploaded.fileId }, key);
    ElMessage.success(t("finance.saved"));
    emit("saved");
    emit("update:modelValue", false);
  } catch (error) {
    errors.value = serverFieldErrors(error, Object.keys(form));
    notifyError(error);
  } finally {
    busy.value = false;
  }
}
</script>
<template>
  <el-dialog
    :model-value="modelValue"
    :title="t('finance.externalPayment')"
    width="min(720px, 94vw)"
    append-to-body
    :close-on-click-modal="false"
    :close-on-press-escape="!busy"
    :show-close="!busy"
    @update:model-value="emit('update:modelValue', $event)"
  >
    <div v-if="withdrawal" data-test="payment-dialog">
      <el-alert :title="t('finance.externalNote')" type="warning" :closable="false" />
      <el-descriptions :column="1" border
        ><el-descriptions-item :label="t('finance.approvedCny')"
          >{{ withdrawal.amount }} CNY</el-descriptions-item
        ><el-descriptions-item :label="t('finance.recipient')"
          >{{ withdrawal.account.type }} · {{ withdrawal.account.account }} ·
          {{ withdrawal.account.currency }} · {{ withdrawal.account.holder }}
          {{ withdrawal.account.network }}</el-descriptions-item
        ></el-descriptions
      >
      <el-form
        ref="formRef"
        :model="form"
        :rules="rules"
        label-position="top"
        class="payment-form"
        @submit.prevent="submit"
      >
        <el-form-item
          :label="t('finance.actualAmount')"
          prop="actualAmount"
          :error="errors.actualAmount"
          ><el-input v-model="form.actualAmount" data-test="pay-actual-amount"
        /></el-form-item>
        <el-form-item :label="t('finance.actualCurrency')" prop="actualCurrency"
          ><el-input v-model="form.actualCurrency" disabled
        /></el-form-item>
        <el-form-item :label="t('finance.writeOff')" prop="amountCny" :error="errors.amountCny"
          ><el-input v-model="form.amountCny" data-test="pay-writeoff"
        /></el-form-item>
        <el-form-item :label="t('finance.paidAt')" prop="paidAt" :error="errors.paidAt"
          ><el-date-picker
            v-model="form.paidAt"
            type="datetime"
            value-format="YYYY-MM-DDTHH:mm:ssZ"
            data-test="pay-time"
        /></el-form-item>
        <el-form-item :label="t('finance.method')" prop="method" :error="errors.method"
          ><el-input v-model="form.method" maxlength="80" data-test="pay-method"
        /></el-form-item>
        <el-form-item
          :label="t('finance.conversionNote')"
          prop="conversionNote"
          :error="errors.conversionNote"
          ><el-input
            v-model="form.conversionNote"
            type="textarea"
            maxlength="500"
            show-word-limit
            data-test="pay-conversion"
        /></el-form-item>
        <el-form-item
          :label="t('finance.financeNote')"
          prop="financeNote"
          :error="errors.financeNote"
          ><el-input v-model="form.financeNote" type="textarea" maxlength="500" show-word-limit
        /></el-form-item>
        <el-form-item :label="t('finance.voucher')" prop="voucher" :error="errors.voucher"
          ><label class="file-label"
            >{{ t("finance.uploadVoucher")
            }}<input
              type="file"
              accept="image/png,image/jpeg,image/webp,application/pdf"
              data-test="pay-voucher"
              @change="select" /></label
        ></el-form-item>
      </el-form>
    </div>
    <template #footer
      ><el-button :disabled="busy" @click="emit('update:modelValue', false)">{{
        t("finance.cancel")
      }}</el-button
      ><el-button type="primary" :loading="busy" data-test="pay-submit" @click="submit">{{
        t("finance.confirm")
      }}</el-button></template
    >
  </el-dialog>
</template>
<style scoped>
.payment-form {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 0 var(--tcrm-space-4);
  margin-top: var(--tcrm-space-4);
}
.file-label {
  display: flex;
  flex-direction: column;
  gap: var(--tcrm-space-2);
  overflow-wrap: anywhere;
}
.file-label input {
  max-width: 100%;
}
@media (max-width: 767px) {
  .payment-form {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
