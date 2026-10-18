<script setup lang="ts">
import { Close, Plus } from "@element-plus/icons-vue";
import { nextTick, ref, watch } from "vue";
import { useI18n } from "vue-i18n";
import {
  createPayoutAccount,
  downloadQrFile,
  updatePayoutAccount,
  uploadQrFile
} from "@/api/payout-account";
import type { PayoutAccountVO } from "@/api/payout-account";
import { ApiError } from "@/utils/http/error";
import { checkQrFile, USDT_NETWORKS, type UsdtNetwork } from "@/utils/payout-account";
import { useSubmission } from "@/utils/useSubmission";

defineOptions({ name: "PayoutAccountDialog" });

const props = defineProps<{
  modelValue: boolean;
  /** Null creates a new account; a value resubmits that PENDING or REJECTED account. */
  account: PayoutAccountVO | null;
}>();

const emit = defineEmits<{
  (event: "update:modelValue", value: boolean): void;
  (event: "saved"): void;
  (event: "conflict"): void;
}>();

const { t } = useI18n();
const { busy, validation, failure, errorText, submit } = useSubmission();

/** Currencies in the picker, USD first because it is the default. */
const PAYPAL_CURRENCIES = ["USD", "EUR", "GBP", "CNY"] as const;

const accountType = ref<"" | "PAYPAL" | "USDT">("");
const accountValue = ref("");
const holderName = ref("");
/** PayPal currencies offered in the picker (task 25b §3.6); USD is the default. */
const currency = ref("USD");
const network = ref<"" | UsdtNetwork>("");
const qrFileId = ref<string | null>(null);
const qrPreviewUrl = ref<string | null>(null);
const qrInput = ref<HTMLInputElement | null>(null);
const uploadingQr = ref(false);

watch(
  () => props.modelValue,
  open => {
    if (open) resetForm();
  },
  { immediate: true }
);

function resetForm() {
  clearQr();
  validation.value = "";
  failure.value = null;
  accountType.value = props.account?.accountType ?? "";
  accountValue.value = props.account?.account ?? "";
  holderName.value = props.account?.holderName ?? "";
  currency.value = props.account?.currency || "USD";
  network.value = props.account?.network ?? "";
  qrFileId.value = props.account?.qrFileId ?? null;
  if (props.account?.qrFileId) void loadExistingQr(props.account.qrFileId);
}

/** The QR already attached to an edited account is shown again from the file endpoint. */
async function loadExistingQr(fileId: string) {
  try {
    const blob = await downloadQrFile(fileId);
    if (qrFileId.value === fileId) qrPreviewUrl.value = URL.createObjectURL(blob);
  } catch {
    // The thumbnail is nice to have; saving keeps the file reference either way.
  }
}

function clearQr() {
  if (qrPreviewUrl.value) URL.revokeObjectURL(qrPreviewUrl.value);
  qrPreviewUrl.value = null;
  qrFileId.value = null;
  if (qrInput.value) qrInput.value.value = "";
}

async function pickQr(event: Event) {
  const input = event.target as HTMLInputElement;
  const file = input.files?.[0];
  if (!file) return;
  const check = checkQrFile(file);
  if (check !== "ok") {
    validation.value = check === "type" ? "errors.FILE_INVALID" : "errors.FILE_TOO_LARGE";
    input.value = "";
    return;
  }
  uploadingQr.value = true;
  validation.value = "";
  try {
    const result = await uploadQrFile(file);
    clearQr();
    qrFileId.value = result.fileId;
    qrPreviewUrl.value = URL.createObjectURL(file);
  } catch (error) {
    // The upload is not part of the form submit, so its failure is shown like a submit failure.
    if (error instanceof ApiError) {
      failure.value = error;
    } else {
      validation.value = "errors.generic";
    }
  } finally {
    uploadingQr.value = false;
    input.value = "";
  }
}

function validate(): boolean {
  if (accountType.value === "") {
    validation.value = "payoutAccounts.form.typeRequired";
    return false;
  }
  const value = accountValue.value.trim();
  if (value.length < 3 || value.length > 128) {
    validation.value = "payoutAccounts.form.accountRange";
    return false;
  }
  if (accountType.value === "PAYPAL") {
    const holder = holderName.value.trim();
    if (holder.length < 1 || holder.length > 64) {
      validation.value = "payoutAccounts.form.holderRange";
      return false;
    }
    if (!currency.value) {
      validation.value = "payoutAccounts.form.currencyRequired";
      return false;
    }
  } else {
    if (!/^[0-9A-Za-z]{3,128}$/.test(value)) {
      validation.value = "payoutAccounts.form.accountPattern";
      return false;
    }
    if (network.value === "") {
      validation.value = "payoutAccounts.form.networkRequired";
      return false;
    }
  }
  return true;
}

