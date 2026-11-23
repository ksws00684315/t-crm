<script setup lang="ts">
import { computed, nextTick, ref } from "vue";
import { ArrowLeft, ArrowRight, Search } from "@element-plus/icons-vue";
import { useI18n } from "vue-i18n";
import ListSearch from "@/components/ListSearch.vue";
import NameCode from "@/components/NameCode.vue";
import { formatNameWithCode } from "@/utils/format";
import type { Filters, Options } from "@/api/analytics";
import { adjacentPeriod, dates, monthRange, periodModes, periodRange } from "./board";
import type { DateRange, PeriodMode } from "./board";

const props = defineProps<{
  options?: Options;
  loading: boolean;
  optionsFailed: boolean;
  showInviter?: boolean;
  showProduct?: boolean;
  income?: boolean;
  initialMode?: PeriodMode;
}>();
const filters = defineModel<Filters>({ required: true });
const emit = defineEmits<{
  search: [live: boolean];
  periodChange: [range: DateRange, live: boolean];
  reset: [];
  refresh: [];
}>();
const { t } = useI18n();
const selectedShopName = computed(
  () => props.options?.shops.find(shop => shop.id === filters.value.shopId)?.name
);

const mode = ref<PeriodMode>(props.initialMode ?? "sevenDays");
const picker = ref<{ handleOpen: () => void }>();
const today = () => dates(1).endDate;
const custom = computed(() => [filters.value.startDate, filters.value.endDate]);
const next = computed(() => adjacentPeriod(mode.value, filters.value, 1, today()));

function setRange(range: DateRange): void {
  filters.value = { ...filters.value, ...range, cumulative: range.cumulative };
  emit("periodChange", range, mode.value === "live");
}

