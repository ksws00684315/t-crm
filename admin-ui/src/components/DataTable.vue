<script setup lang="ts">
import { Download, FullScreen, Refresh, ScaleToOriginal, Setting } from "@element-plus/icons-vue";
import type { TableInstance } from "element-plus";
import { computed, ref, watch } from "vue";
import { useI18n } from "vue-i18n";
import {
  PAGE_SIZES,
  defaultPrefs,
  loadTablePrefs,
  moveColumn,
  saveTablePrefs,
  withLockedVisible,
  type PrefColumn,
  type TableDensity
} from "@/utils/table-prefs";

defineOptions({ name: "DataTable" });

export interface DataColumn {
  key: string;
  label: string;
  prop?: string;
  width?: number | string;
  minWidth?: number | string;
  amount?: boolean;
  /** Keeps the previous overflow tooltip on long remark and request columns. */
  tooltip?: boolean;
}

const props = withDefaults(
  defineProps<{
    tableId: string;
    rows: readonly object[];
    columns: DataColumn[];
    loading?: boolean;
    total: number;
    page?: number;
    size?: number;
    /** Shop and tenant lists return every row; those tables show a total and do not page. */
    paged?: boolean;
    filterCount?: number;
    rowKey?: string;
    /** Drawers and dialogs keep the table rules without the list toolbar. */
    plain?: boolean;
    /** Shows the toolbar Export button (P0-1); the page does the fetching and file save. */
    exportable?: boolean;
    exporting?: boolean;
    /** Adds a current-page selection column (P0-2). Selection never spans pages. */
    selectable?: boolean;
    /** Rows that may not be selected (e.g. the signed-in account). */
    selectableRow?: (row: object) => boolean;
    /** Adds an expand column in front; the page fills the `expand` slot with the row detail. */
    expandable?: boolean;
    /** Opens every row's detail on render (the matching pool shows lines by default). */
    defaultExpandAll?: boolean;
  }>(),
  {
    loading: false,
    page: 1,
    size: 20,
    paged: true,
    filterCount: 0,
    rowKey: "id",
    plain: false,
    exportable: false,
    exporting: false,
    selectable: false,
    selectableRow: undefined,
    expandable: false,
    defaultExpandAll: false
  }
);

const emit = defineEmits<{
  "update:page": [page: number];
  "update:size": [size: number];
  refresh: [];
  clearFilters: [];
  export: [];
  selectionChange: [rows: object[]];
}>();

const tableRef = ref<TableInstance>();
const selected = ref<object[]>([]);

function onSelection(rows: object[]): void {
  selected.value = rows;
  emit("selectionChange", rows);
}

function clearSelection(): void {
  tableRef.value?.clearSelection();
  if (selected.value.length) onSelection([]);
}

/** New page, new filter or refresh: a selection only ever covers rows on screen. */
watch(
  () => props.rows,
  () => {
    if (selected.value.length) onSelection([]);
  }
);

function canSelect(row: object): boolean {
  return props.selectableRow ? props.selectableRow(row) : true;
}

defineExpose({ clearSelection });

const { t } = useI18n();
const fullscreen = ref(false);

const locked = computed(() => {
  const keys = new Set<string>();
  if (props.columns[0]) keys.add(props.columns[0].key);
  if (props.columns.some(column => column.key === "actions")) keys.add("actions");
  return keys;
});

const columnKeys = computed(() => props.columns.map(column => column.key));

function normalize(columns: PrefColumn[]): PrefColumn[] {
  return withLockedVisible(columns, locked.value);
}

const prefs = ref(
  (() => {
    const loaded = loadTablePrefs(props.tableId, columnKeys.value, props.size);
    return { ...loaded, columns: withLockedVisible(loaded.columns, locked.value) };
  })()
);

watch(columnKeys, keys => {
  const merged = loadTablePrefs(props.tableId, keys, prefs.value.pageSize);
  prefs.value = { ...merged, columns: normalize(merged.columns) };
});

watch(
  prefs,
  value => {
    saveTablePrefs(props.tableId, { ...value, columns: normalize(value.columns) });
  },
  { deep: true }
);

const byKey = computed(() => new Map(props.columns.map(column => [column.key, column])));

const visibleColumns = computed(() =>
  prefs.value.columns
    .filter(column => column.visible)
    .map(column => byKey.value.get(column.key))
    .filter((column): column is DataColumn => Boolean(column))
);

const hiddenCount = computed(
  () =>
    prefs.value.columns.filter(column => !column.visible && !locked.value.has(column.key)).length
);

const range = computed(() => {
  if (!props.paged || props.total === 0) return { from: 0, to: 0 };
  const from = (props.page - 1) * props.size + 1;
  const to = Math.min(props.page * props.size, props.total);
  return { from, to };
});

