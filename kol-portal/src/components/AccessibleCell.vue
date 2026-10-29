<script setup lang="ts">
import { onMounted, onUpdated, ref } from "vue";

defineOptions({ name: "AccessibleCell" });

const element = ref<HTMLElement | null>(null);
const fullText = ref("");
const hasFocusable = ref(false);
const focused = ref(false);

function updateText(): void {
  fullText.value = element.value?.textContent?.trim() ?? "";
  hasFocusable.value = !!element.value?.querySelector(
    'a[href], button:not([disabled]), input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])'
  );
}

function onFocusIn(event: FocusEvent): void {
  updateText();
  const target = event.target as HTMLElement | null;
  focused.value = !!fullText.value && !target?.closest(".copy-text");
}

function onFocusOut(event: FocusEvent): void {
  if (!element.value?.contains(event.relatedTarget as Node | null)) focused.value = false;
}

onMounted(updateText);
onUpdated(updateText);
</script>

<template>
  <el-tooltip :content="fullText" :visible="focused" :trigger-keys="[]" placement="top">
    <span
      ref="element"
      class="accessible-cell"
      :tabindex="hasFocusable ? -1 : 0"
      @focusin="onFocusIn"
      @focusout="onFocusOut"
    >
      <slot />
    </span>
  </el-tooltip>
</template>

<style scoped>
.accessible-cell {
  display: block;
  min-width: 0;
  max-width: 100%;
  text-align: left;
}
</style>
