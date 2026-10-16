<script setup lang="ts">
import { useI18n } from "vue-i18n";

defineOptions({ name: "HomePage" });

const { t } = useI18n();
</script>

<template>
  <el-card shadow="never">
    <template #header>{{ t("home.title") }}</template>
    <p>
      {{ t("home.body") }}
    </p>
    <RouterLink to="/orders">{{ t("home.viewOrders") }}</RouterLink>
  </el-card>
</template>
