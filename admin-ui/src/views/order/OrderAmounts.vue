<script setup lang="ts">
import { computed } from "vue";
import { useI18n } from "vue-i18n";
import type { OrderDetailVO } from "@/api/types";
import { useUserStore } from "@/stores/user";
import { formatAmountWithCurrency, formatInstant } from "@/utils/format";
import { formatRate } from "@/utils/rate";

defineOptions({ name: "OrderAmounts" });

/**
 * Order-level amount block (task 112, amount terms §2), shared by the order detail drawer and the
 * order chain funds section so both show the same items in the same order. Every amount comes from
 * the order detail endpoint; nothing is computed here except the item count. The three commission
 * items follow the chain rule: hidden without commission:view or outside the OWN coverage.
 */
const props = defineProps<{
  detail: OrderDetailVO;
  columns: number;
  /** The drawer shows the order and pay times at the end; the chain header already has them. */
  showTimes?: boolean;
}>();

const { t } = useI18n();
const userStore = useUserStore();

const itemCount = computed(() =>
  props.detail.items.some(item => item.quantity == null)
    ? "-"
    : String(props.detail.items.reduce((sum, item) => sum + (item.quantity ?? 0), 0))
);

function money(value: string | null | undefined, missing: string): string {
  return value == null ? missing : formatAmountWithCurrency(value);
}

/**
 * Why the commission items are absent, in the chain's order: no commission:view, then an OWN scope
 * on a mixed order; anything else is unknown data and reads as unverified, never a fake zero.
 */
const commissionHint = computed(() => {
  if (!userStore.can("commission:view")) return t("order.chain.commissionHidden");
  if (userStore.isOwnScope) return t("finance.scopeLimited");
  return t("finance.unverified");
});

const rateText = computed(() => {
  const rates = props.detail.commission?.rates ?? [];
  return rates.length
    ? rates.map(rate => formatRate(rate)).join(t("order.amounts.rateSeparator"))
    : "-";
});
</script>

<template>
  <el-descriptions :column="columns" border data-test="order-amounts">
    <el-descriptions-item :label="t('order.amounts.productTotal')">{{
      money(detail.itemAmount, t("order.missingHistoricalPrice"))
    }}</el-descriptions-item>
    <el-descriptions-item :label="t('order.amounts.itemCount')">{{
      itemCount
    }}</el-descriptions-item>
    <el-descriptions-item :label="t('order.freight')">{{
      formatAmountWithCurrency(detail.freight)
    }}</el-descriptions-item>
    <el-descriptions-item :label="t('order.amounts.orderAmount')">{{
      money(detail.orderAmount, "-")
    }}</el-descriptions-item>
    <el-descriptions-item :label="t('order.amounts.discount')">{{
      money(detail.discount, "-")
    }}</el-descriptions-item>
    <el-descriptions-item :label="t('order.payAmount')">{{
      formatAmountWithCurrency(detail.payAmount)
    }}</el-descriptions-item>
    <el-descriptions-item :label="t('order.amounts.refundAmount')">{{
      money(detail.refundAmount, "-")
    }}</el-descriptions-item>
    <el-descriptions-item :label="t('order.amounts.netReceived')">{{
      money(detail.netReceived, "-")
    }}</el-descriptions-item>
    <template v-if="detail.commission">
      <el-descriptions-item :label="t('order.amounts.commissionableAmount')"
        ><span data-test="order-amounts-commissionable">{{
          money(detail.commission.commissionableAmount, t("finance.unverified"))
        }}</span></el-descriptions-item
      >
      <el-descriptions-item :label="t('order.amounts.commissionRate')"
        ><span data-test="order-amounts-rate">{{ rateText }}</span></el-descriptions-item
      >
      <el-descriptions-item :label="t('order.amounts.estimatedCommission')"
        ><span data-test="order-amounts-estimated">{{
          money(detail.commission.estimatedCommission, t("finance.unverified"))
        }}</span></el-descriptions-item
      >
    </template>
    <template v-else>
      <el-descriptions-item :label="t('order.amounts.commissionableAmount')"
        ><span class="order-amounts__hidden" data-test="order-amounts-commission-hidden">{{
          commissionHint
        }}</span></el-descriptions-item
      >
      <el-descriptions-item :label="t('order.amounts.commissionRate')"
        ><span class="order-amounts__hidden">{{ commissionHint }}</span></el-descriptions-item
      >
      <el-descriptions-item :label="t('order.amounts.estimatedCommission')"
        ><span class="order-amounts__hidden">{{ commissionHint }}</span></el-descriptions-item
      >
    </template>
    <template v-if="showTimes">
      <el-descriptions-item :label="t('order.detail.orderTime')">{{
        formatInstant(detail.orderTime)
      }}</el-descriptions-item>
      <el-descriptions-item :label="t('order.payTime')">{{
        formatInstant(detail.payTime)
      }}</el-descriptions-item>
    </template>
  </el-descriptions>
</template>

<style scoped>
:deep(.el-descriptions__content) {
  font-variant-numeric: tabular-nums;
}
.order-amounts__hidden {
  color: var(--el-text-color-secondary);
}
</style>
