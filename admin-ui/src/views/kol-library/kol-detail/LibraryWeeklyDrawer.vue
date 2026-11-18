<script setup lang="ts">
import zhCn from "element-plus/es/locale/lang/zh-cn";
import en from "element-plus/es/locale/lang/en";
import { dayjs } from "element-plus";
import DataTable, { type DataColumn } from "@/components/DataTable.vue";
import { computed, reactive, ref, watch } from "vue";
import { useI18n } from "vue-i18n";
import {
  appendLibraryWeek,
  updateLibraryWeek,
  listLibraryWeeklyData,
  type LibraryContentVO,
  type LibraryKolDetailVO,
  type LibraryWeeklyData,
  type LibraryWeeklyInput
} from "@/api/kol-library/kol";
import { formatInstantToMinute } from "@/utils/format";
import { libraryOptionLabel, outreachTagType } from "@/utils/kol-library";
import { notifyError } from "@/utils/notify";

const props = defineProps<{
  creator: LibraryKolDetailVO;
  content: LibraryContentVO;
  canEdit: boolean;
}>();
const open = defineModel<boolean>({ required: true });
const emit = defineEmits<{ saved: [] }>();
const { t, locale } = useI18n();
const history = ref<LibraryWeeklyData[]>([]);
const metrics = ["clicks", "orders", "gmv"] as const;
const metricLabel = (key: (typeof metrics)[number]) =>
  key === "gmv" ? "GMV" : t(`library.content.${key}`);
const columns = computed<DataColumn[]>(() => [
  { key: "weekStart", label: t("library.weekly.week"), minWidth: 220, align: "left" },
  ...metrics.map(key => ({ key, label: metricLabel(key), minWidth: 110, amount: true })),
  { key: "actions", label: t("common.actions"), width: 100 }
]);
const rows = computed(() =>
  props.canEdit && !loading.value ? [...history.value, draft] : history.value
);
const metric = ref<(typeof metrics)[number]>("clicks");
const loading = ref(false);
const saving = ref(false);
const loadFailed = ref(false);
const invalid = ref(false);
const empty = (): LibraryWeeklyInput => ({ weekStart: "", clicks: "", orders: "", gmv: "" });
const draft = reactive(empty());
const day = 86_400_000;
const editingWeek = ref<string | null>(null);
const editDraft = reactive(empty());
dayjs.locale({ ...dayjs.Ls.en, name: "weekly-en", weekStart: 1 }, undefined, true);
const calendarLocale = computed(() => ({
  ...(locale.value.startsWith("zh") ? zhCn : en),
  name: "weekly-en"
}));

function monday(date: Date) {
  const utc = Date.UTC(date.getFullYear(), date.getMonth(), date.getDate());
  return new Date(utc - ((date.getDay() + 6) % 7) * day).toISOString().slice(0, 10);
}

const currentWeek = computed(() => monday(new Date()));
const earliestWeek = computed(() =>
  new Date(Date.parse(currentWeek.value) - 63 * day).toISOString().slice(0, 10)
);
function disabledDate(date: Date) {
  const week = monday(date);
  return (
    week < earliestWeek.value ||
    week > currentWeek.value ||
    history.value.some(row => row.weekStart === week)
  );
}
function selectWeek(value: Date | null) {
  draft.weekStart = value ? monday(value) : "";
}
const selectedDate = ref<Date | null>(null);

function startEdit(row: LibraryWeeklyData) {
  editingWeek.value = row.weekStart;
  Object.assign(editDraft, row);
  invalid.value = false;
}
function inputFor(row: object) {
  return row === draft ? draft : editDraft;
}
function isEditing(row: object) {
  return row === draft || (row as LibraryWeeklyData).weekStart === editingWeek.value;
}
let request = 0;

