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
    <div class="home-entries">
      <RouterLink class="home-entry" to="/payout-accounts" data-test="home-payout-entry">
        {{ t("home.payoutEntry") }} →
      </RouterLink>
      <RouterLink class="home-entry" to="/orders">{{ t("home.viewOrders") }} →</RouterLink>
    </div>
  </el-card>
</template>

<style scoped>
.home-entries {
  display: flex;
  flex-wrap: wrap;
  gap: var(--tcrm-space-4);
}
.home-entry {
  display: inline-block;
  margin-top: var(--tcrm-space-2);
  color: var(--el-color-primary);
  font-weight: 600;
  text-decoration: none;
}
</style>
