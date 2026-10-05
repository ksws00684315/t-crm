<script setup lang="ts">
import { ROLE_LABELS } from "@/utils/permission";
import { useUserStore } from "@/stores/user";

defineOptions({ name: "HomePage" });
const user = useUserStore();
</script>

<template>
  <el-card shadow="never">
    <template #header>欢迎使用 T-CRM</template>
    <p v-if="user.user">
      {{ user.user.nickname }}，你好。当前角色：{{ ROLE_LABELS[user.user.role] }}。
    </p>
    <p>业务功能将在后续阶段陆续上线，目前可使用账号管理相关功能。</p>
  </el-card>
</template>
