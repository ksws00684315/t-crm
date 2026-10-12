<script setup lang="ts">
import { useI18n } from "vue-i18n";

defineOptions({ name: "ForbiddenPage" });
const { t } = useI18n();
</script>

<template>
  <el-result icon="warning" title="403" :sub-title="t('forbidden.subtitle')">
    <template #extra>
      <el-button type="primary" @click="$router.push('/')">{{ t("common.backToHome") }}</el-button>
    </template>
  </el-result>
</template>
