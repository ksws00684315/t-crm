<script setup lang="ts">
import { computed } from "vue";

defineOptions({ name: "AmountText" });
const props = defineProps<{ value: string }>();
/**
 * A long amount may wrap (规范 13 §6) but only after a thousands separator, never inside a group of
 * digits: "¥12,345," / "678.90" stays readable, "¥12,34" / "5,678.90" does not. The text content is
 * the plain formatted amount; `<wbr>` adds no character.
 */
const parts = computed(() => props.value.split(/(?<=,)/));
</script>

<template>
  <template v-for="(part, index) in parts" :key="index"
    ><wbr v-if="index > 0" />{{ part }}</template
  >
</template>
