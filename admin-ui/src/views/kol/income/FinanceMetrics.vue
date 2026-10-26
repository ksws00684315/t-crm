<script setup lang="ts">
import { useI18n } from "vue-i18n";
import type { Metrics } from "@/api/commission/finance-types";
import { formatPrice } from "@/utils/format";
defineProps<{ metrics: Metrics; reconciliation?: boolean }>();
const { t } = useI18n();
</script>
<template>
  <section class="finance-metrics" data-test="finance-metrics">
    <article v-for="key in ['estimated', 'frozen', 'available', 'paid'] as const" :key="key">
      <span>{{ t(`finance.${key}`) }}</span>
      <strong :data-test="`metric-${key}`">{{ formatPrice(metrics[key], "CNY") }}</strong>
      <template v-if="key === 'paid'">
        <small
          >{{ t("finance.commissionPaid") }} {{ formatPrice(metrics.commissionPaid, "CNY") }}</small
        >
        <small>{{ t("finance.prepayPaid") }} {{ formatPrice(metrics.prepayPaid, "CNY") }}</small>
      </template>
    </article>
    <template v-if="reconciliation">
      <article
        v-for="key in ['occupied', 'pendingDeduction', 'deducted', 'cumulativePaid'] as const"
        :key="key"
      >
        <span>{{ t(`finance.${key}`) }}</span
        ><strong>{{ formatPrice(metrics[key], "CNY") }}</strong>
      </article>
    </template>
  </section>
</template>
<style scoped>
.finance-metrics {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: var(--tcrm-space-4);
}
article {
  display: flex;
  flex-direction: column;
  min-width: 0;
  padding: var(--tcrm-space-4);
  gap: var(--tcrm-space-2);
  background: var(--tcrm-color-card);
  border: 1px solid var(--tcrm-color-border);
  border-radius: var(--tcrm-radius-card);
}
span,
small {
  color: var(--tcrm-color-text-secondary);
}
strong {
  font-size: var(--tcrm-font-size-title);
  line-height: var(--tcrm-line-height-title);
  font-weight: 600;
  font-variant-numeric: tabular-nums;
  overflow-wrap: anywhere;
}
small {
  font-size: var(--tcrm-font-size-help);
  line-height: var(--tcrm-line-height-help);
}
@media (max-width: 767px) {
  .finance-metrics {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
</style>
