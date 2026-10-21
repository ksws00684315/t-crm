<script setup lang="ts">
import { useI18n } from "vue-i18n";

defineOptions({ name: "NotFoundPage" });
const { t } = useI18n();
</script>

<template>
  <el-result class="not-found" icon="info" title="404" :sub-title="t('notFound.subtitle')">
    <template #extra>
      <el-button type="primary" @click="$router.push('/')">{{ t("common.backToHome") }}</el-button>
    </template>
  </el-result>
</template>

<style scoped>
.not-found {
  min-height: 100%;
  display: flex;
  flex-direction: column;
  justify-content: center;
}
</style>
