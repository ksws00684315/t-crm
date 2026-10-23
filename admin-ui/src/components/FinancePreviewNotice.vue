<script setup lang="ts">
import { useI18n } from "vue-i18n";
import { financePreview, previewContext } from "@/utils/finance";
const { t } = useI18n();
</script>
<template>
  <div v-if="financePreview" class="finance-preview-notice" data-test="finance-preview-notice">
    <el-alert :title="t('finance.integratedPreview')" type="warning" :closable="false" show-icon />
    <el-select
      v-model="previewContext.scenario"
      :aria-label="t('finance.scenario')"
      data-test="finance-scenario"
    >
      <el-option
        v-for="scenario in ['data', 'empty', 'long', 'error']"
        :key="scenario"
        :label="t(`finance.${scenario}`)"
        :value="scenario"
      />
    </el-select>
  </div>
</template>
<style scoped>
.finance-preview-notice {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: var(--tcrm-space-3);
  flex-shrink: 0;
}
.finance-preview-notice :deep(.el-alert) {
  flex: 1;
  min-width: 0;
}
.finance-preview-notice :deep(.el-select) {
  width: 160px;
}
@media (max-width: 767px) {
  .finance-preview-notice {
    align-items: stretch;
  }
  .finance-preview-notice :deep(.el-alert) {
    flex-basis: 100%;
  }
}
</style>
