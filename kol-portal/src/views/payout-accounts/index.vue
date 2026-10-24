<script setup lang="ts">
import { CopyDocument } from "@element-plus/icons-vue";
import { computed, onUnmounted, ref } from "vue";
import { useI18n } from "vue-i18n";
import { ElMessage, ElMessageBox } from "element-plus";
import {
  disablePayoutAccount,
  downloadQrFile,
  listPayoutAccounts,
  setDefaultPayoutAccount,
  type PayoutAccountVO
} from "@/api/payout-account";
import { translate } from "@/locales";
import { copyText } from "@/utils/copy";
import { useSubmission } from "@/utils/useSubmission";
import PayoutAccountDialog from "./PayoutAccountDialog.vue";

defineOptions({ name: "PayoutAccountsPage" });

const { t, locale } = useI18n();

const accounts = ref<PayoutAccountVO[]>([]);
const loadRequest = useSubmission();
const listError = computed(() => (accounts.value.length === 0 ? loadRequest.errorText.value : ""));
const loading = computed(() => loadRequest.busy.value);

const dialogVisible = ref(false);
const editing = ref<PayoutAccountVO | null>(null);
const actingId = ref<string | null>(null);
const qrViewUrl = ref<string | null>(null);
const qrViewLoading = ref(false);

function load() {
  return loadRequest.submit(async () => {
    accounts.value = await listPayoutAccounts();
  });
}

function openCreate() {
  editing.value = null;
  dialogVisible.value = true;
}

function openEdit(account: PayoutAccountVO) {
  editing.value = account;
  dialogVisible.value = true;
}

/** Only PENDING and REJECTED accounts may be edited again (task 15b §4.1.4). */
function canEdit(account: PayoutAccountVO): boolean {
  return account.status === "PENDING" || account.status === "REJECTED";
}

/** Only an APPROVED account that is not already the default can become the default. */
function canSetDefault(account: PayoutAccountVO): boolean {
  return account.status === "APPROVED" && !account.defaultAccount;
}

async function setDefault(account: PayoutAccountVO) {
  actingId.value = account.id;
  try {
    await setDefaultPayoutAccount(account.id);
    ElMessage.success(t("payoutAccounts.defaultSet"));
    await load();
  } catch (error) {
    announce(error);
  } finally {
    actingId.value = null;
  }
}

async function disable(account: PayoutAccountVO) {
  try {
    await ElMessageBox.confirm(t("payoutAccounts.disableConfirm"), t("payoutAccounts.disable"), {
      type: "warning",
      confirmButtonText: t("payoutAccounts.disable"),
      cancelButtonText: t("common.cancel")
    });
  } catch {
    return; // cancelled
  }
  actingId.value = account.id;
  try {
    await disablePayoutAccount(account.id);
    ElMessage.success(t("payoutAccounts.disabled"));
    await load();
  } catch (error) {
    announce(error);
  } finally {
    actingId.value = null;
  }
}

/** Shows an API failure in the current language; nothing extra for the ones already displayed. */
function announce(error: unknown): void {
  if (error instanceof Error && "displayMessage" in error) {
    const apiError = error as { displayMessage: string };
    ElMessage.error(apiError.displayMessage);
  } else {
    ElMessage.error(translate("errors.generic"));
  }
}

/** Loads the QR image as a blob (token stays in the header) and shows it full size. */
async function viewQr(account: PayoutAccountVO) {
  if (!account.qrFileId) return;
  actingId.value = account.id;
  qrViewLoading.value = true;
  try {
    const blob = await downloadQrFile(account.qrFileId);
    if (qrViewUrl.value) URL.revokeObjectURL(qrViewUrl.value);
    qrViewUrl.value = URL.createObjectURL(blob);
  } catch (error) {
    announce(error);
  } finally {
    qrViewLoading.value = false;
    actingId.value = null;
  }
}

function closeQrView(): void {
  if (qrViewUrl.value) URL.revokeObjectURL(qrViewUrl.value);
  qrViewUrl.value = null;
}

function statusText(status: PayoutAccountVO["status"]): string {
  return t(`payoutAccounts.status.${status}`);
}

function typeText(account: PayoutAccountVO): string {
  if (account.accountType === "USDT")
    return t("payoutAccounts.type.USDT") + " · " + t(`payoutAccounts.network.${account.network}`);
  return t("payoutAccounts.type.PAYPAL") + (account.currency ? " · " + account.currency : "");
}

/** Copy button for the full account; only its owner ever sees this page. */
async function copyAccount(account: PayoutAccountVO) {
  const copied = await copyText(account.account);
  if (copied) ElMessage.success(t("payoutAccounts.copied"));
}

function formatTime(value: string): string {
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return "";
  return new Intl.DateTimeFormat(locale.value, {
    year: "numeric",
    month: "short",
    day: "numeric",
    hour: "2-digit",
    minute: "2-digit"
  }).format(date);
}

function onSaved() {
  ElMessage.success(t("payoutAccounts.submitted"));
  void load();
}

/** The account was approved by someone else while being edited: close, explain, refresh. */
function onConflict() {
  dialogVisible.value = false;
  ElMessage.warning(translate("errors.PAYOUT_ACCOUNT_LOCKED"));
  void load();
}

onUnmounted(closeQrView);
load();
</script>

