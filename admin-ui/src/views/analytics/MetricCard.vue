<script setup lang="ts">
import { computed, type Component } from "vue";

interface Term {
  label: string;
  hint?: string;
  value?: string;
}

const props = defineProps<{
  label: string;
  value: string;
  detail?: string;
  hint?: string;
  labelTerms?: Term[];
  detailTerms?: Term[];
  comparison?: string;
  unavailable?: boolean;
  icon?: Component;
  tone?: "neutral" | "blue" | "green" | "amber" | "red" | "purple";
}>();

const note = computed(() => [props.detail, props.comparison].filter(Boolean).join(" · "));
</script>

<template>
  <article
    class="metric-card"
    :class="[tone && `metric-card--${tone}`, { 'metric-card--with-terms': labelTerms && icon }]"
    :aria-label="label"
  >
    <el-icon v-if="icon" class="metric-card__icon" aria-hidden="true">
      <component :is="icon" />
    </el-icon>
    <div class="metric-card__body">
      <div v-if="labelTerms" class="metric-card__label metric-card__terms">
        <template v-for="(term, index) in labelTerms" :key="term.label">
          <span v-if="index" aria-hidden="true">/</span>
          <el-tooltip
            :content="term.hint"
            :trigger="['hover', 'focus']"
            popper-class="analytics-metric-definition"
          >
            <span tabindex="0" class="metric-card__term">{{ term.label }}</span>
          </el-tooltip>
        </template>
      </div>
      <div v-else class="metric-card__label">
        <el-tooltip
          v-if="hint"
          :content="hint"
          :trigger="['hover', 'focus']"
          popper-class="analytics-metric-definition"
        >
          <span tabindex="0" class="metric-card__text metric-card__term">{{ label }}</span>
        </el-tooltip>
        <span v-else class="metric-card__text" :title="label">{{ label }}</span>
      </div>
      <div v-if="unavailable" class="metric-card__state">
        <el-tag type="info" effect="light">{{ value }}</el-tag>
      </div>
      <strong v-else class="metric-card__text" :title="value"
        ><slot name="value">{{ value }}</slot></strong
      >
      <small v-if="detailTerms" class="metric-card__note metric-card__terms">
        <template v-for="(term, index) in detailTerms" :key="term.label">
          <span v-if="index" aria-hidden="true">·</span>
          <el-tooltip
            v-if="term.hint"
            :content="term.hint"
            :trigger="['hover', 'focus']"
            popper-class="analytics-metric-definition"
          >
            <span tabindex="0" class="metric-card__term">{{ term.label }} {{ term.value }}</span>
          </el-tooltip>
          <span v-else>{{ term.label }} {{ term.value }}</span>
        </template>
      </small>
      <small v-else-if="note" class="metric-card__text metric-card__note" :title="note">{{
        note
      }}</small>
    </div>
  </article>
</template>

<style scoped>
.metric-card {
  min-width: 0;
  display: flex;
  align-items: var(--metric-card-alignment, flex-start);
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
  flex: 0 0 var(--metric-icon-box, var(--tcrm-control-height-large));
  height: var(--metric-icon-box, var(--tcrm-control-height-large));
  border-radius: var(--tcrm-radius-control);
  color: var(--metric-accent, var(--tcrm-color-primary-text));
  background: var(--metric-background, var(--tcrm-color-option-selected-bg));
  font-size: var(--metric-icon-size, var(--tcrm-font-size-brand));
}
.metric-card--neutral {
  --metric-accent: var(--tcrm-color-text-secondary);
  --metric-background: var(--tcrm-color-muted);
}
.metric-card--blue {
  --metric-accent: var(--tcrm-color-primary-text);
}
.metric-card--purple {
  --metric-accent: #6945d7;
  --metric-background: #efebfd;
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
.metric-card__text {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.metric-card__label {
  display: flex;
  align-items: center;
  gap: var(--tcrm-space-1);
  color: var(--tcrm-color-text-secondary);
  font-size: var(--tcrm-font-size-body);
  line-height: var(--tcrm-line-height-body);
  font-weight: 400;
  margin: 0;
}
.metric-card__terms {
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  gap: 0 var(--tcrm-space-1);
}
.metric-card__term {
  cursor: help;
}
.metric-card__note.metric-card__terms {
  display: block;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: var(--tcrm-font-size-help);
}
.metric-card__note.metric-card__terms > span + span {
  margin-inline-start: var(--tcrm-space-1);
}
.metric-card--with-terms {
  container-type: inline-size;
  display: grid;
  grid-template-columns: var(--metric-icon-box, var(--tcrm-control-height-large)) minmax(0, 1fr);
  row-gap: var(--tcrm-space-1);
  align-content: center;
}
.metric-card--with-terms .metric-card__body {
  display: contents;
}
.metric-card--with-terms .metric-card__icon {
  grid-row: 1 / 3;
  width: 100%;
}
.metric-card--with-terms .metric-card__note {
  grid-column: var(--metric-note-column, 1 / -1);
  margin-inline-start: var(--metric-note-indent, clamp(0px, calc(100cqw - 240px), 60px));
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
}
.metric-card__note {
  color: var(--el-text-color-secondary);
  font-size: var(--tcrm-font-size-body);
  line-height: var(--tcrm-line-height-body);
}
</style>