const densityOptions = computed(() => [
  { label: t("table.densityDefault"), value: "default" },
  { label: t("table.densityCompact"), value: "small" },
  { label: t("table.densityLoose"), value: "large" }
]);

function setDensity(value: TableDensity): void {
  prefs.value = { ...prefs.value, density: value };
}

function toggleColumn(key: string, visible: boolean): void {
  if (locked.value.has(key)) return;
  prefs.value = {
    ...prefs.value,
    columns: normalize(
      prefs.value.columns.map(column => (column.key === key ? { ...column, visible } : column))
    )
  };
}

function move(index: number, delta: -1 | 1): void {
  prefs.value = {
    ...prefs.value,
    columns: normalize(moveColumn(prefs.value.columns, index, delta, locked.value))
  };
}

function resetColumns(): void {
  prefs.value = {
    ...prefs.value,
    columns: normalize(defaultPrefs(columnKeys.value, prefs.value.pageSize).columns)
  };
}

function onSize(size: number): void {
  prefs.value = { ...prefs.value, pageSize: size };
  emit("update:size", size);
  emit("update:page", 1);
}

function cellText(row: unknown, column: DataColumn): string {
  if (!row || typeof row !== "object") return "";
  const value = (row as Record<string, unknown>)[column.prop ?? column.key];
  return value == null ? "" : String(value);
}

function canMove(index: number, delta: -1 | 1): boolean {
  const target = index + delta;
  const columns = prefs.value.columns;
  if (target < 0 || target >= columns.length) return false;
  return !locked.value.has(columns[index].key) && !locked.value.has(columns[target].key);
}
</script>

<template>
  <section
    class="data-table"
    :class="[{ 'is-fullscreen': fullscreen, 'is-plain': plain }, `density-${prefs.density}`]"
  >
    <header v-if="!plain" class="data-table__toolbar">
      <div v-if="selectable && selected.length" class="data-table__batch" data-test="batch-bar">
        <span class="data-table__batch-count">{{
          t("table.selected", { n: selected.length })
        }}</span>
        <span class="data-table__batch-hint">{{ t("table.selectedPageOnly") }}</span>
        <slot name="batch" :rows="selected" />
        <el-button text data-test="batch-clear" @click="clearSelection">{{
          t("table.clearSelection")
        }}</el-button>
      </div>
      <span v-else class="data-table__total">{{ t("table.total", { total }) }}</span>
      <div class="data-table__tools">
        <el-segmented
          :model-value="prefs.density"
          :options="densityOptions"
          size="small"
          @update:model-value="setDensity($event as TableDensity)"
        />
        <el-button
          v-if="exportable"
          class="data-table__export"
          :loading="exporting"
          :disabled="total === 0"
          data-test="export"
          @click="emit('export')"
        >
          <el-icon v-if="!exporting"><Download /></el-icon>
          <span>{{ t("table.export") }}</span>
        </el-button>
        <el-button text :aria-label="t('table.refresh')" @click="emit('refresh')">
          <el-icon><Refresh /></el-icon>
        </el-button>
        <el-popover trigger="click" :width="280" placement="bottom-end">
          <template #reference>
            <el-button text data-test="column-settings">
              <el-icon><Setting /></el-icon>
              <span>{{
                hiddenCount ? t("table.hidden", { n: hiddenCount }) : t("table.columns")
              }}</span>
            </el-button>
          </template>
          <div class="data-table__columns">
            <div
              v-for="(column, index) in prefs.columns"
              :key="column.key"
              class="data-table__column"
            >
              <el-checkbox
                :model-value="column.visible"
                :disabled="locked.has(column.key)"
                @change="toggleColumn(column.key, Boolean($event))"
              >
                {{ byKey.get(column.key)?.label ?? column.key }}
              </el-checkbox>
              <el-button
                text
                size="small"
                :disabled="!canMove(index, -1)"
                :aria-label="t('table.moveUp')"
                @click="move(index, -1)"
              >
                {{ t("table.moveUp") }}
              </el-button>
              <el-button
                text
                size="small"
                :disabled="!canMove(index, 1)"
                :aria-label="t('table.moveDown')"
                @click="move(index, 1)"
              >
                {{ t("table.moveDown") }}
              </el-button>
            </div>
            <el-button size="small" @click="resetColumns">{{ t("table.reset") }}</el-button>
          </div>
        </el-popover>
        <el-button
          text
          :aria-label="fullscreen ? t('table.exitFullscreen') : t('table.fullscreen')"
          @click="fullscreen = !fullscreen"
        >
          <el-icon><ScaleToOriginal v-if="fullscreen" /><FullScreen v-else /></el-icon>
        </el-button>
      </div>
    </header>
    <div class="data-table__body">
      <el-table
        ref="tableRef"
        v-loading="loading"
        :data="rows"
        :row-key="rowKey"
        size="default"
        :height="plain ? undefined : '100%'"
        :default-expand-all="defaultExpandAll"
        stripe
        @selection-change="onSelection"
      >
        <el-table-column
          v-if="expandable"
          type="expand"
          width="40"
          fixed="left"
          class-name="tcrm-expand"
        >
          <template #default="{ row }">
            <slot name="expand" :row="row" />
          </template>
        </el-table-column>
        <el-table-column
          v-if="selectable && !plain"
          type="selection"
          width="44"
          fixed="left"
          :selectable="canSelect"
        />
        <el-table-column
          v-for="column in visibleColumns"
          :key="column.key"
          :prop="column.key === 'actions' ? undefined : (column.prop ?? column.key)"
          :label="column.label"
          :width="column.width"
          :min-width="column.minWidth"
          :fixed="
            column.key === 'actions' ? 'right' : column.key === columns[0]?.key ? 'left' : undefined
          "
          :align="column.amount ? 'right' : undefined"
          :show-overflow-tooltip="column.tooltip"
          :class-name="column.amount ? 'tcrm-amount' : undefined"
        >
          <template #default="{ row }">
            <template v-if="column.key === 'actions'">
              <slot name="actions" :row="row" />
            </template>
            <template v-else>
              <slot :name="`cell-${column.key}`" :row="row">
                {{ cellText(row, column) }}
              </slot>
            </template>
          </template>
        </el-table-column>
        <template #empty>
          <div class="data-table__empty">
            <p v-if="filterCount > 0">{{ t("table.emptyFiltered", { n: filterCount }) }}</p>
            <p v-else>{{ t("table.empty") }}</p>
            <el-button v-if="filterCount > 0" @click="emit('clearFilters')">
              {{ t("table.clearFilters") }}
            </el-button>
          </div>
        </template>
      </el-table>
    </div>
    <footer v-if="paged" class="data-table__pager">
      <span>{{ t("table.range", { from: range.from, to: range.to, total }) }}</span>
      <el-pagination
        background
        layout="sizes, prev, pager, next"
        :page-sizes="[...PAGE_SIZES]"
        :total="total"
        :current-page="page"
        :page-size="prefs.pageSize"
        @current-change="emit('update:page', $event)"
        @size-change="onSize"
      />
    </footer>
  </section>
