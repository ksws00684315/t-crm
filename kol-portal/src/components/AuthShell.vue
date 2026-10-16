<script setup lang="ts">
import LocaleSwitch from "@/components/LocaleSwitch.vue";
defineProps<{ title: string }>();
</script>

<template>
  <main class="auth-shell">
    <div class="auth-shell__bar"><LocaleSwitch /></div>
    <el-card class="auth-shell__card" shadow="never">
      <h1>{{ title }}</h1>
      <slot />
    </el-card>
  </main>
</template>

<style scoped>
.auth-shell {
  position: relative;
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 100%;
  padding: 80px 16px 32px;
}
.auth-shell__bar {
  position: absolute;
  top: 16px;
  right: 16px;
}
.auth-shell__card {
  width: 100%;
  max-width: 420px;
}
h1 {
  margin: 0 0 24px;
  font-size: 22px;
  text-align: center;
}
:deep(.el-button) {
  min-height: 44px;
}
:deep(.el-input__wrapper) {
  min-height: 44px;
}
:deep(.auth-submit) {
  width: 100%;
  margin-top: 8px;
}
:deep(.el-alert) {
  margin-bottom: 20px;
}
:deep(.auth-links) {
  display: flex;
  justify-content: center;
  margin-top: 20px;
}
@media (max-width: 480px) {
  .auth-shell__card {
    max-width: none;
  }
}
</style>
