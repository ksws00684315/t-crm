<script setup lang="ts">
import NameCode from "@/components/NameCode.vue";
import { computed, onMounted, reactive, ref } from "vue";
import { useI18n } from "vue-i18n";
import { changePassword, getMe, updateMe } from "@/api/auth";
import {
  createPayoutAccount,
  listPayoutAccounts,
  updatePayoutAccount,
  uploadQrFile,
  type PayoutAccountPayload,
  type PayoutAccountVO
} from "@/api/payout-account";
import { useSessionStore } from "@/stores/session";
import { validPassword } from "@/utils/auth";
import { USDT_NETWORKS, type UsdtNetwork } from "@/utils/payout-account";
import { useSubmission } from "@/utils/useSubmission";

defineOptions({ name: "AccountPage" });
const { t } = useI18n();
const session = useSessionStore();
const profile = reactive({ name: "", primaryPlatform: "", primaryHandle: "" });
const payout = reactive({
  type: "PAYPAL" as "PAYPAL" | "USDT",
  account: "",
  holderName: "",
  currency: "USD",
  network: "TRC20" as UsdtNetwork,
  qrFileId: ""
});
const accounts = ref<PayoutAccountVO[]>([]);
const selectedAccountId = ref("");
const qrUploading = ref(false);
const oldPassword = ref("");
const newPassword = ref("");
const confirm = ref("");
const profileSaved = ref(false);
const payoutSaved = ref(false);
const passwordChanged = ref(false);
const profileRequest = useSubmission();
const payoutRequest = useSubmission();
const passwordRequest = useSubmission();
const profileBusy = profileRequest.busy;
const profileValidation = profileRequest.validation;
const payoutBusy = payoutRequest.busy;
const payoutValidation = payoutRequest.validation;
const passwordBusy = passwordRequest.busy;
const passwordValidation = passwordRequest.validation;
const profileError = profileRequest.errorText;
const payoutError = payoutRequest.errorText;
const passwordError = passwordRequest.errorText;
const profileLoading = profileRequest.busy;
const paypalCurrencies = ["USD", "EUR", "GBP", "CNY"];
const systemId = computed(() => session.kol?.systemId || session.kol?.kolCode || "—");
const currentAccount = computed(() =>
  accounts.value.find(item => item.id === selectedAccountId.value)
);
const platforms = ["TikTok", "Instagram", "YouTube", "X", "Facebook", "Other"];

