<script setup lang="ts">
defineProps<{
  label: string;
  value: string;
  detail?: string;
  hint?: string;
  comparison?: string;
}>();
</script>

<template>
  <article class="metric-card">
    <h2>{{ label }}</h2>
    <strong>{{ value }}</strong>
    <span v-if="detail">{{ detail }}</span>
    <small v-if="comparison">{{ comparison }}</small>
    <small v-if="hint">{{ hint }}</small>
  </article>
</template>

<style scoped>
.metric-card {
  min-width: 0;
  display: grid;
  align-content: start;
  gap: var(--tcrm-space-2);
  padding: var(--tcrm-space-4);
  background: var(--el-bg-color);
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 12px;
}
h2 {
  font-size: var(--tcrm-font-size-table);
  line-height: var(--tcrm-line-height-table);
  font-weight: 600;
  margin: 0;
}
strong {
  font-size: var(--tcrm-font-size-dashboard-metric);
  line-height: var(--tcrm-line-height-dashboard-metric);
  font-variant-numeric: tabular-nums;
  overflow-wrap: anywhere;
}
span,
small {
  color: var(--el-text-color-secondary);
  font-size: var(--tcrm-font-size-body);
  line-height: var(--tcrm-line-height-body);
}
</style>