async function selectMode(value: PeriodMode): Promise<void> {
  const changed = mode.value !== value;
  mode.value = value;
  if (changed) {
    if (value === "custom") {
      const { startDate, endDate, cumulative } = filters.value;
      setRange(cumulative ? dates() : { startDate, endDate });
    } else {
      setRange(periodRange(value));
    }
  }
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
  <ListSearch
    class="analytics-filter"
    :class="{ 'analytics-filter--four-fields': showInviter || showProduct }"
    @search="emit('search', mode === 'live')"
    @reset="reset"
  >
    <template #primary>
      <div class="analytics-filter__time">
        <span class="analytics-filter__label">{{ t("analytics.period") }}</span>
        <div class="analytics-filter__period" @keyup.enter.stop>
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
              :disabled="mode === 'live' || mode === 'cumulative'"
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
          <span v-else-if="mode !== 'cumulative'" class="analytics-filter__dates">
            {{ filters.startDate
            }}<template v-if="filters.endDate !== filters.startDate">
              – {{ filters.endDate }}</template
            >
          </span>
        </div>
        <el-button
          class="analytics-filter__refresh"
          native-type="button"
          :loading="loading"
          @click="emit('refresh')"
          >{{ t("analytics.refresh") }}</el-button
        >
      </div>
      <div class="analytics-filter__fields">
        <span class="analytics-filter__label">{{ t("analytics.conditions") }}</span>
        <template
          v-for="field in income ? ['creator', 'status', 'shop'] : ['shop', 'channel', 'creator']"
          :key="field"
        >
          <el-select
            v-if="field === 'shop'"
            v-model="filters.shopId"
            class="analytics-filter__shop"
            clearable
            filterable
            :aria-label="t('analytics.shop')"
            :placeholder="t('analytics.all')"
          >
            <template #prefix>
              <span class="analytics-filter__caption">{{ t("analytics.shop") }}</span>
            </template>
            <template #label="{ label }">
              <span :title="label">{{ selectedShopName ?? label }}</span>
            </template>
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
            v-else-if="field === 'channel'"
            v-model="filters.channel"
            class="analytics-filter__channel"
            clearable
            :aria-label="t('analytics.channel')"
            :placeholder="t('analytics.all')"
          >
            <template #prefix>
              <span class="analytics-filter__caption">{{ t("analytics.channel") }}</span>
            </template>
            <el-option value="WEIDIAN" :label="t('analytics.weidian')" />
          </el-select>
          <el-input
            v-else-if="field === 'creator'"
            v-model="filters.creatorKeyword"
            class="analytics-filter__creator"
            :data-test="income ? 'income-keyword' : undefined"
            clearable
            :maxlength="100"
            :aria-label="t(income ? 'analytics.income.search' : 'analytics.creatorSearch')"
            :placeholder="t(income ? 'analytics.income.search' : 'analytics.creatorSearch')"
          >
            <template #prefix>
              <span class="analytics-filter__caption">{{ t("analytics.creator") }}</span>
              <el-icon class="analytics-filter__search-icon"><Search /></el-icon>
            </template>
          </el-input>
          <el-select
            v-else
            v-model="filters.creatorStatus"
            class="analytics-filter__status"
            clearable
            :aria-label="t('analytics.income.relationship')"
            :placeholder="t('analytics.all')"
          >
            <template #prefix>
              <span class="analytics-filter__caption">{{
                t("analytics.income.relationship")
              }}</span>
            </template>
            <el-option :label="t('kol.relationStatusOption.PROMOTING')" value="PROMOTING" />
            <el-option :label="t('kol.relationStatusOption.INVALID')" value="INVALID" />
          </el-select>
        </template>
        <el-input
          v-if="showInviter"
          v-model="filters.inviterKeyword"
          class="analytics-filter__inviter"
          clearable
          :maxlength="100"
          :aria-label="t('analytics.inviter')"
          :placeholder="t('analytics.inviterSearch')"
        >
          <template #prefix>
            <span class="analytics-filter__caption">{{ t("analytics.inviter") }}</span>
            <el-icon class="analytics-filter__search-icon"><Search /></el-icon>
          </template>
        </el-input>
        <el-input
          v-if="showProduct"
          v-model="filters.productKeyword"
          class="analytics-filter__product"
          clearable
          :maxlength="100"
          :aria-label="t('analytics.productSearch')"
          :placeholder="t('analytics.productSearch')"
        >
          <template #prefix>
            <span class="analytics-filter__caption">{{ t("analytics.product") }}</span>
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
.analytics-filter {
  container: analytics-filter / inline-size;
}
.analytics-filter :deep(.list-search__row) {
  display: grid;
  grid-template-columns: minmax(0, 1fr) max-content;
  row-gap: var(--tcrm-space-4);
}
.analytics-filter__time {
  display: flex;
  grid-column: 1 / -1;
  align-items: center;
  gap: var(--tcrm-space-2);
  min-width: 0;
}
.analytics-filter__refresh {
  margin-inline-start: auto;
}
.analytics-filter__fields {
  --analytics-filter-columns: 3;
  display: grid;
  grid-template-columns: 5em repeat(var(--analytics-filter-columns), minmax(0, 1fr));
  align-items: center;
  gap: var(--tcrm-space-2);
  min-width: 0;
}
.analytics-filter--four-fields .analytics-filter__fields {
  --analytics-filter-columns: 4;
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
}
.analytics-filter__label,
.analytics-filter__caption {
  color: var(--tcrm-color-text);
}
.analytics-filter__period :deep(.el-button) {
  padding-inline: var(--tcrm-space-3);
}
#app .analytics-filter__period :deep(.el-button) {
  font-weight: 400;
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
.analytics-filter__shop,
.analytics-filter__channel,
.analytics-filter__creator,
.analytics-filter__inviter,
.analytics-filter__product,
.analytics-filter__status {
  min-width: 0;
  width: 100%;
}
.analytics-filter__search-icon {
  margin-inline-start: var(--tcrm-space-1);
}
.analytics-filter__failure {
  grid-column: 1 / -1;
  color: var(--el-text-color-secondary);
}
.analytics-filter :deep(.list-search__actions) {
  min-width: 0;
}
@container analytics-filter (max-width: 1370px) {
  .analytics-filter--four-fields .analytics-filter__fields {
    --analytics-filter-columns: 2;
  }
  .analytics-filter--four-fields .analytics-filter__fields > .analytics-filter__label {
    grid-row: span 2;
  }
}
@container analytics-filter (max-width: 1100px) {
  .analytics-filter__fields {
    --analytics-filter-columns: 2;
  }
  .analytics-filter__fields > .analytics-filter__label {
    grid-row: span 2;
  }
}
@container analytics-filter (max-width: 620px) {
  .analytics-filter :deep(.list-search__row),
  .analytics-filter__fields,
  .analytics-filter--four-fields .analytics-filter__fields {
    grid-template-columns: minmax(0, 1fr);
  }
  .analytics-filter__time {
    flex-wrap: wrap;
  }
  .analytics-filter__fields > .analytics-filter__label,
  .analytics-filter--four-fields .analytics-filter__fields > .analytics-filter__label {
    grid-row: auto;
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
}
</style>