function save() {
  return submit(async () => {
    if (!validate()) return;
    const trimmed = accountValue.value.trim();
    const payload =
      accountType.value === "PAYPAL"
        ? {
            accountType: "PAYPAL" as const,
            account: trimmed,
            holderName: holderName.value.trim(),
            currency: currency.value
          }
        : {
            accountType: "USDT" as const,
            account: trimmed,
            network: network.value as UsdtNetwork,
            ...(qrFileId.value ? { qrFileId: qrFileId.value } : {})
          };
    try {
      if (props.account) await updatePayoutAccount(props.account.id, payload);
      else await createPayoutAccount(payload);
    } catch (error) {
      if (error instanceof ApiError && error.errorCode === "PAYOUT_ACCOUNT_LOCKED") {
        error.handled = true;
        emit("conflict");
        return;
      }
      throw error;
    }
    emit("saved");
    emit("update:modelValue", false);
    await nextTick();
    if (qrPreviewUrl.value) clearQr();
  });
}

function close() {
  emit("update:modelValue", false);
}
</script>

<template>
  <el-dialog
    :model-value="modelValue"
    :title="account ? t('payoutAccounts.editTitle') : t('payoutAccounts.createTitle')"
    width="min(520px, calc(100vw - 24px))"
    :close-on-click-modal="false"
    @update:model-value="close"
    @closed="clearQr"
  >
    <el-alert v-if="errorText" role="alert" type="error" :closable="false" :title="errorText" />
    <el-form label-position="top" novalidate @submit.prevent="save">
      <el-form-item :label="t('payoutAccounts.form.type')">
        <el-radio-group v-model="accountType" data-test="type-input" :disabled="busy">
          <el-radio-button value="PAYPAL">{{ t("payoutAccounts.type.PAYPAL") }}</el-radio-button>
          <el-radio-button value="USDT">{{ t("payoutAccounts.type.USDT") }}</el-radio-button>
        </el-radio-group>
      </el-form-item>

      <el-form-item :label="t('payoutAccounts.form.account')" for="payout-account">
        <el-input
          id="payout-account"
          v-model="accountValue"
          maxlength="128"
          :placeholder="
            accountType === 'PAYPAL'
              ? t('payoutAccounts.form.accountPaypalHint')
              : t('payoutAccounts.form.accountUsdtHint')
          "
          :disabled="busy"
        />
      </el-form-item>

      <el-form-item
        v-if="accountType === 'PAYPAL'"
        :label="t('payoutAccounts.form.currency')"
        for="payout-currency"
      >
        <el-select
          id="payout-currency"
          v-model="currency"
          data-test="currency-input"
          :disabled="busy"
        >
          <el-option v-for="item in PAYPAL_CURRENCIES" :key="item" :label="item" :value="item" />
        </el-select>
      </el-form-item>

      <el-form-item
        v-if="accountType === 'PAYPAL'"
        :label="t('payoutAccounts.holder')"
        for="payout-holder"
      >
        <el-input id="payout-holder" v-model="holderName" maxlength="64" :disabled="busy" />
      </el-form-item>

      <template v-if="accountType === 'USDT'">
        <el-form-item :label="t('payoutAccounts.form.network')">
          <el-select v-model="network" data-test="network-input" :disabled="busy">
            <el-option
              v-for="item in USDT_NETWORKS"
              :key="item"
              :label="t(`payoutAccounts.network.${item}`)"
              :value="item"
            />
          </el-select>
        </el-form-item>

        <el-form-item :label="t('payoutAccounts.form.qr')">
          <div v-if="qrPreviewUrl" class="qr-current" data-test="qr-preview">
            <el-image
              :src="qrPreviewUrl"
              :preview-src-list="[qrPreviewUrl]"
              fit="contain"
              class="qr-current__image"
              alt=""
            />
            <el-button link type="danger" data-test="qr-remove" @click="clearQr">
              <el-icon><Close /></el-icon>
              {{ t("payoutAccounts.form.qrRemove") }}
            </el-button>
          </div>
          <div v-else>
            <el-button
              data-test="qr-pick"
              :loading="uploadingQr"
              :disabled="busy"
              @click="qrInput?.click()"
            >
              <el-icon v-if="!uploadingQr"><Plus /></el-icon>
              {{ t("payoutAccounts.form.qrAdd") }}
            </el-button>
            <p class="qr-hint">{{ t("payoutAccounts.form.qrHint") }}</p>
          </div>
          <input
            ref="qrInput"
            type="file"
            accept="image/png,image/jpeg"
            class="qr-input"
            data-test="qr-input"
            @change="pickQr"
          />
        </el-form-item>
      </template>

      <div class="dialog-actions">
        <el-button native-type="button" :disabled="busy" @click="close">
          {{ t("common.cancel") }}
        </el-button>
        <el-button native-type="submit" type="primary" :loading="busy" :disabled="busy">
          {{ t("payoutAccounts.form.submit") }}
        </el-button>
      </div>
    </el-form>
  </el-dialog>
</template>

<style scoped>
.el-alert {
  margin-bottom: 16px;
}
.el-select {
  width: 100%;
}
.dialog-actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}
.qr-current {
  display: flex;
  align-items: flex-start;
  gap: 12px;
}
.qr-current__image {
  width: 96px;
  height: 96px;
  border: 1px solid var(--el-border-color-light);
  border-radius: 8px;
}
.qr-hint {
  margin: 8px 0 0;
  color: var(--el-text-color-secondary);
  font-size: 13px;
}
.qr-input {
  display: none;
}
</style>
