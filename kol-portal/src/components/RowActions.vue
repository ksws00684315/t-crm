<!-- Ported from admin-ui/src/components/RowActions.vue (task 58). Identical behaviour and styling; keep both in sync. -->
<script lang="ts">
/** One row-level action entry; pages keep declaring order, permissions, and disabled rules. */
export interface RowAction {
  key: string;
  label: string;
  testId?: string;
  /** Link color; defaults to primary. */
  type?: "primary" | "danger" | "warning" | "success";
  /** Red label regardless of type — e.g. the cleanup countdown. */
  danger?: boolean;
  disabled?: boolean;
  /** Hint for disabled entries (e.g. "under development" or "no KOLs to transfer"). */
  tooltip?: string;
  /** Row-level visibility; hidden entries are not rendered. */
  hidden?: boolean;
  onClick?: () => void;
}
</script>

<script setup lang="ts">
import { computed } from "vue";

/**
 * Row action links. Every visible action renders inline in the declared order; there is no
 * "More" menu (owner decision 2026-10-21, ADR-029), so pages size their action column to fit.
 */
const props = defineProps<{ actions: RowAction[] }>();

const items = computed(() => props.actions.filter(action => !action.hidden));

function run(action: RowAction): void {
  if (!action.disabled) {
    action.onClick?.();
  }
}
</script>

<template>
  <div class="actions-cell" data-test="row-actions">
    <el-tooltip
      v-for="action in items"
      :key="action.key"
      :disabled="!action.tooltip"
      :content="action.tooltip ?? ''"
      placement="top"
    >
      <span class="actions-cell__wrap">
        <el-button
          link
          :type="action.type ?? 'primary'"
          :class="{ 'actions-cell__danger': action.danger }"
          :data-test="action.testId"
          :disabled="action.disabled"
          @click="run(action)"
          >{{ action.label }}</el-button
        >
      </span>
    </el-tooltip>
  </div>
</template>

<style scoped>
.actions-cell {
  display: inline-flex;
  flex-wrap: nowrap;
  gap: 0 var(--tcrm-space-2);
  align-items: center;
}

.actions-cell__wrap {
  display: inline-flex;
}

.actions-cell :deep(.el-button) {
  margin: 0;
  padding: 0;
  height: auto;
  white-space: nowrap;
}

.actions-cell .actions-cell__danger {
  color: var(--tcrm-color-danger);
}
</style>
