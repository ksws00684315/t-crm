<script setup lang="ts">
import { ref } from "vue";

defineProps<{
  items: { code: string; name: string; desc: string; checked: boolean }[];
  disabled?: boolean;
}>();
const emit = defineEmits<{ toggle: [code: string, checked: boolean] }>();
const hovered = ref<string | null>(null);
const focused = ref<string | null>(null);
</script>

<template>
  <div class="permission-choices">
    <el-tooltip
      v-for="item in items"
      :key="item.code"
      :content="item.desc"
      :disabled="!item.desc"
      :visible="!!item.desc && (hovered === item.code || focused === item.code)"
      :trigger-keys="[]"
      placement="top"
    >
      <span
        class="permission-choice"
        @mouseenter="hovered = item.code"
        @mouseleave="hovered = null"
        @focusin="focused = item.code"
        @focusout="focused = null"
      >
        <el-checkbox
          :model-value="item.checked"
          :disabled="disabled"
          :data-test="`template-permission-${item.code}`"
          @change="emit('toggle', item.code, $event === true)"
          >{{ item.name
          }}<span v-if="item.desc" class="sr-only"> {{ item.desc }}</span></el-checkbox
        >
      </span>
    </el-tooltip>
  </div>
</template>

<style scoped>
.permission-choices {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: var(--tcrm-space-2) var(--tcrm-space-5);
  min-width: 0;
}
.permission-choice {
  min-width: 0;
  max-width: 100%;
}
.permission-choice :deep(.el-checkbox) {
  height: auto;
  min-height: 32px;
  margin: 0;
  white-space: normal;
}
#app .permission-choice :deep(.el-checkbox__label) {
  font-size: var(--tcrm-font-size-table) !important;
  line-height: var(--tcrm-line-height-table) !important;
  white-space: normal;
  overflow-wrap: anywhere;
}
.sr-only {
  position: absolute;
  width: 1px;
  height: 1px;
  padding: 0;
  overflow: hidden;
  clip-path: inset(50%);
  white-space: nowrap;
}
@media (max-width: 767px) {
  .permission-choices {
    display: grid;
    grid-template-columns: minmax(0, 1fr);
    gap: var(--tcrm-space-2);
  }
}
</style>
