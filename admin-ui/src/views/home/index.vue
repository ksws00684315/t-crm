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
  <el-card shadow="never">
    <template #header>{{ t("home.welcome") }}</template>
    <p v-if="user.user">{{ greeting }}</p>
    <p>{{ t("home.body") }}</p>
  </el-card>
</template>
