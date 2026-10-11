<script setup lang="ts">
import { useI18n } from "vue-i18n";

defineOptions({ name: "NotFoundPage" });

const { t } = useI18n();
</script>

<template>
  <el-result icon="info" title="404" :sub-title="t('route.notFound')">
    <template #extra>
      <el-button type="primary" @click="$router.push('/')">{{ t("common.backToHome") }}</el-button>
    </template>
  </el-result>
</template>
