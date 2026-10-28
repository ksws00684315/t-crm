<script setup lang="ts">
import { Download, FullScreen, Refresh, ScaleToOriginal, Setting } from "@element-plus/icons-vue";
import type { TableInstance, TableProps } from "element-plus";
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from "vue";
import { useI18n } from "vue-i18n";
import AccessibleCell from "@/components/AccessibleCell.vue";
import {
  PAGE_SIZES,
  applyDefaultHidden,
  defaultPrefs,
  hasSavedTablePrefs,
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
  /** Long content is left aligned; short content is centered by default. */
  align?: "left" | "center";
  /** Form controls supply their own focus handling and per-control descriptions. */
  interactive?: boolean;
  /** Keeps the previous overflow tooltip on long remark and request columns. */
  tooltip?: boolean;
  /** Hidden until the user shows it. Ignored once this table has saved column prefs. */
  defaultHidden?: boolean;
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
    /**
     * Body-row class, e.g. to mark the record a navigation landed on (task 59 R-02): a string for
     * every row or a per-row function, handed straight to el-table.
     */
    rowClassName?: string | ((scope: { row: object; rowIndex: number }) => string);
    /** Optional page-scoped merged cells (used by grouped order rows). */
    spanMethod?: (
      data: Parameters<NonNullable<TableProps<object>["spanMethod"]>>[0] & {
        visibleColumnCount: number;
      }
    ) => ReturnType<NonNullable<TableProps<object>["spanMethod"]>>;
    /** Grouped rows cannot merge correctly across Element Plus fixed-column clones. */
    fixedColumns?: boolean;
    /** Preserve the independently approved order-list presentation from PR #138. */
    legacyOrderStyle?: boolean;
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
    defaultExpandAll: false,
    fixedColumns: true,
    legacyOrderStyle: false
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

function resolveSpanMethod(
  scope: Parameters<NonNullable<TableProps<object>["spanMethod"]>>[0]
): ReturnType<NonNullable<TableProps<object>["spanMethod"]>> {
  return props.spanMethod?.({ ...scope, visibleColumnCount: visibleColumns.value.length });
}

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

/**
 * Card height follows the rows (the owner's 2026-10-20 review compares the console with the
 * generated preview, where the pager sits directly under the last row). The page still owns a
 * definite height, so the table gets a measured ceiling instead of the old `height: 100%`:
 * short pages shrink, long pages scroll inside the body and keep the pager on screen.
 */
const rootEl = ref<HTMLElement | null>(null);
const tableMaxHeight = ref<number | null>(null);
let resizeObserver: ResizeObserver | null = null;
let measureFrame = 0;

function measureHeights(): void {
  if (props.plain) {
    tableMaxHeight.value = null;
    return;
  }
  const el = rootEl.value;
  const host = el?.parentElement;
  if (!el || !host) return;
  const pageHeight = host.clientHeight;
  if (!pageHeight) return;
  const hostStyle = getComputedStyle(host);
  const inFlowChildren = [...host.children].filter(child => {
    const childStyle = getComputedStyle(child);
    return childStyle.display !== "none" && !["absolute", "fixed"].includes(childStyle.position);
  });
  const siblingHeight = inFlowChildren.reduce(
    (height, child) => height + (child === el ? 0 : (child as HTMLElement).offsetHeight),
    0
  );
  const hostPadding =
    parseFloat(hostStyle.paddingTop || "0") + parseFloat(hostStyle.paddingBottom || "0");
  const hostGap =
    (parseFloat(hostStyle.rowGap || "0") || 0) * Math.max(0, inFlowChildren.length - 1);
  const toolbar = el.querySelector<HTMLElement>(".data-table__toolbar")?.offsetHeight ?? 0;
  const pager = el.querySelector<HTMLElement>(".data-table__pager")?.offsetHeight ?? 0;
  const style = getComputedStyle(el);
  const frameHeight =
    parseFloat(style.paddingTop || "0") +
    parseFloat(style.paddingBottom || "0") +
    parseFloat(style.borderTopWidth || "0") +
    parseFloat(style.borderBottomWidth || "0");
  const gapCount = (toolbar ? 1 : 0) + (pager ? 1 : 0);
  const gap = parseFloat(style.rowGap || "0") || 0;
  const ceiling =
    pageHeight -
    hostPadding -
    siblingHeight -
    hostGap -
    toolbar -
    pager -
    frameHeight -
    gap * gapCount;
  const rounded = Math.max(160, Math.round(ceiling));
  tableMaxHeight.value = snapToWholeRows(rounded);
}

/**
 * A long page scrolls inside the body, and the ceiling rarely lands on a row boundary: the next row
 * then shows as a 6px sliver above the pager, which reads as a clipped table. Round the ceiling down
 * to whole rows once the rows actually overflow; a table that fits keeps the measured height.
 */
function snapToWholeRows(ceiling: number): number {
  const table = tableRef.value?.$el as HTMLElement | undefined;
  const header = table?.querySelector<HTMLElement>(".el-table__header-wrapper");
  const body = table?.querySelector<HTMLElement>(".el-table__body");
  const rows = [...(body?.querySelectorAll<HTMLElement>(":scope > tbody > tr") ?? [])];
  const headerHeight = header?.offsetHeight ?? 0;
  const heights = rows.map(row => row.offsetHeight);
  if (!headerHeight || !heights.length || heights.some(height => height === 0)) return ceiling;
  if (headerHeight + heights.reduce((sum, height) => sum + height, 0) <= ceiling) return ceiling;
  let completeHeight = headerHeight;
  for (const height of heights) {
    if (completeHeight + height > ceiling) break;
    completeHeight += height;
  }
  return completeHeight > headerHeight ? completeHeight : ceiling;
}

function scheduleMeasure(): void {
  if (measureFrame) return;
  measureFrame = requestAnimationFrame(() => {
    measureFrame = 0;
    measureHeights();
  });
}

onMounted(async () => {
  await nextTick();
  measureHeights();
  const host = rootEl.value?.parentElement;
  if (host && typeof ResizeObserver !== "undefined") {
    resizeObserver = new ResizeObserver(scheduleMeasure);
    resizeObserver.observe(host);
    resizeObserver.observe(rootEl.value as HTMLElement);
  }
  window.addEventListener("resize", scheduleMeasure);
});

onBeforeUnmount(() => {
  resizeObserver?.disconnect();
  resizeObserver = null;
  if (measureFrame) cancelAnimationFrame(measureFrame);
  window.removeEventListener("resize", scheduleMeasure);
});

watch(() => [props.rows, props.plain, props.paged, props.exportable], scheduleMeasure);
watch(fullscreen, scheduleMeasure);

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

function hiddenKeys(): ReadonlySet<string> {
  return new Set(props.columns.filter(column => column.defaultHidden).map(column => column.key));
}

function withDefaultHidden(loaded: ReturnType<typeof loadTablePrefs>) {
  return hasSavedTablePrefs(props.tableId) ? loaded : applyDefaultHidden(loaded, hiddenKeys());
}

const prefs = ref(
  (() => {
    const loaded = withDefaultHidden(loadTablePrefs(props.tableId, columnKeys.value, props.size));
    return { ...loaded, columns: withLockedVisible(loaded.columns, locked.value) };
  })()
);

watch(columnKeys, keys => {
  // Labels change with the locale, which rebuilds this array. Reloading then would
  // replace the in-memory default-hidden columns before the user has saved anything.
  const current = prefs.value.columns.map(column => column.key);
  if (current.length === keys.length && current.every((key, index) => key === keys[index])) {
    return;
  }
  const merged = withDefaultHidden(loadTablePrefs(props.tableId, keys, prefs.value.pageSize));
  prefs.value = { ...merged, columns: normalize(merged.columns) };
});

// Density changes the row height that the height ceiling is rounded to, so it re-measures too.
watch(() => prefs.value.density, scheduleMeasure);

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

/**
 * Column widths are a floor, not a fixed size. Element Plus only stretches flex columns
 * (`min-width`), so fixed `width` left a blank band on the right of the card on wide monitors.
 * Every column except the action cell shares the surplus; the action column keeps its own width
 * so its buttons stay on the card's right edge instead of drifting into a stretched cell.
 */
const isFlexColumn = (column: DataColumn) => column.key !== "actions";
const columnWidth = (column: DataColumn) => (isFlexColumn(column) ? undefined : column.width);
const columnMinWidth = (column: DataColumn) =>
  isFlexColumn(column) ? (column.minWidth ?? column.width) : column.minWidth;

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
  const defaults = applyDefaultHidden(
    defaultPrefs(columnKeys.value, prefs.value.pageSize),
    hiddenKeys()
  );
  prefs.value = {
    ...prefs.value,
    columns: normalize(defaults.columns)
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
    ref="rootEl"
    class="data-table"
    :class="[
      { 'is-fullscreen': fullscreen, 'is-plain': plain, 'is-legacy-order': legacyOrderStyle },
      `density-${prefs.density}`
    ]"
  >
    <header v-if="!plain" class="data-table__toolbar">
      <div v-if="$slots.title" class="data-table__page-title">
        <slot name="title" />
      </div>
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
      <template v-else>
        <!-- Page-specific controls such as result tabs; the default toolbar stays unchanged. -->
        <slot name="toolbar">
          <span class="data-table__total">{{ t("table.total", { total }) }}</span>
        </slot>
      </template>
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
        <el-popover trigger="click" width="min(420px, calc(100vw - 24px))" placement="bottom-end">
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
        :row-class-name="rowClassName"
        :span-method="spanMethod ? resolveSpanMethod : undefined"
        size="default"
        :max-height="plain ? undefined : (tableMaxHeight ?? undefined)"
        :default-expand-all="defaultExpandAll"
        :stripe="legacyOrderStyle"
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
          align="center"
          header-align="center"
        />
        <el-table-column
          v-for="column in visibleColumns"
          :key="column.key"
          :prop="column.key === 'actions' ? undefined : (column.prop ?? column.key)"
          :column-key="column.key"
          :label="column.label"
          :width="columnWidth(column)"
          :min-width="columnMinWidth(column)"
          :fixed="
            fixedColumns
              ? column.key === 'actions'
                ? 'right'
                : column.key === columns[0]?.key
                  ? 'left'
                  : undefined
              : undefined
          "
          :align="
            column.align ?? (legacyOrderStyle ? (column.amount ? 'right' : undefined) : 'center')
          "
          :header-align="column.align ?? (legacyOrderStyle ? undefined : 'center')"
          :show-overflow-tooltip="
            legacyOrderStyle ? column.tooltip : (column.tooltip ?? column.align === 'left')
          "
          :class-name="column.amount ? 'tcrm-amount' : undefined"
        >
          <template #default="{ row }">
            <template v-if="column.key === 'actions'">
              <slot name="actions" :row="row" />
            </template>
            <AccessibleCell
              v-else-if="!legacyOrderStyle && column.align === 'left' && !column.interactive"
            >
              <slot :name="`cell-${column.key}`" :row="row">
                {{ cellText(row, column) }}
              </slot>
            </AccessibleCell>
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
  flex: 0 1 auto;
  min-height: 0;
  max-height: 100%;
  height: auto;
  padding: var(--tcrm-space-4);
  background: var(--tcrm-color-card);
  border: 1px solid var(--tcrm-color-border);
  border-radius: var(--tcrm-radius-card);
}
.data-table.is-legacy-order {
  padding: var(--tcrm-space-3);
}
@media (min-width: 1440px) and (min-height: 900px) {
  .data-table:not(.is-plain):not(.is-legacy-order) {
    padding: var(--tcrm-space-5);
  }
}
@media (max-width: 767px) {
  .data-table:not(.is-plain):not(.is-legacy-order) {
    padding: var(--tcrm-space-3);
  }
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
  font-size: var(--tcrm-font-size-help);
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
.data-table__toolbar {
  flex-wrap: wrap;
}
.data-table__page-title {
  min-width: 0;
}
.data-table__page-title :deep(h1),
.data-table__page-title :deep(p) {
  margin: 0;
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
  flex: 1 1 auto;
  min-height: 0;
  /* el-table owns vertical and horizontal scrolling; the card clips nothing itself. */
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
.data-table:not(.is-legacy-order) :deep(.el-table) {
  --el-table-text-color: var(--tcrm-color-table-text);
  --el-table-header-text-color: var(--tcrm-color-table-text);
  --el-table-border-color: var(--tcrm-color-table-border);
}
.data-table:not(.is-legacy-order) :deep(.el-table .el-tag) {
  height: var(--tcrm-line-height-table);
  padding-block: 0;
  vertical-align: top;
}
.data-table:not(.is-legacy-order) :deep(.el-table td.is-left .name-code) {
  align-items: flex-start;
  text-align: left;
}
.data-table:not(.is-legacy-order) :deep(.el-table td.is-left .name-code__name .el-button) {
  text-align: left;
}
.data-table :deep(.el-table__header th) {
  background: var(--tcrm-color-table-header);
}
.data-table :deep(.el-table th.el-table__cell) {
  height: var(--tcrm-table-header-height);
  padding-block: 0;
}
.data-table.is-legacy-order :deep(.el-table__header th) {
  background: var(--tcrm-color-muted);
}
.data-table.is-legacy-order :deep(.el-table th.el-table__cell) {
  height: var(--tcrm-table-legacy-header-height);
}
/* Overflow must be discoverable: a 1440 desktop shows 9 of the 13 roster columns. Element Plus keeps
   its scrollbar hidden until hover, so the track is forced on and the pinned action column casts a
   left shadow — without it a clipped column reads as a rendering bug. */
.data-table :deep(.el-scrollbar__bar.is-horizontal) {
  display: block !important;
  height: 6px;
  opacity: 1 !important;
}
.data-table :deep(.el-scrollbar__bar.is-horizontal > div) {
  background: color-mix(in srgb, var(--tcrm-color-text-secondary) 40%, transparent);
  border-radius: 3px;
}
.data-table :deep(.el-table-fixed-column--right.is-first-column) {
  box-shadow: inset 10px 0 8px -8px color-mix(in srgb, var(--tcrm-color-text) 16%, transparent);
}
.data-table :deep(.el-table .el-table__cell) {
  padding-block: var(--tcrm-space-4);
  border-bottom-color: var(--tcrm-color-table-border);
}
.data-table.density-small :deep(.el-table .el-table__cell) {
  padding-block: var(--tcrm-space-2);
}
.data-table.density-large :deep(.el-table .el-table__cell) {
  padding-block: var(--tcrm-table-padding-loose);
}
.data-table.is-legacy-order :deep(.el-table .el-table__cell) {
  padding-block: var(--tcrm-space-3);
}
.data-table.is-legacy-order.density-small :deep(.el-table .el-table__cell) {
  padding-block: var(--tcrm-space-2);
}
.data-table.is-legacy-order.density-large :deep(.el-table .el-table__cell) {
  padding-block: var(--tcrm-space-4);
}
.data-table :deep(.el-table__body tr:hover > td) {
  background: var(--tcrm-color-muted);
}
/* A link button carries a 28px box next to 22px cell text, so rows with a link (the creator column
   of the order list, the download action of the import list) were 4px taller than rows without one.
   Drop the button onto the text line and every row keeps one height. */
.data-table :deep(.el-table .cell .el-button.is-link) {
  height: auto;
  padding: 0;
  line-height: var(--tcrm-line-height-table);
  vertical-align: baseline;
}
.data-table:not(.is-legacy-order) :deep(.el-table .cell .el-button.is-link) {
  height: var(--tcrm-line-height-table);
  min-height: var(--tcrm-line-height-table);
  vertical-align: top;
}
.data-table.is-legacy-order :deep(.el-table .cell .el-button.is-link) {
  line-height: var(--tcrm-line-height-body);
}
</style>
