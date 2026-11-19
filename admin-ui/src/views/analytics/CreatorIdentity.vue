<script setup lang="ts">
import CopyText from "@/components/CopyText.vue";

defineProps<{ name?: string | null; code?: string | null; heading?: boolean }>();
</script>

<template>
  <div class="creator-identity">
    <component
      :is="heading ? 'h2' : 'span'"
      class="creator-identity__name"
      :title="name || undefined"
    >
      {{ name || "—" }}
    </component>
    <CopyText v-if="code" :value="code" class="creator-identity__code" />
  </div>
</template>

<style scoped>
.creator-identity {
  min-width: 0;
  display: grid;
  justify-items: start;
  gap: var(--tcrm-space-1);
  text-align: left;
  line-height: var(--tcrm-line-height-body);
}
.creator-identity__name {
  min-width: 0;
  max-width: 100%;
  margin: 0;
  overflow: hidden;
  color: var(--tcrm-color-text);
  font-size: var(--tcrm-font-size-body);
  font-weight: 400;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.creator-identity__code {
  color: var(--tcrm-color-text-secondary);
  font-size: var(--tcrm-font-size-body);
}
@media (hover: hover) {
  .creator-identity__code :deep([data-test="copy"]) {
    opacity: 0;
  }
  .creator-identity:hover .creator-identity__code :deep([data-test="copy"]),
  .creator-identity:focus-within .creator-identity__code :deep([data-test="copy"]) {
    opacity: 1;
  }
}
</style>
