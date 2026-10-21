<script lang="ts">
/** One row-level action entry; pages keep declaring order, permissions, and disabled rules. */
export interface RowAction {
  key: string;
  label: string;
  testId?: string;
  /** Link color for visible entries; also tints the dropdown item for warning/danger types. */
  type?: "primary" | "danger" | "warning" | "success";
  /** Red label regardless of type — the cleanup countdown must stay readable in the dropdown. */
  danger?: boolean;
  disabled?: boolean;
  /** Hint for disabled entries (e.g. "under development" or "no KOLs to transfer"). */
  tooltip?: string;
  /** Row-level visibility; hidden entries are removed before splitting visible/overflow. */
  hidden?: boolean;
  onClick?: () => void;
}
</script>

<script setup lang="ts">
import { computed } from "vue";
import { useI18n } from "vue-i18n";

/**
 * Row action links following the visual spec: up to `maxVisible` common actions render
 * directly, the remaining ones collapse into a "More" dropdown so the action column keeps
 * its narrow width. The dropdown opens on click and disabled entries keep their tooltip.
 */
const props = withDefaults(defineProps<{ actions: RowAction[]; maxVisible?: number }>(), {
  maxVisible: 2
});

const { t } = useI18n();
const items = computed(() => props.actions.filter(action => !action.hidden));
const visible = computed(() => items.value.slice(0, props.maxVisible));
const overflow = computed(() => items.value.slice(props.maxVisible));

function run(action: RowAction): void {
  if (!action.disabled) {
    action.onClick?.();
  }
}

function runByKey(key: string | number | object): void {
  const action = items.value.find(item => item.key === key);
  if (action) {
    run(action);
  }
}
</script>

<template>
  <div class="actions-cell" data-test="row-actions">
    <el-tooltip
      v-for="action in visible"
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
    <el-dropdown v-if="overflow.length" trigger="click" @command="runByKey">
      <el-button link type="primary" data-test="more-actions">{{
        t("common.moreActions")
      }}</el-button>
      <template #dropdown>
        <el-dropdown-menu>
          <el-dropdown-item
            v-for="action in overflow"
            :key="action.key"
            :command="action.key"
            :disabled="action.disabled"
            :data-test="action.testId"
            :title="action.disabled ? action.tooltip : undefined"
            :class="{
              'actions-cell__danger': action.danger,
              'actions-cell__warning': action.type === 'warning'
            }"
          >
            {{ action.label }}
          </el-dropdown-item>
        </el-dropdown-menu>
      </template>
    </el-dropdown>
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
