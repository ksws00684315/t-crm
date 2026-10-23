<script setup lang="ts">
import { useI18n } from "vue-i18n";
import { financePreview } from "@/utils/finance";
const { t } = useI18n();
</script>
<template>
  <div v-if="financePreview" class="finance-preview-notice" data-test="finance-preview-notice">
    <el-alert :title="t('finance.integratedPreview')" type="warning" :closable="false" show-icon />
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
@media (max-width: 767px) {
  .finance-preview-notice {
    align-items: stretch;
  }
  .finance-preview-notice :deep(.el-alert) {
    flex-basis: 100%;
  }
}
</style>
