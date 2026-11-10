<script setup lang="ts">
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
    <strong>{{ t("analytics.applied") }}</strong>
    <span
      >{{ result.meta.period.startDate }} – {{ result.meta.period.endDate }} ·
      {{ t("analytics.beijing") }}</span
    >
    <span>{{ t("analytics.shop") }} {{ label(result.meta.filters.shopId, options?.shops) }}</span>
    <span>{{ t("analytics.channel") }} {{ t("analytics.weidian") }}</span>
    <span v-if="result.meta.filters.kolId"
      >{{ t("analytics.creator") }} {{ label(result.meta.filters.kolId, options?.creators) }}</span
    >
    <span v-if="result.meta.filters.inviterId"
      >{{ t("analytics.inviter") }}
      {{ label(result.meta.filters.inviterId, options?.inviters) }}</span
    >
    <span v-if="result.keyword">{{ t("analytics.keyword") }} {{ result.keyword }}</span>
    <span>{{ t(`analytics.scope.${result.meta.dataScope}`) }}</span>
    <span>{{
      t("analytics.updated", {
        time: result.meta.updatedAt ? formatInstant(result.meta.updatedAt) : "—"
      })
    }}</span>
  </section>
</template>

<style scoped>
.query-summary {
  display: flex;
  flex-wrap: wrap;
  gap: var(--tcrm-space-2) var(--tcrm-space-4);
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 12px;
  padding: var(--tcrm-space-3) var(--tcrm-space-4);
  background: var(--el-bg-color);
  color: var(--el-text-color-secondary);
  font-size: var(--tcrm-font-size-body);
  line-height: var(--tcrm-line-height-body);
  overflow-wrap: anywhere;
}
</style>
