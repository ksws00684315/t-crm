<script setup lang="ts">
import { computed } from "vue";
import { useI18n } from "vue-i18n";
import { useUserStore } from "@/stores/user";
import { roleLabel } from "@/utils/permission";

defineOptions({ name: "HomePage" });
const { t } = useI18n();
const user = useUserStore();
const greeting = computed(() =>
  user.user
    ? t("home.greeting", { nickname: user.user.nickname, role: roleLabel(user.user.role) })
    : ""
);
</script>

<template>
  <el-card class="home" shadow="never">
    <template #header
      ><h1 class="home__title">{{ t("home.welcome") }}</h1></template
    >
    <p v-if="user.user" class="home__greeting">{{ greeting }}</p>
    <p class="home__body">{{ t("home.body") }}</p>
  </el-card>
</template>

<style scoped>
.home__title {
  margin: 0;
}
.home__greeting,
.home__body {
  margin: 0;
  color: var(--tcrm-color-text);
}
.home__body {
  margin-top: var(--tcrm-space-2);
  color: var(--tcrm-color-text-secondary);
}
</style>
