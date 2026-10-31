<script setup lang="ts">
import NameCode from "@/components/NameCode.vue";
import { computed, ref, watch } from "vue";
import { useI18n } from "vue-i18n";
import {
  downloadPayoutQr,
  getPayoutAccount,
  type PayoutAccountDetailVO
} from "@/api/commission/payout-account";
import CopyText from "@/components/CopyText.vue";
import { useDescriptionColumns } from "@/composables/useDescriptionColumns";
import { formatInstant } from "@/utils/format";
import { notifyError } from "@/utils/notify";

defineOptions({ name: "PayoutAccountDetailDrawer" });

/**
 * Read-only full view of one payout account, opened from the creator's fund dialog (task 82).
 * Every load writes an operation log on the server; nothing here changes the account.
 */
const props = defineProps<{
  modelValue: boolean;
  accountId: string | null;
}>();

const emit = defineEmits<{
  (event: "update:modelValue", value: boolean): void;
}>();

const { t } = useI18n();
const detailRegion = ref<HTMLElement | null>(null);
const detailColumns = useDescriptionColumns(detailRegion);

const loading = ref(false);
const detail = ref<PayoutAccountDetailVO | null>(null);
const qrUrl = ref<string | null>(null);

const statusLabel = computed(() =>
  detail.value?.status === "DISABLED" ? t("kol.fundDisabled") : t("kol.fundActive")
);

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
    // The drawer stays readable without the picture.
  }
}

function releaseQr(): void {
  if (qrUrl.value) URL.revokeObjectURL(qrUrl.value);
  qrUrl.value = null;
}
</script>

<template>
  <el-drawer
    :model-value="modelValue"
    :title="t('payout.detailTitle')"
    size="640px"
    @update:model-value="emit('update:modelValue', $event)"
    @closed="releaseQr"
  >
    <div ref="detailRegion" v-loading="loading">
      <template v-if="detail">
        <el-alert class="audit-note" type="info" :closable="false" :title="t('payout.auditNote')" />
        <el-descriptions :column="detailColumns" border>
          <el-descriptions-item :label="t('payout.kolCode')" :span="detailColumns">
            <NameCode :name="detail.kolName" :code="detail.kolCode"
              ><template #code><CopyText :value="detail.kolCode" /></template
            ></NameCode>
          </el-descriptions-item>
          <el-descriptions-item
            :label="t('payout.type')"
            :span="detail.accountType === 'PAYPAL' ? 1 : detailColumns"
          >
            {{ t(`payout.typeOption.${detail.accountType}`) }}
            <template v-if="detail.network"> · {{ detail.network }}</template>
          </el-descriptions-item>
          <el-descriptions-item
            v-if="detail.accountType === 'PAYPAL'"
            :label="t('payout.currency')"
          >
            <span data-test="payout-currency">{{ detail.currency || "-" }}</span>
          </el-descriptions-item>
          <el-descriptions-item :label="t('payout.fullAccount')" :span="detailColumns">
            <CopyText :value="detail.account" />
          </el-descriptions-item>
          <el-descriptions-item :label="t('payout.holder')" :span="detailColumns">
            {{ detail.holderName || "-" }}
          </el-descriptions-item>
          <el-descriptions-item :label="t('payout.qr')" :span="detailColumns">
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
            <el-tag :type="detail.status === 'DISABLED' ? 'info' : 'success'">
              {{ statusLabel }}
            </el-tag>
          </el-descriptions-item>
          <el-descriptions-item :label="t('payout.defaultColumn')">
            {{ detail.defaultAccount ? t("payout.defaultColumn") : "-" }}
          </el-descriptions-item>
          <el-descriptions-item :label="t('payout.submittedAt')">
            {{ formatInstant(detail.lastSubmittedAt) }}
          </el-descriptions-item>
        </el-descriptions>
      </template>
    </div>
  </el-drawer>
</template>

<style scoped>
.audit-note {
  margin-bottom: 12px;
}
.qr-image {
  width: 160px;
  height: 160px;
  border: 1px solid var(--tcrm-color-border);
  border-radius: var(--tcrm-radius-control);
}
</style>
