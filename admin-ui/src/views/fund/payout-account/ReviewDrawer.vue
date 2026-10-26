<script setup lang="ts">
import NameCode from "@/components/NameCode.vue";
import { ElMessage, ElMessageBox } from "element-plus";
import { computed, ref, watch } from "vue";
import { useI18n } from "vue-i18n";
import {
  downloadPayoutQr,
  getPayoutAccount,
  reviewPayoutAccount,
  type PayoutAccountDetailVO
} from "@/api/commission/payout-account";
import CopyText from "@/components/CopyText.vue";
import { formatInstant } from "@/utils/format";
import { ApiError } from "@/utils/http/error";
import { notifyError } from "@/utils/notify";

defineOptions({ name: "PayoutReviewDrawer" });

const props = defineProps<{
  modelValue: boolean;
  accountId: string | null;
}>();

const emit = defineEmits<{
  (event: "update:modelValue", value: boolean): void;
  /** A review decision landed; the list and the menu badge refresh. */
  (event: "reviewed"): void;
}>();

const { t } = useI18n();

const loading = ref(false);
const detail = ref<PayoutAccountDetailVO | null>(null);
const acting = ref(false);
const qrUrl = ref<string | null>(null);
const rejectVisible = ref(false);
const rejectReason = ref("");
const rejectError = ref("");

const canDecide = computed(() => detail.value?.status === "PENDING");

watch(
  () => [props.modelValue, props.accountId] as const,
  async ([open, id]) => {
    if (!open || !id) return;
    await load(id);
  },
  { immediate: true }
);

async function load(id: string): Promise<void> {
  loading.value = true;
  try {
    detail.value = await getPayoutAccount(id);
    await loadQr();
  } catch (error) {
    notifyError(error);
    emit("update:modelValue", false);
  } finally {
    loading.value = false;
  }
}

/** The QR encodes the wallet address, so it streams as a blob like the full account. */
async function loadQr(): Promise<void> {
  releaseQr();
  const fileId = detail.value?.qrFileId;
  if (!fileId) return;
  try {
    const blob = await downloadPayoutQr(fileId);
    qrUrl.value = URL.createObjectURL(blob);
  } catch {
    // The account stays reviewable without the picture.
  }
}

function releaseQr(): void {
  if (qrUrl.value) URL.revokeObjectURL(qrUrl.value);
  qrUrl.value = null;
}

/**
 * Sends the decision with the `lastSubmittedAt` this drawer loaded; a 409 means the creator
 * changed or someone else reviewed the account meanwhile, so the detail reloads.
 */
async function decide(action: "APPROVE" | "REJECT", reason?: string): Promise<void> {
  const current = detail.value;
  if (!current || !props.accountId) return;
  acting.value = true;
  try {
    await reviewPayoutAccount(props.accountId, {
      action,
      ...(action === "REJECT" ? { reason } : {}),
      lastSubmittedAt: current.lastSubmittedAt
    });
    ElMessage.success(action === "APPROVE" ? t("payout.approved") : t("payout.rejected"));
    emit("reviewed");
    await load(props.accountId);
  } catch (error) {
    if (error instanceof ApiError && error.errorCode === "PAYOUT_ACCOUNT_NOT_PENDING") {
      ElMessage.warning(t("errors.PAYOUT_ACCOUNT_NOT_PENDING"));
      // load() reports its own failure and closes the drawer; nothing to catch here.
      await load(props.accountId);
      emit("reviewed");
    } else {
      notifyError(error);
    }
  } finally {
    acting.value = false;
  }
}

async function approve(): Promise<void> {
  try {
    await ElMessageBox.confirm(t("payout.approveConfirm"), t("payout.approve"), {
      type: "warning",
      confirmButtonText: t("payout.approve"),
      cancelButtonText: t("common.cancel")
    });
  } catch {
    return; // cancelled
  }
  await decide("APPROVE");
}

function openReject(): void {
  rejectReason.value = "";
  rejectError.value = "";
  rejectVisible.value = true;
}

async function submitReject(): Promise<void> {
  const reason = rejectReason.value.trim();
  if (reason.length < 1 || reason.length > 255) {
    rejectError.value = t("payout.rejectReasonRule");
    return;
  }
  rejectVisible.value = false;
  await decide("REJECT", reason);
}
</script>