async function load() {
  const token = ++request;
  loading.value = true;
  loadFailed.value = false;
  try {
    const rows = await listLibraryWeeklyData(props.creator.id, props.content.id);
    if (request === token) history.value = rows;
  } catch (error) {
    if (request === token) {
      loadFailed.value = true;
      notifyError(error);
    }
  } finally {
    if (request === token) loading.value = false;
  }
}

watch(
  () => [open.value, props.content.id],
  () => {
    history.value = [];
    editingWeek.value = null;
    selectedDate.value = null;
    Object.assign(draft, empty());
    invalid.value = false;
    metric.value = "clicks";
    if (open.value) void load();
    else ++request;
  },
  { immediate: true }
);

function weekLabel(week: string) {
  const end = new Date(Date.parse(week) + day * 6).toISOString().slice(0, 10);
  return `${week} ～ ${end}`;
}

const last = computed(() => history.value.at(-1));

const updatedAt = computed(() =>
  history.value.reduce(
    (latest, row) => (row.updatedAt > latest ? row.updatedAt : latest),
    history.value[0]?.updatedAt ?? props.content.updateTime
  )
);

function display(value: string | null | undefined, currency = false) {
  if (value == null) return "—";
  const [whole = "0", fraction = ""] = String(value).split(".");
  const integer = BigInt(whole).toLocaleString(locale.value);
  return currency ? `¥${integer}.${fraction.padEnd(2, "0")}` : integer;
}

const chart = computed(() => {
  const values = history.value.map(row => Number(row[metric.value]));
  const max = Math.max(1, ...values);
  const base = 10 ** Math.floor(Math.log10(max));
  const ceiling = Math.ceil(max / base) * base;
  const first = Date.parse(history.value[0]?.weekStart ?? "");
  const lastDate = Date.parse(last.value?.weekStart ?? "");
  const points = history.value.map((row, i) => ({
    x:
      first === lastDate
        ? 400
        : 91 + ((Date.parse(row.weekStart) - first) / (lastDate - first)) * 655,
    y: 131 - ((values[i] ?? 0) / ceiling) * 112,
    week: row.weekStart,
    value: row[metric.value]
  }));
  return {
    points,
    ticks: [1, 2 / 3, 1 / 3, 0].map(ratio => ({
      y: 131 - ratio * 112,
      label: (ceiling * ratio).toLocaleString(locale.value, { maximumFractionDigits: 0 })
    }))
  };
});

async function submit(row: object) {
  const updating = row !== draft;
  const input = updating ? editDraft : draft;
  if (saving.value || !props.canEdit || loading.value || loadFailed.value) return;
  invalid.value =
    (!updating &&
      (!input.weekStart ||
        input.weekStart < earliestWeek.value ||
        input.weekStart > currentWeek.value ||
        history.value.some(item => item.weekStart === input.weekStart))) ||
    !/^\d{1,19}$/.test(input.clicks) ||
    !/^\d{1,19}$/.test(input.orders) ||
    !/^\d{1,12}(\.\d{1,2})?$/.test(input.gmv);
  if (invalid.value) return;
  const token = request;
  saving.value = true;
  try {
    const save = updating ? updateLibraryWeek : appendLibraryWeek;
    const rows = await save(props.creator.id, props.content.id, {
      weekStart: input.weekStart,
      clicks: input.clicks,
      orders: input.orders,
      gmv: input.gmv
    });
    if (request === token) {
      history.value = rows;
      if (updating) editingWeek.value = null;
      else {
        Object.assign(draft, empty());
        selectedDate.value = null;
      }
    }
    emit("saved");
  } catch (error) {
    notifyError(error);
  } finally {
    saving.value = false;
  }
}
</script>