function syncAccount(account?: PayoutAccountVO) {
  payout.type = account?.accountType ?? "PAYPAL";
  payout.account = account?.account ?? "";
  payout.holderName = account?.holderName ?? "";
  payout.currency = account?.currency ?? "USD";
  payout.network = (account?.network as UsdtNetwork | null) ?? "TRC20";
  payout.qrFileId = account?.qrFileId ?? "";
  selectedAccountId.value = account?.id ?? "";
}
function load() {
  return profileRequest.submit(async () => {
    const [me, paymentAccounts] = await Promise.all([getMe(), listPayoutAccounts()]);
    session.updateProfile(me);
    profile.name = me.name;
    profile.primaryPlatform = me.primaryPlatform ?? "";
    profile.primaryHandle = me.primaryHandle ?? "";
    accounts.value = paymentAccounts;
    syncAccount(
      paymentAccounts.find(item => item.defaultAccount) ??
        paymentAccounts.find(item => item.status !== "DISABLED")
    );
  });
}
onMounted(load);
function selectType(value: "PAYPAL" | "USDT") {
  const match = accounts.value.find(
    item => item.accountType === value && item.status !== "DISABLED"
  );
  syncAccount(match);
  payout.type = value;
}
function selectAccount(id: string) {
  const account = accounts.value.find(item => item.id === id);
  if (account) syncAccount(account);
}
function saveProfile() {
  profileSaved.value = false;
  const name = profile.name.trim();
  const primaryPlatform = profile.primaryPlatform.trim();
  const primaryHandle = profile.primaryHandle.trim();
  if (!name || name.length > 64 || primaryPlatform.length > 32 || primaryHandle.length > 128) {
    profileRequest.validation.value = "errors.field";
    return;
  }
  if (Boolean(primaryPlatform) !== Boolean(primaryHandle)) {
    profileRequest.validation.value = "register.socialPairRequired";
    return;
  }
  return profileRequest.submit(async () => {
    const updated = await updateMe({
      name,
      primaryPlatform: primaryPlatform || null,
      primaryHandle: primaryHandle || null
    });
    session.updateProfile(updated);
    profileSaved.value = true;
  });
}
async function uploadQr(file: File | undefined) {
  if (!file) return;
  qrUploading.value = true;
  try {
    payout.qrFileId = (await uploadQrFile(file)).fileId;
  } catch {
    payoutRequest.validation.value = "errors.generic";
  } finally {
    qrUploading.value = false;
  }
}
function savePayout() {
  payoutSaved.value = false;
  if (!payout.account.trim() || (payout.type === "PAYPAL" && !payout.holderName.trim())) {
    payoutRequest.validation.value = "errors.field";
    return;
  }
  return payoutRequest.submit(async () => {
    const payload: PayoutAccountPayload =
      payout.type === "PAYPAL"
        ? {
            accountType: "PAYPAL",
            account: payout.account.trim(),
            holderName: payout.holderName.trim(),
            currency: payout.currency
          }
        : {
            accountType: "USDT",
            account: payout.account.trim(),
            network: payout.network,
            ...(payout.holderName.trim() ? { holderName: payout.holderName.trim() } : {}),
            ...(payout.qrFileId ? { qrFileId: payout.qrFileId } : {})
          };
    if (currentAccount.value && ["PENDING", "REJECTED"].includes(currentAccount.value.status))
      await updatePayoutAccount(currentAccount.value.id, payload);
    else await createPayoutAccount(payload);
    accounts.value = await listPayoutAccounts();
    syncAccount(
      accounts.value.find(item => item.defaultAccount) ??
        accounts.value.find(item => item.accountType === payout.type)
    );
    payoutSaved.value = true;
  });
}
function savePassword() {
  passwordChanged.value = false;
  return passwordRequest.submit(async () => {
    if (!validPassword(oldPassword.value) || !validPassword(newPassword.value)) {
      passwordRequest.validation.value = "auth.passwordRule";
      return;
    }
    if (newPassword.value !== confirm.value) {
      passwordRequest.validation.value = "auth.passwordMismatch";
      return;
    }
    session.acceptSession(
      await changePassword({ oldPassword: oldPassword.value, newPassword: newPassword.value })
    );
    oldPassword.value = "";
    newPassword.value = "";
    confirm.value = "";
    passwordChanged.value = true;
  });
}
</script>

