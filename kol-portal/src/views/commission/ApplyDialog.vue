<script setup lang="ts">
import { computed, reactive, ref, watch } from "vue";
import { useI18n } from "vue-i18n";
import { ElMessage, type FormInstance, type FormRules } from "element-plus";
import { applyWithdrawal, getMyAccounts, uploadRecipientQr } from "@/api/commission/finance";
import type { AccountSnapshot, Withdrawal } from "@/api/commission/finance-types";
import { cents, validMoney } from "@/utils/finance";
import { checkQrFile, ALNUM_ACCOUNT } from "@/utils/payout-account";
import { useSubmission } from "@/utils/useSubmission";
const props = defineProps<{
  modelValue: boolean;
  minimum: string;
  available: string;
  previous: Withdrawal | null;
}>();
const emit = defineEmits<{ "update:modelValue": [value: boolean]; saved: [] }>();
const { t } = useI18n();
const formRef = ref<FormInstance>();
const submission = useSubmission();
const accounts = ref<AccountSnapshot[]>([]);
const source = ref("saved");
const selectedAccount = ref(0);
const qr = ref<File>();
const qrError = ref("");
const key = ref("");
const accountError = ref(false);
const form = reactive({
  amount: "",
  account: {
    type: "PAYPAL",
    account: "",
    holder: "",
    currency: "USD",
    network: ""
  } as AccountSnapshot,
  note: ""
});
function useSaved() {
  if (source.value === "saved" && accounts.value[selectedAccount.value])
    form.account = { ...accounts.value[selectedAccount.value] };
}
watch(source, useSaved);
watch(selectedAccount, useSaved);
watch(
  () => props.modelValue,
  async open => {
    if (!open) return;
    form.amount = props.previous?.amount ?? "";
    form.note = props.previous?.note ?? "";
    key.value = crypto.randomUUID();
    submission.validation.value = "";
    submission.failure.value = null;
    accountError.value = false;
    accounts.value = [];
    selectedAccount.value = 0;
    qr.value = undefined;
    qrError.value = "";
    source.value = "saved";
    form.account = { type: "PAYPAL", account: "", holder: "", currency: "USD", network: "" };
    try {
      accounts.value = await getMyAccounts();
      if (props.previous) {
        form.account = { ...props.previous.account };
        source.value = "manual";
      } else useSaved();
    } catch {
      accountError.value = true;
      source.value = "manual";
    }
    formRef.value?.clearValidate();
  }
);
function changeType() {
  form.account.currency = form.account.type === "USDT" ? "USDT" : "USD";
  form.account.network = form.account.type === "USDT" ? "TRC20" : "";
  form.account.account = "";
  form.account.id = undefined;
  form.account.qrFileId = undefined;
  qr.value = undefined;
  qrError.value = "";
}
const rules = computed<FormRules>(() => ({
  amount: [
    {
      validator: (_rule, value, done) => {
        const error = !validMoney(value)
          ? "validAmount"
          : cents(value) < cents(props.minimum)
            ? "belowMinimum"
            : cents(value) > cents(props.available)
              ? "insufficient"
              : "";
        done(error ? new Error(t(`finance.${error}`)) : undefined);
      },
      trigger: "blur"
    }
  ],
  "account.account": [
    {
      validator: (_rule, value, done) => {
        const valid =
          form.account.type === "PAYPAL"
            ? /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(value)
            : ALNUM_ACCOUNT.test(value);
        done(
          valid
            ? undefined
            : new Error(
                t(
                  form.account.type === "PAYPAL" ? "finance.invalidEmail" : "finance.invalidAddress"
                )
              )
        );
      },
      trigger: "blur"
    }
  ],
  "account.holder": [
    { required: true, whitespace: true, message: t("finance.required"), trigger: "blur" }
  ],
  "account.network": [
    {
      validator: (_rule, value, done) =>
        done(
          form.account.type === "USDT" && !value
            ? new Error(t("finance.invalidAddress"))
            : undefined
        ),
      trigger: "change"
    }
  ]
}));
function selectQr(event: Event) {
  const file = (event.target as HTMLInputElement).files?.[0];
  qr.value = undefined;
  qrError.value = "";
  if (file && checkQrFile(file) !== "ok") {
    qrError.value = t("finance.invalidQr");
    return;
  }
  qr.value = file;
}
async function submit() {
  if (!(await formRef.value?.validate().catch(() => false))) return;
  await submission.submit(async () => {
    if (qrError.value) return;
    if (qr.value) {
      const uploaded = await uploadRecipientQr(qr.value);
      form.account.qrFileId = uploaded.fileId;
    }
    await applyWithdrawal({
      payoutAccountId: source.value === "saved" ? form.account.id : undefined,
      amount: form.amount,
      account: { ...form.account },
      note: form.note,
      previousId: props.previous?.id,
      idempotencyKey: key.value
    });
    ElMessage.success(t("finance.saved"));
    emit("saved");
    emit("update:modelValue", false);
  });
}
</script>
<template>
  <el-dialog
    class="withdrawal-apply-dialog"
    :model-value="modelValue"
    :title="t(previous ? 'finance.resubmit' : 'finance.apply')"
    width="min(680px, 94vw)"
    top="4vh"
    :close-on-click-modal="false"
    :show-close="!submission.busy.value"
    @update:model-value="emit('update:modelValue', $event)"
  >
    <div data-test="creator-apply-dialog">
      <el-alert
        :title="t('finance.thresholdNote', { minimum, available })"
        :closable="false"
      /><el-alert
        v-if="accountError"
        :title="t('finance.loadFailed')"
        type="error"
        :closable="false"
      />
      <el-form
        ref="formRef"
        :model="form"
        :rules="rules"
        scroll-to-error
        :scroll-into-view-options="{ block: 'nearest' }"
        label-position="top"
        @submit.prevent="submit"
      >
        <el-form-item :label="t('finance.amount')" prop="amount"
          ><el-input v-model="form.amount" data-test="apply-amount"
            ><template #append>CNY</template></el-input
          ></el-form-item
        >
        <el-form-item :label="t('finance.recipient')"
          ><el-radio-group v-model="source"
            ><el-radio value="saved" :disabled="!accounts.length">{{
              t("finance.savedAccount")
            }}</el-radio
            ><el-radio value="manual">{{ t("finance.manualAccount") }}</el-radio></el-radio-group
          ></el-form-item
        >
        <el-form-item v-if="source === 'saved'" :label="t('finance.chooseAccount')"
          ><el-select v-model="selectedAccount" data-test="apply-saved-account"
            ><el-option
              v-for="(account, index) in accounts"
              :key="account.id ?? index"
              :value="index"
              :label="`${account.type} · ${account.account}`" /></el-select
        ></el-form-item>
        <el-form-item :label="t('finance.accountType')" prop="account.type"
          ><el-select
            v-model="form.account.type"
            :disabled="source === 'saved'"
            data-test="apply-account-type"
            @change="changeType"
            ><el-option label="PayPal" value="PAYPAL" /><el-option
              label="USDT"
              value="USDT" /></el-select
        ></el-form-item>
        <el-form-item :label="t('finance.account')" prop="account.account"
          ><el-input
            v-model="form.account.account"
            :disabled="source === 'saved'"
            maxlength="254"
            data-test="apply-account"
        /></el-form-item>
        <el-form-item :label="t('finance.holder')" prop="account.holder"
          ><el-input
            v-model="form.account.holder"
            :disabled="source === 'saved'"
            maxlength="100"
            data-test="apply-holder"
        /></el-form-item>
        <el-form-item :label="t('finance.currency')" prop="account.currency"
          ><el-select
            v-model="form.account.currency"
            :disabled="source === 'saved' || form.account.type === 'USDT'"
            ><el-option
              v-for="currency in form.account.type === 'USDT'
                ? ['USDT']
                : ['USD', 'CNY', 'EUR', 'GBP']"
              :key="currency"
              :label="currency"
              :value="currency" /></el-select
        ></el-form-item>
        <el-form-item
          v-if="form.account.type === 'USDT'"
          :label="t('finance.network')"
          prop="account.network"
          ><el-select
            v-model="form.account.network"
            :disabled="source === 'saved'"
            data-test="apply-network"
            ><el-option
              v-for="network in ['TRC20', 'ERC20', 'BEP20', 'SOL', 'TON']"
              :key="network"
              :label="network"
              :value="network" /></el-select
        ></el-form-item>
        <el-form-item
          v-if="form.account.type === 'USDT' && source === 'manual'"
          :label="t('finance.optionalQr')"
          :error="qrError"
          ><input type="file" accept="image/png,image/jpeg" data-test="apply-qr" @change="selectQr"
        /></el-form-item>
        <el-form-item :label="t('finance.note')" prop="note"
          ><el-input
            v-model="form.note"
            type="textarea"
            maxlength="500"
            show-word-limit
            data-test="apply-note"
        /></el-form-item>
      </el-form>
      <p>{{ t("finance.accountNote") }}</p>
      <el-alert
        v-if="submission.errorText.value"
        :title="submission.errorText.value"
        type="error"
        :closable="false"
        data-test="apply-error"
      />
    </div>
    <template #footer
      ><el-button :disabled="submission.busy.value" @click="emit('update:modelValue', false)">{{
        t("finance.cancel")
      }}</el-button
      ><el-button
        type="primary"
        :loading="submission.busy.value"
        data-test="apply-submit"
        @click="submit"
        >{{ t("finance.confirm") }}</el-button
      ></template
    >
  </el-dialog>
</template>
<style scoped>
/* Element Plus teleports the dialog root without this component's scope attribute. */
:global(.withdrawal-apply-dialog.el-dialog) {
  display: flex;
  flex-direction: column;
  max-height: 92dvh;
}
:global(.withdrawal-apply-dialog .el-dialog__body) {
  flex: 1 1 auto;
  min-height: 0;
  overflow-y: auto;
}
:global(.withdrawal-apply-dialog .el-dialog__header),
:global(.withdrawal-apply-dialog .el-dialog__footer) {
  flex-shrink: 0;
}
.el-form {
  margin-top: var(--tcrm-space-4);
}
p {
  color: var(--el-text-color-secondary);
  overflow-wrap: anywhere;
}
:deep(.el-radio-group) {
  display: flex;
  flex-direction: column;
  align-items: start;
}
@media (max-width: 767px) {
  :deep(.el-radio) {
    height: auto;
    min-height: 44px;
    white-space: normal;
  }
  :deep(.el-radio__label) {
    white-space: normal;
  }
}
</style>
