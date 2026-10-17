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
    <RouterLink class="home-entry" to="/payout-accounts" data-test="home-payout-entry">
      {{ t("home.payoutEntry") }} →
    </RouterLink>
  </el-card>
</template>

<style scoped>
.home-entry {
  display: inline-block;
  margin-top: 8px;
  font-weight: 600;
}
</style>