<template>
  <section class="account-page">
    <h1>{{ t("account.title") }}</h1>
    <el-alert v-if="profileError" type="error" :closable="false" :title="profileError" show-icon />
    <el-button v-if="profileError" :loading="profileLoading" @click="load">{{
      t("account.retry")
    }}</el-button>
    <el-skeleton v-if="profileLoading && !session.kol" :rows="4" animated />
    <template v-else-if="session.kol">
      <NameCode :name="session.kol.name" :code="session.kol.kolCode" />
      <el-card shadow="never" class="section-card">
        <template #header
          ><h2>{{ t("account.personalInfo") }}</h2></template
        >
        <el-alert
          v-if="profileSaved"
          type="success"
          :closable="false"
          :title="t('account.profileSaved')"
        />
        <el-alert
          v-if="profileValidation"
          type="error"
          :closable="false"
          :title="t(profileValidation)"
        />
        <el-form label-position="top" @submit.prevent="saveProfile">
          <div class="form-grid">
            <el-form-item :label="t('account.email')"
              ><el-input :model-value="session.kol.email" readonly disabled
            /></el-form-item>
            <el-form-item :label="t('account.systemId')"
              ><el-input :model-value="systemId" readonly disabled
            /></el-form-item>
            <el-form-item :label="t('account.nickname')"
              ><el-input
                v-model="profile.name"
                maxlength="64"
                show-word-limit
                data-test="profile-name"
            /></el-form-item>
            <el-form-item :label="t('account.primaryPlatform')"
              ><el-select
                v-model="profile.primaryPlatform"
                data-test="profile-platform"
                clearable
                :placeholder="t('register.platformPlaceholder')"
                ><el-option
                  v-for="platform in platforms"
                  :key="platform"
                  :label="t(`register.platforms.${platform}`)"
                  :value="platform" /></el-select
            ></el-form-item>
            <el-form-item :label="t('account.primaryHandle')"
              ><el-input v-model="profile.primaryHandle" maxlength="128" data-test="profile-handle"
            /></el-form-item>
          </div>
          <el-button
            type="primary"
            :loading="profileBusy"
            data-test="profile-save"
            @click="saveProfile"
            >{{ t("common.save") }}</el-button
          >
        </el-form>
      </el-card>

      <el-card shadow="never" class="section-card">
        <template #header
          ><h2>{{ t("account.readOnlyStatus") }}</h2></template
        >
        <dl class="read-only-grid">
          <template v-if="session.kol.tenantName"
            ><dt>{{ t("account.company") }}</dt>
            <dd>{{ session.kol.tenantName }}</dd></template
          >
          <template v-if="session.kol.country"
            ><dt>{{ t("account.country") }}</dt>
            <dd>{{ session.kol.country }}</dd></template
          >
          <template v-if="session.kol.phone"
            ><dt>{{ t("account.phone") }}</dt>
            <dd>{{ session.kol.phone }}</dd></template
          >
          <template v-if="session.kol.whatsapp"
            ><dt>{{ t("account.whatsapp") }}</dt>
            <dd>{{ session.kol.whatsapp }}</dd></template
          >
          <template v-if="session.kol.reviewStatus"
            ><dt>{{ t("account.reviewStatus") }}</dt>
            <dd>{{ t(`account.status.${session.kol.reviewStatus}`) }}</dd></template
          >
          <template v-if="session.kol.loginStatus"
            ><dt>{{ t("account.loginStatus") }}</dt>
            <dd>{{ t(`account.status.${session.kol.loginStatus}`) }}</dd></template
          >
          <template v-if="session.kol.relationStatus"
            ><dt>{{ t("account.relationStatus") }}</dt>
            <dd>{{ t(`account.status.${session.kol.relationStatus}`) }}</dd></template
          >
          <template
            ><dt>{{ t("account.commissionRate") }}</dt>
            <dd>
              {{
                session.kol.commissionRate == null
                  ? t("account.ratePending")
                  : `${session.kol.commissionRate}%`
              }}
            </dd></template
          >
          <template v-if="session.kol.rateEffectiveAt"
            ><dt>{{ t("account.rateEffectiveAt") }}</dt>
            <dd>{{ new Date(session.kol.rateEffectiveAt).toLocaleString() }}</dd></template
          >
        </dl>
      </el-card>

      <el-card shadow="never" class="section-card">
        <template #header
          ><h2>{{ t("account.payoutInfo") }}</h2></template
        >
        <el-alert
          v-if="payoutSaved"
          type="success"
          :closable="false"
          :title="t('account.payoutSaved')"
        />
        <el-alert
          v-if="payoutError || payoutValidation"
          type="error"
          :closable="false"
          :title="payoutError || t(payoutValidation!)"
        />
        <el-form label-position="top" @submit.prevent="savePayout">
          <div class="form-grid">
            <el-form-item :label="t('account.payoutType')"
              ><el-select :model-value="payout.type" @change="selectType"
                ><el-option :label="t('payoutAccounts.type.PAYPAL')" value="PAYPAL" /><el-option
                  :label="t('payoutAccounts.type.USDT')"
                  value="USDT" /></el-select
            ></el-form-item>
            <el-form-item
              v-if="
                accounts.some(
                  item => item.accountType === payout.type && item.status !== 'DISABLED'
                )
              "
              :label="t('account.savedAccount')"
              ><el-select :model-value="selectedAccountId" @change="selectAccount"
                ><el-option
                  v-for="item in accounts.filter(
                    entry => entry.accountType === payout.type && entry.status !== 'DISABLED'
                  )"
                  :key="item.id"
                  :value="item.id"
                  :label="`${item.account} · ${t(`payoutAccounts.status.${item.status}`)}`" /></el-select
            ></el-form-item>
            <el-form-item
              :label="
                payout.type === 'PAYPAL' ? t('account.paypalEmail') : t('account.usdtAddress')
              "
              ><el-input v-model="payout.account" maxlength="128"
            /></el-form-item>
            <el-form-item v-if="payout.type === 'PAYPAL'" :label="t('account.receiverName')"
              ><el-input v-model="payout.holderName" maxlength="64"
            /></el-form-item>
            <el-form-item v-if="payout.type === 'PAYPAL'" :label="t('account.currency')"
              ><el-select v-model="payout.currency"
                ><el-option
                  v-for="currency in paypalCurrencies"
                  :key="currency"
                  :label="currency"
                  :value="currency" /></el-select
            ></el-form-item>
            <el-form-item v-if="payout.type === 'USDT'" :label="t('payoutAccounts.form.network')"
              ><el-select v-model="payout.network"
                ><el-option
                  v-for="network in USDT_NETWORKS"
                  :key="network"
                  :label="t(`payoutAccounts.network.${network}`)"
                  :value="network" /></el-select
            ></el-form-item>
            <el-form-item v-if="payout.type === 'USDT'" :label="t('account.qrCode')"
              ><input
                type="file"
                accept="image/png,image/jpeg"
                :disabled="qrUploading"
                @change="uploadQr(($event.target as HTMLInputElement).files?.[0])"
              /><small>{{ t("payoutAccounts.form.qrHint") }}</small></el-form-item
            >
          </div>
          <div class="actions">
            <el-button type="primary" :loading="payoutBusy || qrUploading" @click="savePayout">{{
              t("common.save")
            }}</el-button
            ><RouterLink to="/payout-accounts">{{ t("account.managePayoutAccounts") }}</RouterLink>
          </div>
        </el-form>
      </el-card>

      <el-card shadow="never" class="section-card password-card">
        <template #header
          ><h2>{{ t("account.changePassword") }}</h2></template
        >
        <el-alert
          v-if="passwordChanged"
          type="success"
          :closable="false"
          :title="t('account.passwordChanged')"
        />
        <el-alert
          v-if="passwordError || passwordValidation"
          type="error"
          :closable="false"
          :title="passwordError || t(passwordValidation!)"
        />
        <p>{{ t("auth.passwordRule") }}</p>
        <el-form data-test="password-form" label-position="top" @submit.prevent="savePassword">
          <div class="form-grid">
            <el-form-item :label="t('account.oldPassword')"
              ><el-input
                v-model="oldPassword"
                id="old-password"
                type="password"
                autocomplete="current-password"
                show-password
            /></el-form-item>
            <el-form-item :label="t('auth.newPassword')"
              ><el-input
                v-model="newPassword"
                id="new-password"
                type="password"
                autocomplete="new-password"
                show-password
            /></el-form-item>
            <el-form-item :label="t('auth.confirmPassword')"
              ><el-input
                v-model="confirm"
                id="confirm-password"
                type="password"
                autocomplete="new-password"
                show-password
            /></el-form-item>
          </div>
          <el-button type="primary" :loading="passwordBusy" @click="savePassword">{{
            t("account.changePassword")
          }}</el-button>
        </el-form>
      </el-card>
    </template>
  </section>