<template>
  <section class="payout">
    <div class="payout__head">
      <h1>{{ t("payoutAccounts.title") }}</h1>
      <el-button type="primary" data-test="create" @click="openCreate">
        {{ t("payoutAccounts.create") }}
      </el-button>
    </div>
    <el-alert
      v-if="listError"
      role="alert"
      type="error"
      :closable="false"
      :title="listError"
      data-test="list-error"
    />
    <el-button v-if="listError" :loading="loading" @click="load">{{
      t("account.retry")
    }}</el-button>

    <div v-loading="loading" class="payout__list">
      <el-empty v-if="!loading && accounts.length === 0" :description="t('payoutAccounts.empty')">
        <el-button type="primary" data-test="empty-create" @click="openCreate">{{
          t("payoutAccounts.create")
        }}</el-button>
      </el-empty>

      <el-card
        v-for="account in accounts"
        :key="account.id"
        class="payout__card"
        shadow="never"
        :data-test="`account-${account.id}`"
      >
        <div class="payout__card-head">
          <span class="payout__type" data-test="type">{{ typeText(account) }}</span>
          <span class="payout__tags">
            <el-tag
              size="small"
              :type="
                account.status === 'APPROVED'
                  ? 'success'
                  : account.status === 'REJECTED'
                    ? 'danger'
                    : 'warning'
              "
              data-test="status"
            >
              {{ statusText(account.status) }}
            </el-tag>
            <el-tag v-if="account.defaultAccount" size="small" data-test="default-tag">
              {{ t("payoutAccounts.defaultTag") }}
            </el-tag>
          </span>
        </div>

        <dl class="payout__fields">
          <dt>{{ t("payoutAccounts.account") }}</dt>
          <dd class="payout__account">
            <span class="payout__account-value" data-test="account">{{ account.account }}</span>
            <el-button
              link
              type="primary"
              :aria-label="t('common.copy')"
              data-test="copy-account"
              @click="copyAccount(account)"
            >
              <el-icon><CopyDocument /></el-icon>
            </el-button>
          </dd>
          <template v-if="account.accountType === 'PAYPAL'">
            <dt>{{ t("payoutAccounts.holder") }}</dt>
            <dd>{{ account.holderName }}</dd>
          </template>
          <dt>{{ t("payoutAccounts.submittedAt") }}</dt>
          <dd>{{ formatTime(account.lastSubmittedAt) }}</dd>
        </dl>

        <el-alert
          v-if="account.status === 'REJECTED' && account.rejectReason"
          class="payout__reject"
          type="error"
          :closable="false"
          :title="t('payoutAccounts.rejectReason')"
          :description="account.rejectReason"
          data-test="reject-reason"
        />

        <div class="payout__actions">
          <el-button
            v-if="account.qrFileId"
            link
            type="primary"
            data-test="view-qr"
            :loading="qrViewLoading && actingId === account.id"
            @click="viewQr(account)"
          >
            {{ t("payoutAccounts.viewQr") }}
          </el-button>
          <el-button
            v-if="canEdit(account)"
            link
            type="primary"
            data-test="edit"
            @click="openEdit(account)"
          >
            {{ t("common.edit") }}
          </el-button>
          <el-button
            v-if="canSetDefault(account)"
            link
            type="primary"
            data-test="set-default"
            :loading="actingId === account.id"
            @click="setDefault(account)"
          >
            {{ t("payoutAccounts.setDefault") }}
          </el-button>
          <el-button
            link
            type="danger"
            data-test="disable"
            :loading="actingId === account.id"
            @click="disable(account)"
          >
            {{ t("payoutAccounts.disable") }}
          </el-button>
        </div>
      </el-card>
    </div>

    <PayoutAccountDialog
      v-model="dialogVisible"
      :account="editing"
      @saved="onSaved"
      @conflict="onConflict"
    />

    <el-dialog
      :model-value="qrViewUrl !== null"
      :title="t('payoutAccounts.qrTitle')"
      width="min(420px, calc(100vw - 24px))"
      @update:model-value="closeQrView"
      @closed="closeQrView"
    >
      <el-image
        v-if="qrViewUrl"
        class="payout__qr-view"
        :src="qrViewUrl"
        :preview-src-list="[qrViewUrl]"
        fit="contain"
        alt=""
      />
    </el-dialog>
  </section>
</template>

<style scoped>
.payout {
  display: flex;
  flex-direction: column;
  gap: var(--tcrm-space-4);
}
.payout__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--tcrm-space-3);
  flex-wrap: wrap;
}
.payout__head h1 {
  margin: 0;
  font-size: var(--tcrm-font-size-title);
  line-height: var(--tcrm-line-height-title);
}
.payout__limit {
  margin: calc(var(--tcrm-space-2) * -1) 0 0;
  color: var(--el-text-color-secondary);
}
.payout__list {
  display: flex;
  flex-direction: column;
  gap: var(--tcrm-space-3);
  min-height: 120px;
}
.payout__card-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--tcrm-space-2);
  flex-wrap: wrap;
  margin-bottom: var(--tcrm-space-2);
}
.payout__type {
  font-weight: 600;
}
.payout__tags {
  display: inline-flex;
  gap: var(--tcrm-space-2);
}
.payout__fields {
  display: grid;
  grid-template-columns: minmax(110px, auto) minmax(0, 1fr);
  gap: var(--tcrm-space-2) var(--tcrm-space-4);
  margin: 0;
}
.payout__fields dt {
  color: var(--el-text-color-secondary);
}
.payout__fields dd {
  margin: 0;
  min-width: 0;
}
.payout__account {
  display: flex;
  align-items: flex-start;
  gap: var(--tcrm-space-1);
}
.payout__account-value {
  overflow-wrap: anywhere;
  font-family: var(--tcrm-font-sans);
}
.payout__reject {
  margin-top: var(--tcrm-space-3);
}
.payout__actions {
  display: flex;
  flex-wrap: wrap;
  gap: var(--tcrm-space-1);
  margin-top: var(--tcrm-space-3);
}
.payout__qr-view {
  width: 100%;
  max-height: 70vh;
}
@media (max-width: 480px) {
  .payout__head .el-button {
    width: 100%;
  }
}
</style>
