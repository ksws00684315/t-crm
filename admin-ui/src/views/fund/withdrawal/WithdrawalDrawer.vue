<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from "vue";
import { useI18n } from "vue-i18n";
import { subscribeNarrow } from "@/utils/sidebar";
import { getWithdrawal, downloadWithdrawalVoucher } from "@/api/commission/finance";
import type { Withdrawal } from "@/api/commission/finance-types";
import DataTable, { type DataColumn } from "@/components/DataTable.vue";
import CopyText from "@/components/CopyText.vue";
import { formatInstant, formatPrice } from "@/utils/format";
import { notifyError } from "@/utils/notify";
import { saveBlob } from "@/utils/download";
const props = defineProps<{ modelValue: boolean; id: string }>();
const emit = defineEmits<{ "update:modelValue": [value: boolean] }>();
const { t } = useI18n();
const narrow = ref(false);
let unsubscribe: (() => void) | undefined;
onMounted(() => {
  unsubscribe = subscribeNarrow(value => (narrow.value = value));
});
onBeforeUnmount(() => unsubscribe?.());
const detail = ref<Withdrawal>();
const loading = ref(false);
const failed = ref(false);
async function load() {
  loading.value = true;
  failed.value = false;
  detail.value = undefined;
  try {
    detail.value = await getWithdrawal(props.id);
  } catch (error) {
    failed.value = true;
    notifyError(error);
  } finally {
    loading.value = false;
  }
}
watch(
  () => [props.modelValue, props.id],
  () => {
    if (props.modelValue) void load();
  }
);
const columns = computed<DataColumn[]>(() =>
  ["orderNo", "image", "product", "sku", "occupied", "remainingSnapshot"].map(key => ({
    key,
    prop: key,
    label: t(`finance.${key}`),
    minWidth: key === "image" ? 110 : 190,
    amount: ["occupied", "remainingSnapshot"].includes(key),
    tooltip: true
  }))
);
async function voucher() {
  if (!detail.value?.payment?.voucher) return;
  try {
    const result = await downloadWithdrawalVoucher(detail.value.payment.voucher);
    saveBlob(result.blob, result.fileName ?? "payment-voucher");
  } catch (error) {
    notifyError(error);
  }
}
</script>
<template>
  <el-drawer
    :model-value="modelValue"
    :title="t('finance.view')"
    size="min(1100px, 96vw)"
    destroy-on-close
    @update:model-value="emit('update:modelValue', $event)"
  >
    <div v-loading="loading" class="withdrawal-detail" data-test="withdrawal-detail">
      <el-result v-if="failed" icon="error" :title="t('finance.loadFailed')"
        ><template #extra
          ><el-button @click="load">{{ t("finance.retry") }}</el-button></template
        ></el-result
      >
      <template v-if="detail">
        <el-alert
          v-if="detail.paused"
          :title="t('finance.paused')"
          :description="t(`finance.${detail.pauseReason}`)"
          type="error"
          :closable="false"
        />
        <el-descriptions :column="narrow ? 1 : 2" border>
          <el-descriptions-item :label="t('finance.applicationNo')"
            ><CopyText :value="detail.applicationNo" /></el-descriptions-item
          ><el-descriptions-item :label="t('finance.applicationType')">{{
            t("finance.COMMISSION")
          }}</el-descriptions-item>
          <el-descriptions-item :label="t('finance.creator')"
            >{{ detail.kolName }} <CopyText :value="detail.kolCode" /></el-descriptions-item
          ><el-descriptions-item :label="t('finance.amount')">{{
            formatPrice(detail.amount, "CNY")
          }}</el-descriptions-item
          ><el-descriptions-item :label="t('finance.currency')">CNY</el-descriptions-item
          ><el-descriptions-item :label="t('finance.status')">{{
            t(`finance.${detail.status}`)
          }}</el-descriptions-item>
          <el-descriptions-item :label="t('finance.recipient')" :span="narrow ? 1 : 2"
            >{{ detail.account.type }} · {{ detail.account.account }} ·
            {{ detail.account.holder }} · {{ detail.account.currency }}
            {{ detail.account.network }}</el-descriptions-item
          ><el-descriptions-item :label="t('finance.note')">{{
            detail.note || "—"
          }}</el-descriptions-item
          ><el-descriptions-item :label="t('finance.reviewNote')">{{
            detail.reviewNote === "rejectExample"
              ? t("finance.rejectExample")
              : detail.reviewNote || "—"
          }}</el-descriptions-item
          ><el-descriptions-item :label="t('finance.financeNote')">{{
            detail.financeNote || "—"
          }}</el-descriptions-item>
        </el-descriptions>
        <h2>{{ t("finance.allocations") }}</h2>
        <p>{{ t("finance.snapshotNote") }}</p>
        <DataTable
          table-id="withdrawal-allocations"
          :rows="detail.allocations"
          :columns="columns"
          :total="detail.allocations.length"
          :paged="false"
          plain
        >
          <template #cell-orderNo="{ row }"><CopyText :value="row.orderNo" /></template
          ><template #cell-image="{ row }"
            ><el-image :src="row.image" :alt="row.product" class="product-image" fit="cover"
              ><template #error
                ><span>{{ t("finance.noImage") }}</span></template
              ></el-image
            ></template
          ><template #cell-occupied="{ row }">{{ formatPrice(row.occupied, "CNY") }}</template
          ><template #cell-remainingSnapshot="{ row }">{{
            formatPrice(row.remainingSnapshot, "CNY")
          }}</template>
        </DataTable>
        <h2>{{ t("finance.approvals") }}</h2>
        <el-timeline
          ><el-timeline-item
            v-for="(event, index) in detail.history"
            :key="index"
            :timestamp="formatInstant(event.at)"
            >{{ t(`finance.${event.action}`) }} · {{ event.actor }}
            <p>{{ event.note }}</p></el-timeline-item
          ></el-timeline
        >
        <h2>{{ t("finance.paymentResult") }}</h2>
        <el-descriptions v-if="detail.payment" :column="narrow ? 1 : 2" border
          ><el-descriptions-item
            v-for="key in [
              'actualAmount',
              'actualCurrency',
              'amountCny',
              'method',
              'conversionNote',
              'financeNote'
            ] as const"
            :key="key"
            :label="t(`finance.${key}`)"
            >{{ detail.payment[key] || "—" }}</el-descriptions-item
          ><el-descriptions-item :label="t('finance.paidAt')">{{
            formatInstant(detail.payment.paidAt)
          }}</el-descriptions-item
          ><el-descriptions-item :label="t('finance.voucher')"
            ><el-button
              data-test="withdrawal-voucher"
              :disabled="!detail.payment.voucher"
              link
              type="primary"
              @click="voucher"
              >{{ t("finance.voucherPreview") }}</el-button
            ></el-descriptions-item
          ></el-descriptions
        >
        <el-empty v-else :description="t(`finance.${detail.status}`)" />
      </template>
    </div>
  </el-drawer>
</template>
<style scoped>
.product-image {
  width: 44px;
  height: 44px;
}
.product-image :deep(.el-image__error) {
  font-size: var(--tcrm-font-size-help);
}
.withdrawal-detail {
  display: flex;
  flex-direction: column;
  gap: var(--tcrm-space-4);
  min-width: 0;
  overflow-wrap: anywhere;
}
h2 {
  font-size: var(--tcrm-font-size-section);
  line-height: var(--tcrm-line-height-section);
  margin: 0;
}
p {
  margin: 0;
  color: var(--tcrm-color-text-secondary);
}
</style>
