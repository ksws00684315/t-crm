<script setup lang="ts">
defineOptions({ name: "NameCode" });

defineProps<{
  name?: string | null;
  code?: string | null;
}>();
</script>

<template>
  <span class="name-code" data-test="name-code">
    <span class="name-code__name" :title="name || undefined" data-test="identity-name">
      <slot>{{ name || "-" }}</slot>
    </span>
    <span class="name-code__code" :title="code || undefined" data-test="identity-code">
      <slot name="code">{{ code || "-" }}</slot>
    </span>
  </span>
</template>

<style scoped>
.name-code {
  display: inline-flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: var(--tcrm-space-1);
  min-width: 0;
  max-width: 100%;
  width: 100%;
  text-align: center;
  vertical-align: middle;
}
.name-code__name,
.name-code__code {
  max-width: 100%;
  overflow-wrap: anywhere;
}
.name-code__name {
  color: var(--tcrm-color-text);
  font-size: var(--tcrm-font-size-body);
  line-height: var(--tcrm-line-height-body);
}
.name-code__code {
  color: var(--tcrm-color-text-secondary);
  font-size: var(--tcrm-font-size-body);
  line-height: var(--tcrm-line-height-body);
}
.name-code__name :deep(.el-button) {
  color: inherit;
  white-space: normal;
  text-align: center;
}
</style>
