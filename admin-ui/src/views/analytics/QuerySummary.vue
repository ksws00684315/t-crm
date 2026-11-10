<script setup lang="ts">
import { DataAnalysis } from "@element-plus/icons-vue";
import { useI18n } from "vue-i18n";
import { formatInstant, formatNameWithCode } from "@/utils/format";
import type { AnalyticsResult, Option, Options } from "@/api/analytics";

defineProps<{ result: AnalyticsResult; options?: Options }>();
const { t } = useI18n();

function label(id: string | undefined, options?: Option[]): string {
  const option = options?.find(entry => entry.id === id);
  return option ? formatNameWithCode(option.name, option.code) : (id ?? t("analytics.all"));
}
</script>

<template>
  <section class="query-summary" :aria-label="t('analytics.applied')">
    <el-icon class="query-summary__icon" aria-hidden="true"><DataAnalysis /></el-icon>
    <div class="query-summary__details">
      <strong>{{ t("analytics.applied") }}</strong>
      <span
        >{{ result.meta.period.startDate }} – {{ result.meta.period.endDate }} ·
        {{ t("analytics.beijing") }}</span
      >
      <span>{{ t("analytics.shop") }} {{ label(result.meta.filters.shopId, options?.shops) }}</span>
      <span>{{ t("analytics.channel") }} {{ t("analytics.weidian") }}</span>
      <span v-if="result.meta.filters.kolId"
        >{{ t("analytics.creator") }}
        {{ label(result.meta.filters.kolId, options?.creators) }}</span
      >
      <span v-if="result.meta.filters.inviterId"
        >{{ t("analytics.inviter") }}
        {{ label(result.meta.filters.inviterId, options?.inviters) }}</span
      >
      <span v-if="result.keyword">{{ t("analytics.keyword") }} {{ result.keyword }}</span>
    </div>
    <div class="query-summary__meta">
      <span>{{ t(`analytics.scope.${result.meta.dataScope}`) }}</span>
      <span class="query-summary__updated" :class="{ 'has-update': result.meta.updatedAt }">{{
        t("analytics.updated", {
          time: result.meta.updatedAt ? formatInstant(result.meta.updatedAt) : "—"
        })
      }}</span>
    </div>
  </section>
</template>

<style scoped>
.query-summary {
  display: flex;
  flex-wrap: wrap;
  gap: var(--tcrm-space-2) var(--tcrm-space-4);
  align-items: center;
  border: 1px solid var(--el-border-color-lighter);
  border-inline-start: 3px solid var(--tcrm-color-chain-finance);
  border-radius: 12px;
  padding: var(--tcrm-space-3) var(--tcrm-space-4);
  background: var(--el-bg-color);
  color: var(--el-text-color-secondary);
  font-size: var(--tcrm-font-size-body);
  line-height: var(--tcrm-line-height-body);
  overflow-wrap: anywhere;
}
.query-summary__icon {
  flex: 0 0 var(--tcrm-control-height);
  height: var(--tcrm-control-height);
  border-radius: var(--tcrm-radius-control);
  background: var(--tcrm-color-order-band);
  color: var(--tcrm-color-primary-active);
  font-size: var(--tcrm-font-size-section);
}
.query-summary__details,
.query-summary__meta {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--tcrm-space-1) var(--tcrm-space-3);
}
.query-summary__details {
  flex: 1 1 220px;
  min-width: 0;
}
.query-summary__details strong {
  color: var(--tcrm-color-text);
}
.query-summary__meta > :first-child {
  color: var(--tcrm-color-primary-active);
  background: var(--tcrm-color-order-band);
  border-radius: var(--tcrm-radius-control);
  padding-inline: var(--tcrm-space-2);
}
.query-summary__updated.has-update::before {
  display: inline-block;
  content: "";
  width: var(--tcrm-space-2);
  height: var(--tcrm-space-2);
  border-radius: 50%;
  background: var(--tcrm-color-success);
  margin-inline-end: var(--tcrm-space-2);
}
</style>
