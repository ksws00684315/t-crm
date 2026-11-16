<script setup lang="ts">
import { computed, nextTick, ref } from "vue";
import { ArrowLeft, ArrowRight, Search } from "@element-plus/icons-vue";
import { useI18n } from "vue-i18n";
import ListSearch from "@/components/ListSearch.vue";
import NameCode from "@/components/NameCode.vue";
import { formatNameWithCode } from "@/utils/format";
import type { Filters, Options } from "@/api/analytics";
import { adjacentPeriod, dates, monthRange, periodModes, periodRange } from "./board";
import type { PeriodMode } from "./board";

defineProps<{ options?: Options; loading: boolean; optionsFailed: boolean }>();
const filters = defineModel<Filters>({ required: true });
const emit = defineEmits<{ search: [live: boolean]; reset: []; refresh: [] }>();
const { t } = useI18n();

const mode = ref<PeriodMode>("sevenDays");
const picker = ref<{ handleOpen: () => void }>();
const today = () => dates(1).endDate;
const custom = computed(() => [filters.value.startDate, filters.value.endDate]);
const next = computed(() => adjacentPeriod(mode.value, filters.value, 1, today()));

function setRange(range: Pick<Filters, "startDate" | "endDate">): void {
  filters.value = { ...filters.value, ...range };
}

async function selectMode(value: PeriodMode): Promise<void> {
  if (mode.value !== value && value !== "custom") setRange(periodRange(value));
  mode.value = value;
  if (value === "day" || value === "month" || value === "custom") {
    await nextTick();
    picker.value?.handleOpen();
  }
}

function move(direction: -1 | 1): void {
  const range = adjacentPeriod(mode.value, filters.value, direction, today());
  if (range) setRange(range);
}

function setDay(day: string): void {
  if (day) setRange({ startDate: day, endDate: day });
}

function setMonth(month: string): void {
  if (month) setRange(monthRange(month, today()));
}

function setCustom(range: [string, string] | null): void {
  if (range) setRange({ startDate: range[0], endDate: range[1] });
}

function future(date: Date): boolean {
  const month = String(date.getMonth() + 1).padStart(2, "0");
  const day = String(date.getDate()).padStart(2, "0");
  return `${date.getFullYear()}-${month}-${day}` > today();
}

function reset(): void {
  mode.value = "sevenDays";
  emit("reset");
}
</script>

<template>
  <ListSearch class="analytics-filter" @search="emit('search', mode === 'live')" @reset="reset">
    <template #actions>
      <el-button :loading="loading" @click="emit('refresh')">{{
        t("analytics.refresh")
      }}</el-button>
    </template>
    <template #primary>
      <div class="analytics-filter__time">
        <span class="analytics-filter__label">{{ t("analytics.period") }}</span>
        <div class="analytics-filter__period">
          <el-button-group role="group" :aria-label="t('analytics.period')">
            <el-button
              v-for="choice in periodModes"
              :key="choice"
              native-type="button"
              :aria-pressed="mode === choice"
              :class="{ 'period-selected': mode === choice }"
              @click="selectMode(choice)"
            >
              {{ t(`analytics.periodMode.${choice}`) }}
            </el-button>
            <el-button
              native-type="button"
              :icon="ArrowLeft"
              :aria-label="t('analytics.previousPeriod')"
              :disabled="mode === 'live'"
              @click="move(-1)"
            />
            <el-button
              native-type="button"
              :icon="ArrowRight"
              :aria-label="t('analytics.nextPeriod')"
              :disabled="!next"
              @click="move(1)"
            />
          </el-button-group>
          <el-date-picker
            v-if="mode === 'day'"
            ref="picker"
            :model-value="filters.startDate"
            :aria-label="t('analytics.periodMode.day')"
            value-format="YYYY-MM-DD"
            type="date"
            :disabled-date="future"
            :clearable="false"
            @update:model-value="setDay"
          />
          <el-date-picker
            v-else-if="mode === 'month'"
            ref="picker"
            :model-value="filters.startDate.slice(0, 7)"
            :aria-label="t('analytics.periodMode.month')"
            value-format="YYYY-MM"
            type="month"
            :disabled-date="future"
            :clearable="false"
            @update:model-value="setMonth"
          />
          <el-date-picker
            v-else-if="mode === 'custom'"
            ref="picker"
            :model-value="custom"
            :aria-label="t('analytics.periodMode.custom')"
            value-format="YYYY-MM-DD"
            type="daterange"
            single-panel
            placement="bottom-start"
            :fallback-placements="['top-start', 'bottom-end', 'top-end']"
            :start-placeholder="t('analytics.startDate')"
            :end-placeholder="t('analytics.endDate')"
            :disabled-date="future"
            :clearable="false"
            @update:model-value="setCustom"
          />
          <span v-else class="analytics-filter__dates">
            {{ filters.startDate
            }}<template v-if="filters.endDate !== filters.startDate">
              – {{ filters.endDate }}</template
            >
          </span>
        </div>
      </div>
      <div class="analytics-filter__fields">
        <span class="analytics-filter__label">{{ t("analytics.conditions") }}</span>
        <el-select
          v-model="filters.shopId"
          class="analytics-filter__shop"
          clearable
          filterable
          :aria-label="t('analytics.shop')"
          :placeholder="t('analytics.all')"
        >
          <template #prefix
            ><span>{{ t("analytics.shop") }}</span></template
          >
          <el-option
            v-for="option in options?.shops"
            :key="option.id"
            :value="option.id"
            :label="formatNameWithCode(option.name, option.code)"
          >
            <NameCode v-if="option.code" :name="option.name" :code="option.code" />
            <span v-else>{{ option.name }}</span>
          </el-option>
        </el-select>
        <el-select
          v-model="filters.channel"
          class="analytics-filter__channel"
          clearable
          :aria-label="t('analytics.channel')"
          :placeholder="t('analytics.all')"
        >
          <template #prefix
            ><span>{{ t("analytics.channel") }}</span></template
          >
          <el-option value="WEIDIAN" :label="t('analytics.weidian')" />
        </el-select>
        <el-input
          v-model="filters.creatorKeyword"
          class="analytics-filter__creator"
          clearable
          :maxlength="100"
          :aria-label="t('analytics.creatorSearch')"
          :placeholder="t('analytics.creatorSearch')"
        >
          <template #prefix>
            <span>{{ t("analytics.creator") }}</span>
            <el-icon class="analytics-filter__search-icon"><Search /></el-icon>
          </template>
        </el-input>
        <span v-if="optionsFailed" class="analytics-filter__failure" role="status">{{
          t("analytics.optionsFailed")
        }}</span>
      </div>
    </template>
  </ListSearch>
