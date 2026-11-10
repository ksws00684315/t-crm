<script setup lang="ts">
import { InfoFilled } from "@element-plus/icons-vue";
import type { Component } from "vue";

defineProps<{
  label: string;
  value: string;
  detail?: string;
  hint?: string;
  comparison?: string;
  unavailable?: boolean;
  icon?: Component;
  tone?: "neutral" | "blue" | "green" | "amber" | "red";
}>();
</script>

<template>
  <article class="metric-card" :class="tone && `metric-card--${tone}`" :aria-label="label">
    <el-icon v-if="icon" class="metric-card__icon" aria-hidden="true">
      <component :is="icon" />
    </el-icon>
    <div class="metric-card__body">
      <div class="metric-card__label">
        {{ label }}
        <el-tooltip v-if="hint" :content="hint" :trigger="['hover', 'focus']">
          <button type="button" class="metric-card__hint" :aria-label="hint">
            <el-icon aria-hidden="true"><InfoFilled /></el-icon>
          </button>
        </el-tooltip>
      </div>
      <div v-if="unavailable" class="metric-card__state">
        <el-tag type="info" effect="light">{{ value }}</el-tag>
      </div>
      <strong v-else>{{ value }}</strong>
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
  padding: var(--analytics-card-padding, var(--tcrm-space-4));
  background: var(--tcrm-color-card);
  border: 1px solid var(--tcrm-color-border);
  border-radius: var(--tcrm-radius-card);
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
  color: var(--metric-accent, var(--tcrm-color-primary-text));
  background: var(--metric-background, var(--tcrm-color-option-selected-bg));
  font-size: var(--tcrm-font-size-brand);
}
.metric-card--neutral {
  --metric-accent: var(--tcrm-color-text-secondary);
  --metric-background: var(--tcrm-color-muted);
}
.metric-card--blue {
  --metric-accent: var(--tcrm-color-primary-text);
}
.metric-card--green {
  --metric-accent: var(--tcrm-color-success);
  --metric-background: var(--tcrm-color-success-bg);
}
.metric-card--amber {
  --metric-accent: var(--tcrm-color-warning);
  --metric-background: var(--tcrm-color-warning-bg);
}
.metric-card--red {
  --metric-accent: var(--tcrm-color-danger);
  --metric-background: var(--tcrm-color-danger-bg);
}
.metric-card__label {
  color: var(--tcrm-color-text-secondary);
  font-size: var(--tcrm-font-size-body);
  line-height: var(--tcrm-line-height-body);
  font-weight: 400;
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
.metric-card__state {
  display: flex;
  align-items: center;
  min-height: var(--tcrm-line-height-dashboard-metric);
}
strong {
  color: var(--tcrm-color-text);
  font-weight: 600;
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
