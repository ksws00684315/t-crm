<script setup lang="ts">
import { InfoFilled } from "@element-plus/icons-vue";
import type { Component } from "vue";

defineProps<{
  label: string;
  value: string;
  detail?: string;
  hint?: string;
  comparison?: string;
  icon?: Component;
  tone?: "violet" | "blue" | "green" | "amber" | "red";
}>();
</script>

<template>
  <article class="metric-card" :class="tone && `metric-card--${tone}`">
    <el-icon v-if="icon" class="metric-card__icon" aria-hidden="true">
      <component :is="icon" />
    </el-icon>
    <div class="metric-card__body">
      <h2>
        {{ label }}
        <el-tooltip v-if="hint" :content="hint" :trigger="['hover', 'focus']">
          <button type="button" class="metric-card__hint" :aria-label="hint">
            <el-icon aria-hidden="true"><InfoFilled /></el-icon>
          </button>
        </el-tooltip>
      </h2>
      <strong>{{ value }}</strong>
      <span v-if="detail">{{ detail }}</span>
      <small v-if="comparison">{{ comparison }}</small>
    </div>
  </article>
</template>

<style scoped>
.metric-card {
  min-width: 0;
  display: flex;
  align-items: flex-start;
  gap: var(--tcrm-space-3);
  padding: var(--tcrm-space-4);
  background: var(--el-bg-color);
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 12px;
}
.metric-card__body {
  min-width: 0;
  display: grid;
  gap: var(--tcrm-space-1);
}
.metric-card__icon {
  flex: 0 0 var(--tcrm-control-height-large);
  height: var(--tcrm-control-height-large);
  border-radius: var(--tcrm-radius-control);
  color: var(--metric-accent, var(--tcrm-color-primary));
  background: color-mix(
    in srgb,
    var(--metric-accent, var(--tcrm-color-primary)) 12%,
    var(--tcrm-color-card)
  );
  font-size: var(--tcrm-font-size-brand);
}
.metric-card--violet {
  --metric-accent: var(--tcrm-color-chain-finance);
}
.metric-card--blue {
  --metric-accent: var(--tcrm-color-primary);
}
.metric-card--green {
  --metric-accent: var(--tcrm-color-success);
}
.metric-card--amber {
  --metric-accent: var(--tcrm-color-warning);
}
.metric-card--red {
  --metric-accent: var(--tcrm-color-danger);
}
h2 {
  font-size: var(--tcrm-font-size-table);
  line-height: var(--tcrm-line-height-table);
  font-weight: 600;
  margin: 0;
}
.metric-card__hint {
  display: inline-flex;
  vertical-align: middle;
  padding: 0;
  border: 0;
  background: transparent;
  color: var(--tcrm-color-text-secondary);
  cursor: help;
  font-size: var(--tcrm-font-size-help);
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