</template>

<style scoped>
.data-table {
  display: flex;
  flex-direction: column;
  gap: var(--tcrm-space-2);
  flex: 1;
  min-height: 0;
  height: auto;
  padding: var(--tcrm-space-3);
  background: var(--tcrm-color-card);
  border: 1px solid var(--tcrm-color-border);
  border-radius: var(--tcrm-radius-card);
}
.data-table__batch {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: var(--tcrm-space-2);
}
.data-table__batch-count {
  font-weight: 600;
  color: var(--tcrm-color-primary-text, var(--el-color-primary));
}
.data-table__batch-hint {
  font-size: 12px;
  line-height: 20px;
  color: var(--tcrm-color-text-secondary);
}
.data-table.is-plain {
  flex: initial;
  height: auto;
  min-height: 0;
  padding: 0;
  border: none;
  background: transparent;
}
.data-table.is-fullscreen {
  position: fixed;
  z-index: 30;
  top: calc(var(--tcrm-header-height) + 40px);
  right: 0;
  bottom: 0;
  left: var(--layout-aside-width, 0px);
  height: auto;
  border-radius: 0;
}
.data-table__toolbar,
.data-table__pager,
.data-table__column {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--tcrm-space-2);
  flex-shrink: 0;
}
.data-table__tools {
  display: flex;
  align-items: center;
  gap: var(--tcrm-space-1);
}
.data-table__total {
  color: var(--tcrm-color-text-secondary);
}
.data-table__body {
  flex: 1;
  min-height: 0;
  /* el-table height=100% owns vertical and horizontal scroll; no outer overflow */
  overflow: hidden;
}
.data-table__columns {
  display: flex;
  flex-direction: column;
  gap: var(--tcrm-space-1);
}
.data-table__empty {
  padding: var(--tcrm-space-4) 0;
  color: var(--tcrm-color-text-secondary);
}
.data-table :deep(.el-table__header th) {
  background: var(--tcrm-color-muted);
}
.data-table :deep(.el-table .el-table__cell) {
  padding-block: 12px;
}
.data-table.density-small :deep(.el-table .el-table__cell) {
  padding-block: 8px;
}
.data-table.density-large :deep(.el-table .el-table__cell) {
  padding-block: 16px;
}
.data-table :deep(.el-table__body tr:hover > td) {
  background: var(--tcrm-color-muted);
}
</style>
