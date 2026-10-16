<script setup lang="ts">
import { computed } from "vue";
import { useI18n } from "vue-i18n";
import type { SheetSkippedVO } from "@/api/types";

defineOptions({ name: "SkippedRows" });

const { t } = useI18n();
const props = defineProps<{ skipped: SheetSkippedVO }>();

const problems = computed(
  () => props.skipped.nonWeidian + props.skipped.noItemId + props.skipped.noShop
);
</script>

<template>
  <div class="skipped" data-test="skipped">
    <p v-if="skipped.empty > 0" class="muted">
      {{ t("sheetSource.skippedEmpty", { count: skipped.empty }) }}
    </p>
    <template v-if="problems > 0">
      <h4>{{ t("sheetSource.skippedTitle") }} ({{ problems }})</h4>
      <el-table :data="skipped.samples" size="small" max-height="240">
        <el-table-column prop="row" :label="t('sheetSource.row')" width="80" />
        <el-table-column :label="t('sheetSource.reason')" min-width="180">
          <template #default="{ row }">{{ t(`sheetSource.skippedReason.${row.reason}`) }}</template>
        </el-table-column>
        <el-table-column prop="name" :label="t('sheetSource.name')" min-width="160" />
      </el-table>
    </template>
  </div>
</template>

<style scoped>
.skipped h4 {
  margin: 12px 0 8px;
  font-size: 14px;
}

.muted {
  color: var(--tcrm-color-text-secondary);
  font-size: 13px;
}
</style>