<template>
  <el-drawer
    :model-value="modelValue"
    :title="t('payout.detailTitle')"
    size="560px"
    @update:model-value="emit('update:modelValue', $event)"
    @closed="releaseQr"
  >
    <div v-loading="loading">
      <template v-if="detail">
        <el-alert class="audit-note" type="info" :closable="false" :title="t('payout.auditNote')" />
        <el-descriptions :column="1" border>
          <el-descriptions-item :label="t('payout.kolCode')">
            <NameCode :name="detail.kolName" :code="detail.kolCode"
              ><template #code><CopyText :value="detail.kolCode" /></template
            ></NameCode>
          </el-descriptions-item>
          <el-descriptions-item :label="t('payout.type')">
            {{ t(`payout.typeOption.${detail.accountType}`) }}
            <template v-if="detail.network"> · {{ detail.network }}</template>
          </el-descriptions-item>
          <el-descriptions-item
            v-if="detail.accountType === 'PAYPAL'"
            :label="t('payout.currency')"
          >
            <span data-test="payout-currency">{{ detail.currency || "-" }}</span>
          </el-descriptions-item>
          <el-descriptions-item :label="t('payout.fullAccount')">
            <CopyText :value="detail.account" />
          </el-descriptions-item>
          <el-descriptions-item :label="t('payout.holder')">
            {{ detail.holderName || "-" }}
          </el-descriptions-item>
          <el-descriptions-item :label="t('payout.qr')">
            <el-image
              v-if="qrUrl"
              class="qr-image"
              :src="qrUrl"
              :preview-src-list="[qrUrl]"
              fit="contain"
              alt=""
              data-test="qr-image"
            />
            <span v-else>{{ detail.qrFileId ? t("payout.qrUnavailable") : "-" }}</span>
          </el-descriptions-item>
          <el-descriptions-item :label="t('common.status')">
            <el-tag
              :type="
                detail.status === 'APPROVED'
                  ? 'success'
                  : detail.status === 'REJECTED'
                    ? 'danger'
                    : detail.status === 'DISABLED'
                      ? 'info'
                      : 'warning'
              "
            >
              {{ t(`payout.status.${detail.status}`) }}
            </el-tag>
          </el-descriptions-item>
          <el-descriptions-item :label="t('payout.defaultColumn')">
            {{ detail.defaultAccount ? t("payout.defaultColumn") : "-" }}
          </el-descriptions-item>
          <el-descriptions-item
            v-if="detail.status === 'REJECTED' && detail.rejectReason"
            :label="t('payout.rejectReason')"
          >
            {{ detail.rejectReason }}
          </el-descriptions-item>
          <el-descriptions-item :label="t('payout.submittedAt')">
            {{ formatInstant(detail.lastSubmittedAt) }}
          </el-descriptions-item>
          <el-descriptions-item :label="t('payout.reviewedAt')">
            {{ formatInstant(detail.reviewedTime) }}
          </el-descriptions-item>
        </el-descriptions>

        <div v-if="canDecide" class="review-actions">
          <el-button type="success" :loading="acting" data-test="approve" @click="approve">
            {{ t("payout.approve") }}
          </el-button>
          <el-button type="danger" plain :loading="acting" data-test="reject" @click="openReject">
            {{ t("payout.reject") }}
          </el-button>
        </div>
      </template>
    </div>

    <el-dialog
      v-model="rejectVisible"
      :title="t('payout.reject')"
      width="min(440px, calc(100vw - 24px))"
      :close-on-click-modal="false"
      append-to-body
    >
      <el-alert
        v-if="rejectError"
        class="audit-note"
        type="error"
        :closable="false"
        :title="rejectError"
      />
      <el-input
        v-model="rejectReason"
        type="textarea"
        :rows="3"
        maxlength="255"
        show-word-limit
        data-test="reject-reason"
        :placeholder="t('payout.rejectPlaceholder')"
      />
      <template #footer>
        <el-button @click="rejectVisible = false">{{ t("common.cancel") }}</el-button>
        <el-button type="danger" data-test="reject-submit" @click="submitReject">
          {{ t("payout.reject") }}
        </el-button>
      </template>
    </el-dialog>
  </el-drawer>
</template>

<style scoped>
.audit-note {
  margin-bottom: 12px;
}
.kol-name {
  margin-left: 8px;
  color: var(--tcrm-color-text-secondary);
}
.qr-image {
  width: 160px;
  height: 160px;
  border: 1px solid var(--tcrm-color-border);
  border-radius: var(--tcrm-radius-control);
}
.review-actions {
  display: flex;
  justify-content: flex-end;
  gap: var(--tcrm-space-2);
  margin-top: var(--tcrm-space-3);
}
</style>