<template>
  <el-drawer
    v-model="open"
    :title="t('library.weekly.title')"
    size="min(880px, 100vw)"
    :close-on-click-modal="!saving"
    :close-on-press-escape="!saving"
  >
    <div v-loading="loading" class="weekly">
      <section class="weekly__info">
        <h3>{{ content.publishTitle || t("library.weekly.noTitle") }}</h3>
        <div class="weekly__meta">
          <span>{{ creator.displayName }}</span>
          <el-tag :type="outreachTagType(creator.outreachStatus)" size="small">{{
            libraryOptionLabel("outreachStatus", creator.outreachStatus)
          }}</el-tag>
          <span
            >{{ t("library.content.commissionRateColumn") }}
            {{ content.commissionRate == null ? "—" : `${Number(content.commissionRate)}%` }}</span
          >
        </div>
        <div class="weekly__meta weekly__muted">
          <span>{{ t("library.content.publishDate") }} {{ content.publishDate }}</span>
          <span>{{ t("library.weekly.updatedAt") }} {{ formatInstantToMinute(updatedAt) }}</span>
        </div>
      </section>
      <div class="weekly__metrics">
        <div
          v-for="key in metrics"
          :key="key"
          class="weekly__metric"
          :class="`weekly__metric--${key}`"
        >
          <div class="weekly__muted">{{ metricLabel(key) }}</div>
          <strong>{{ display(content[key], key === "gmv") }}</strong>
        </div>
      </div>
      <p class="weekly__muted weekly__note">{{ t("library.weekly.note") }}</p>
      <div class="weekly__switch">
        <el-radio-group v-model="metric">
          <el-radio-button v-for="key in metrics" :key="key" :value="key">{{
            t(`library.content.${key}`)
          }}</el-radio-button>
        </el-radio-group>
      </div>
      <template v-if="!loadFailed">
        <svg
          v-if="history.length"
          class="weekly__chart"
          viewBox="0 0 800 160"
          role="img"
          :aria-label="metricLabel(metric)"
        >
          <g v-for="tick in chart.ticks" :key="tick.y">
            <line x1="64" x2="786" :y1="tick.y" :y2="tick.y" stroke="var(--tcrm-color-border)" />
            <text x="55" :y="tick.y + 4" text-anchor="end">{{ tick.label }}</text>
          </g>
          <polyline
            :points="chart.points.map(point => `${point.x},${point.y}`).join(' ')"
            fill="none"
            stroke="var(--el-color-primary)"
            stroke-width="2"
          />
          <g v-for="(point, i) in chart.points" :key="point.week">
            <circle
              :cx="point.x"
              :cy="point.y"
              r="3.5"
              fill="white"
              stroke="var(--el-color-primary)"
              stroke-width="2"
            >
              <title>
                {{ weekLabel(point.week) }} · {{ display(point.value, metric === "gmv") }}
              </title>
            </circle>
            <text
              v-if="i % Math.ceil(chart.points.length / 6) === 0 || i === chart.points.length - 1"
              :x="point.x"
              y="152"
              text-anchor="middle"
            >
              {{ point.week.slice(5).replace("-", "/") }}
            </text>
          </g>
        </svg>
        <p v-else-if="!loading" class="weekly__empty weekly__muted">
          {{ t("library.weekly.empty") }}
        </p>
      </template>
      <el-button v-if="loadFailed" @click="load">{{ t("table.retry") }}</el-button>
      <DataTable
        v-else
        table-id="library-weekly-data"
        plain
        :paged="false"
        :total="history.length"
        :columns="columns"
        :rows="rows"
        row-key="weekStart"
      >
        <template #cell-weekStart="{ row }">
          <el-config-provider v-if="row === draft" :locale="calendarLocale">
            <el-date-picker
              v-model="selectedDate"
              type="week"
              popper-class="library-week-picker"
              :format="draft.weekStart ? weekLabel(draft.weekStart) : 'YYYY-MM-DD'"
              :disabled-date="disabledDate"
              :placeholder="t('library.weekly.selectWeek')"
              :aria-label="t('library.weekly.week')"
              :disabled="saving"
              :editable="false"
              :show-week-number="false"
              style="width: 100%"
              @change="selectWeek"
            />
          </el-config-provider>
          <span v-else>{{ weekLabel((row as LibraryWeeklyData).weekStart) }}</span>
        </template>
        <template v-for="key in metrics" :key="key" #[`cell-${key}`]="{ row }">
          <el-input
            v-if="isEditing(row)"
            v-model="inputFor(row)[key]"
            :inputmode="key === 'gmv' ? 'decimal' : 'numeric'"
            :placeholder="t(key === 'gmv' ? 'library.weekly.decimal' : 'library.weekly.integer')"
            :aria-label="metricLabel(key)"
            :disabled="saving"
          />
          <span v-else>{{ display((row as LibraryWeeklyData)[key], key === "gmv") }}</span>
        </template>
        <template #actions="{ row }">
          <el-button
            v-if="canEdit && isEditing(row)"
            link
            type="primary"
            :loading="saving"
            @click="submit(row)"
            >{{ t("common.save") }}</el-button
          >
          <el-button
            v-else-if="canEdit"
            link
            type="primary"
            :disabled="saving"
            @click="startEdit(row as LibraryWeeklyData)"
            >{{ t("common.edit") }}</el-button
          >
          <span v-else>—</span>
        </template>
      </DataTable>
      <p v-if="invalid" class="weekly__error" role="alert">{{ t("library.weekly.invalid") }}</p>
    </div>
  </el-drawer>