</template>

<style scoped>
.analytics-filter :deep(.list-search__row) {
  display: grid;
  grid-template-columns: minmax(0, 790px) max-content;
}
.analytics-filter__time,
.analytics-filter__fields {
  display: flex;
  align-items: center;
  gap: var(--tcrm-space-2);
  min-width: 0;
}
.analytics-filter__time {
  grid-column: 1 / -1;
}
.analytics-filter__period {
  display: flex;
  min-width: 0;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--tcrm-space-2);
}
.analytics-filter__label {
  flex: 0 0 5em;
  white-space: nowrap;
  color: var(--tcrm-color-text-secondary);
}
.analytics-filter__period :deep(.el-button) {
  padding-inline: var(--tcrm-space-3);
}
.analytics-filter__period :deep(.period-selected) {
  color: var(--tcrm-color-primary-text);
  background: var(--tcrm-color-option-selected-bg);
  border-color: var(--tcrm-color-input-border);
}
.analytics-filter__period :deep(.period-selected:hover) {
  background: var(--tcrm-color-option-selected-hover-bg);
}
.analytics-filter__period :deep(.el-date-editor) {
  width: 144px;
  max-width: 100%;
}
.analytics-filter__period :deep(.el-date-editor--daterange) {
  width: 264px;
}
.analytics-filter__dates {
  color: var(--el-text-color-secondary);
  font-size: var(--tcrm-font-size-help);
}
.analytics-filter__shop {
  flex: 1 1 210px;
  min-width: 112px;
}
.analytics-filter__channel {
  flex: 0 1 164px;
  min-width: 100px;
}
.analytics-filter__creator {
  flex: 1.5 1 320px;
  min-width: 228px;
}
.analytics-filter__search-icon {
  margin-inline-start: var(--tcrm-space-1);
}
.analytics-filter__failure {
  color: var(--el-text-color-secondary);
}
.analytics-filter :deep(.list-search__actions) {
  min-width: 0;
}
@media (max-width: 767px) {
  .analytics-filter :deep(.list-search__row) {
    grid-template-columns: minmax(0, 1fr);
  }
  .analytics-filter__time,
  .analytics-filter__fields {
    flex-wrap: wrap;
  }
  .analytics-filter__label {
    flex-basis: 100%;
  }
  .analytics-filter__period :deep(.el-button-group) {
    display: inline-flex;
    max-width: 100%;
    overflow-x: auto;
  }
  .analytics-filter__period :deep(.el-button) {
    padding-inline: var(--tcrm-space-2);
  }
  .analytics-filter__creator {
    flex-basis: 100%;
  }
}
</style>