</template>

<style scoped>
.account-page {
  display: flex;
  flex-direction: column;
  gap: var(--tcrm-space-4);
  min-width: 0;
}
h1 {
  margin: 0;
  font-size: var(--tcrm-font-size-title);
  line-height: var(--tcrm-line-height-title);
  font-weight: 600;
}
.section-card :deep(.el-card__header) h2 {
  margin: 0;
  font-size: var(--tcrm-font-size-section);
  line-height: var(--tcrm-line-height-section);
}
.form-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 0 var(--tcrm-space-4);
}
.form-grid :deep(.el-select) {
  width: 100%;
}
.read-only-grid {
  display: grid;
  grid-template-columns: minmax(120px, auto) minmax(0, 1fr);
  gap: var(--tcrm-space-3) var(--tcrm-space-5);
  margin: 0;
}
.read-only-grid dt {
  color: var(--tcrm-color-text-secondary);
}
.read-only-grid dd {
  margin: 0;
  overflow-wrap: anywhere;
}
.actions {
  display: flex;
  align-items: center;
  gap: var(--tcrm-space-4);
}
.actions a {
  color: var(--tcrm-color-primary);
  text-decoration: none;
}
.section-card :deep(.el-alert) {
  margin-bottom: var(--tcrm-space-4);
}
.password-card p,
small {
  color: var(--tcrm-color-text-secondary);
}
@media (max-width: 767px) {
  .form-grid {
    grid-template-columns: 1fr;
  }
  .actions {
    align-items: flex-start;
    flex-direction: column;
  }
}
</style>