</template>

<style scoped>
.weekly {
  display: grid;
  gap: var(--tcrm-space-4);
}
.weekly__info {
  padding: var(--tcrm-space-4);
  background: var(--tcrm-color-page);
  border-radius: var(--tcrm-radius-card);
}
.weekly__info h3 {
  margin: 0 0 var(--tcrm-space-2);
  font-size: var(--tcrm-font-size-section);
  line-height: 24px;
  font-weight: 600;
}
.weekly__meta {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 4px 16px;
}
.weekly__meta + .weekly__meta {
  margin-top: 4px;
}
.weekly__metrics {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: var(--tcrm-space-4);
}
.weekly__metric {
  --metric-accent: var(--tcrm-color-primary);
  padding: var(--tcrm-space-4);
  border: 1px solid var(--tcrm-color-border);
  border-inline-start: 3px solid var(--metric-accent);
  background: var(--tcrm-color-card);
  border-radius: var(--tcrm-radius-card);
  overflow-wrap: anywhere;
}
.weekly__metric--orders {
  --metric-accent: var(--tcrm-color-success);
}
.weekly__metric--gmv {
  --metric-accent: #7460ab;
}
.weekly__metric strong {
  font-variant-numeric: tabular-nums;
  font-size: var(--tcrm-font-size-amount-detail);
  line-height: 32px;
  font-weight: 600;
}
.weekly__muted,
.weekly__chart text {
  color: var(--tcrm-color-text-secondary);
}
.weekly__note,
.weekly__empty {
  margin: 0;
}
.weekly__switch {
  display: flex;
  justify-content: flex-end;
}
.weekly__chart {
  width: 100%;
  min-height: 140px;
}
.weekly__chart text {
  font-size: var(--tcrm-font-size-help);
  fill: currentColor;
}
.weekly__locked {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  white-space: nowrap;
}
.weekly__footer {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding-top: 12px;
  border-top: 1px solid var(--tcrm-color-border);
}
.weekly__error {
  margin: 0;
  color: #b42318;
}
@media (max-width: 575px) {
  .weekly__metrics {
    grid-template-columns: 1fr;
  }
}
</style>

<style>
.library-week-picker .el-month-table tbody {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
}
.library-week-picker .el-month-table tr {
  display: contents;
}
.library-week-picker .el-month-table td {
  display: block;
  width: auto;
}
.library-week-picker .el-month-table .cell {
  width: 100%;
  margin: 0;
  border-radius: var(--tcrm-radius-control);
}
.library-week-picker .el-month-table td.current:not(.disabled) .cell {
  color: white;
  background: var(--el-color-primary);
}
</style>
