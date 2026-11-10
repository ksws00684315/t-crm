<script setup lang="ts">
import { useI18n } from "vue-i18n";
import ListSearch from "@/components/ListSearch.vue";
import { formatNameWithCode } from "@/utils/format";
import type { Board, Filters, Options } from "@/api/analytics";
import { datePreset, dates } from "./board";

defineProps<{ board: Board; options?: Options; loading: boolean; optionsFailed: boolean }>();
const filters = defineModel<Filters>({ required: true });
const emit = defineEmits<{ search: []; reset: []; refresh: [] }>();
const { t } = useI18n();

function preset(value: number | "today" | "yesterday" | "month"): void {
  filters.value = {
    ...filters.value,
    ...(typeof value === "number" ? dates(value) : datePreset(value))
  };
}
</script>

<template>
  <ListSearch @search="emit('search')" @reset="emit('reset')">
    <template #actions>
      <el-button :loading="loading" @click="emit('refresh')">{{
        t("analytics.refresh")
      }}</el-button>
    </template>
    <template #primary>
      <div class="analytics-filter__period">
        <el-button
          v-for="choice in ['today', 'yesterday', 'month'] as const"
          :key="choice"
          native-type="button"
          @click="preset(choice)"
          >{{ t(`analytics.preset.${choice}`) }}</el-button
        >
        <el-button
          v-for="days in [7, 30, 90]"
          :key="days"
          native-type="button"
          @click="preset(days)"
        >
          {{ t("analytics.lastDays", { days }) }}
        </el-button>
        <el-date-picker
          v-model="filters.startDate"
          :aria-label="t('analytics.startDate')"
          :placeholder="t('analytics.startDate')"
          value-format="YYYY-MM-DD"
          type="date"
          :clearable="false"
        />
        <el-date-picker
          v-model="filters.endDate"
          :aria-label="t('analytics.endDate')"
          :placeholder="t('analytics.endDate')"
          value-format="YYYY-MM-DD"
          type="date"
          :clearable="false"
        />
      </div>
      <el-select
        v-model="filters.shopId"
        clearable
        filterable
        :aria-label="t('analytics.shop')"
        :placeholder="t('analytics.shop')"
      >
        <el-option
          v-for="option in options?.shops"
          :key="option.id"
          :value="option.id"
          :label="formatNameWithCode(option.name, option.code)"
        />
      </el-select>
      <el-select
        v-model="filters.channel"
        clearable
        :aria-label="t('analytics.channel')"
        :placeholder="t('analytics.channel')"
      >
        <el-option value="WEIDIAN" :label="t('analytics.weidian')" />
      </el-select>
      <el-select
        v-if="board === 'shop-performance' || board === 'best-sellers'"
        v-model="filters.kolId"
        clearable
        filterable
        :aria-label="t('analytics.creator')"
        :placeholder="t('analytics.creator')"
      >
        <el-option
          v-for="option in options?.creators"
          :key="option.id"
          :value="option.id"
          :label="formatNameWithCode(option.name, option.code)"
        />
      </el-select>
      <el-select
        v-if="board === 'kol-ranking'"
        v-model="filters.inviterId"
        clearable
        filterable
        :aria-label="t('analytics.inviter')"
        :placeholder="t('analytics.inviter')"
      >
        <el-option
          v-for="option in options?.inviters"
          :key="option.id"
          :value="option.id"
          :label="option.name"
        />
      </el-select>
      <el-input
        v-if="board !== 'shop-performance'"
        v-model="filters.keyword"
        clearable
        :maxlength="100"
        :aria-label="t('analytics.keyword')"
        :placeholder="t(`analytics.${board}.keyword`)"
      />
      <span v-if="optionsFailed" role="status">{{ t("analytics.optionsFailed") }}</span>
    </template>
  </ListSearch>
</template>

<style scoped>
.analytics-filter__period {
  display: flex;
  flex: 0 0 100%;
  flex-wrap: wrap;
  gap: var(--tcrm-space-2);
}
.analytics-filter__period :deep(.el-button + .el-button) {
  margin-left: 0;
}
.analytics-filter__period :deep(.el-date-editor) {
  width: 148px;
  max-width: 100%;
}
:deep(.el-select),
:deep(.el-input) {
  max-width: 100%;
}
</style>
