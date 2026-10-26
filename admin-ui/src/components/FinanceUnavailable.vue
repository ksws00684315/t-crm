<script setup lang="ts">
import { useI18n } from "vue-i18n";

const { t } = useI18n();
</script>

<template>
  <el-result
    icon="info"
    :title="t('finance.developing')"
    :sub-title="t('finance.backendNotReady')"
  />
</template>
