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
  gap: 16px;
}
.home-entry {
  display: inline-block;
  margin-top: 8px;
  font-weight: 600;
}
</style>
