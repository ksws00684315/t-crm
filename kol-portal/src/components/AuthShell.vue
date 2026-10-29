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
  padding: var(--tcrm-space-6) var(--tcrm-space-4);
  padding-top: calc(var(--tcrm-space-4) + var(--tcrm-control-height-mobile) + var(--tcrm-space-4));
}
.auth-shell__bar {
  position: absolute;
  top: var(--tcrm-space-4);
  right: var(--tcrm-space-4);
}
.auth-shell__card {
  width: 100%;
  max-width: 380px;
}
h1 {
  margin: 0 0 var(--tcrm-space-5);
  font-size: var(--tcrm-font-size-title);
  line-height: var(--tcrm-line-height-title);
  text-align: center;
}
:deep(.auth-submit) {
  width: 100%;
  margin-top: var(--tcrm-space-2);
}
:deep(.el-alert) {
  margin-bottom: var(--tcrm-space-4);
}
:deep(.auth-links) {
  display: flex;
  flex-wrap: wrap;
  justify-content: center;
  gap: var(--tcrm-space-4);
  margin-top: var(--tcrm-space-4);
}
/* Links inside the auth card follow the admin console: primary colour, underline on hover only. */
:deep(a) {
  color: var(--tcrm-color-primary-text);
  text-decoration: none;
}
:deep(a:hover) {
  text-decoration: underline;
}
@media (max-width: 767px) {
  :deep(.el-button),
  :deep(.el-input__wrapper) {
    min-height: var(--tcrm-control-height-mobile);
  }
  .auth-shell__card {
    max-width: none;
  }
}
</style>
