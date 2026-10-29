<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from "vue";
import { useI18n } from "vue-i18n";
import { useRoute } from "vue-router";
import { listOperLogs, type OperLogQuery, type OperLogVO } from "@/api/system/oper-log";
import DataTable, { type DataColumn } from "@/components/DataTable.vue";
import ListSearch, { type SearchChip } from "@/components/ListSearch.vue";
import { formatInstant } from "@/utils/format";
import { notifyError } from "@/utils/notify";
import { readPageSize } from "@/utils/table-prefs";

defineOptions({ name: "SystemOperLogPage" });

/**
 * The audit log (task 32 §2.3, contract 33 §3.3): module / keyword / operator / time-range
 * filters, the human-readable `summary` column, and the BD-transfer quick filter. BD-transfer
 * records moved here when their own page was removed.
 */
const { t } = useI18n();
const route = useRoute();

const MODULES = ["KOL", "Product", "Order", "System", "Platform"] as const;

const loading = ref(false);
const rows = ref<OperLogVO[]>([]);
const total = ref(0);
const query = reactive({
  page: 1,
  size: readPageSize("oper-log", 20),
  module: "",
  keyword: "",
  operator: "",
  range: null as [Date, Date] | null
});
const applied = ref<OperLogQuery & { module: string; keyword: string; operator: string }>({
  module: "",
  keyword: "",
  operator: ""
});

function asLog(row: object): OperLogVO {
  return row as OperLogVO;
}

function moduleLabel(module: (typeof MODULES)[number]): string {
  return module === "Platform" ? t("audit.platformModule") : module;
}

/** `?module=KOL&keyword=<transfer keyword>` opens with the BD-transfer quick filter. */
function filtersFromRoute() {
  const module = route?.query.module;
  const keyword = route?.query.keyword;
  return {
    module: typeof module === "string" ? module : "",
    keyword: typeof keyword === "string" ? keyword : ""
  };
}

/**
 * The transfer quick filter matches the word the backend writes into every transfer summary
 * ("达人…转至…", verified against the joint-debugging backend); the visible button keeps the
 * 转移记录 label. The literal lives here as a lookup key, exempted in the residual-copy test.
 */
const TRANSFER_KEYWORD = "转至";

const transferFilterActive = computed(
  () => applied.value.module === "KOL" && applied.value.keyword === TRANSFER_KEYWORD
);

const chips = computed<SearchChip[]>(() => {
  const list: SearchChip[] = [];
  if (applied.value.module)
    list.push({ key: "module", label: t("audit.module"), text: applied.value.module });
  if (applied.value.keyword)
    list.push({ key: "keyword", label: t("audit.keyword"), text: applied.value.keyword });
  if (applied.value.operator)
    list.push({ key: "operator", label: t("audit.operator"), text: applied.value.operator });
  if (applied.value.from || applied.value.to)
    list.push({
      key: "range",
      label: t("audit.timeRange"),
      text: `${applied.value.from ?? "…"} ~ ${applied.value.to ?? "…"}`
    });
  return list;
});

const columns = computed<DataColumn[]>(() => [
  { key: "time", label: t("audit.time"), width: 170 },
  { key: "operator", align: "left" as const, label: t("audit.operator"), width: 130 },
  { key: "module", label: t("audit.module"), width: 100 },
  { key: "action", label: t("audit.action"), minWidth: 140 },
  {
    key: "summary",
    align: "left" as const,
    label: t("audit.summary"),
    minWidth: 280,
    tooltip: true
  },
  { key: "result", label: t("audit.result"), width: 90 },
  { key: "path", align: "left" as const, label: t("audit.uri"), minWidth: 200, tooltip: true },
  { key: "ip", align: "left" as const, label: t("audit.ip"), width: 140 }
]);

async function load() {
  loading.value = true;
  try {
    const page = await listOperLogs(query.page, query.size, {
      module: applied.value.module || undefined,
      keyword: applied.value.keyword || undefined,
      operator: applied.value.operator || undefined,
      from: applied.value.from,
      to: applied.value.to
    });
    rows.value = page.items;
    total.value = Number(page.total);
  } catch (error) {
    notifyError(error);
  } finally {
    loading.value = false;
  }
}

function toIso(value: Date | undefined | null): string | undefined {
  return value ? value.toISOString() : undefined;
}

function search() {
  applied.value = {
    module: query.module.trim(),
    keyword: query.keyword.trim(),
    operator: query.operator.trim(),
    from: toIso(query.range?.[0]),
    to: toIso(query.range?.[1])
  };
  query.page = 1;
  void load();
}

function reset() {
  query.module = "";
  query.keyword = "";
  query.operator = "";
  query.range = null;
  search();
}

/** The BD-transfer quick filter (task 32 §2.3): module KOL plus the transfer keyword. */
function applyTransferFilter() {
  query.module = "KOL";
  query.keyword = TRANSFER_KEYWORD;
  query.operator = "";
  query.range = null;
  search();
}

function removeChip(key: string): void {
  if (key === "module") query.module = "";
  if (key === "keyword") query.keyword = "";
  if (key === "operator") query.operator = "";
  if (key === "range") query.range = null;
  search();
}

function onPage(page: number): void {
  query.page = page;
  void load();
}

function onSize(size: number): void {
  query.size = size;
}

watch(
  () => [route?.query.module, route?.query.keyword],
  () => {
    if (route?.name !== "SystemOperLogs") return;
    const fromRoute = filtersFromRoute();
    query.module = fromRoute.module;
    query.keyword = fromRoute.keyword;
    search();
  }
);

onMounted(() => {
  const fromRoute = filtersFromRoute();
  query.module = fromRoute.module;
  query.keyword = fromRoute.keyword;
  search();
});
</script>

<template>
  <div class="list-page">
    <ListSearch :chips="chips" @search="search" @reset="reset" @remove="removeChip">
      <template #actions>
        <el-button
          data-test="transfer-filter"
          :type="transferFilterActive ? 'primary' : ''"
          @click="applyTransferFilter"
        >
          {{ t("audit.transferFilter") }}
        </el-button>
      </template>
      <template #primary>
        <el-form-item :label="t('audit.module')">
          <el-select
            v-model="query.module"
            clearable
            filterable
            :placeholder="t('common.all')"
            data-test="filter-module"
            style="width: 140px"
          >
            <el-option
              v-for="module in MODULES"
              :key="module"
              :label="moduleLabel(module)"
              :value="module"
            />
          </el-select>
        </el-form-item>
        <el-form-item :label="t('audit.keyword')">
          <el-input
            v-model="query.keyword"
            clearable
            maxlength="128"
            data-test="filter-keyword"
            :placeholder="t('audit.keywordPlaceholder')"
          />
        </el-form-item>
        <el-form-item :label="t('audit.operator')">
          <el-input
            v-model="query.operator"
            clearable
            maxlength="64"
            data-test="filter-operator"
            :placeholder="t('audit.operator')"
          />
        </el-form-item>
        <el-form-item :label="t('audit.timeRange')">
          <el-date-picker
            v-model="query.range"
            type="datetimerange"
            :start-placeholder="t('audit.timeRange')"
            :end-placeholder="t('audit.timeRange')"
            data-test="filter-range"
          />
        </el-form-item>
      </template>
    </ListSearch>

    <DataTable
      table-id="oper-log"
      fill-available-height
      :rows="rows"
      :columns="columns"
      :loading="loading"
      :total="total"
      :page="query.page"
      :size="query.size"
      :filter-count="chips.length"
      @update:page="onPage"
      @update:size="onSize"
      @refresh="load"
      @clear-filters="reset"
    >
      <template #cell-time="{ row }">{{ formatInstant(asLog(row).operTime) }}</template>
      <template #cell-operator="{ row }">{{ asLog(row).operatorName ?? "-" }}</template>
      <template #cell-summary="{ row }">{{ asLog(row).summary ?? "-" }}</template>
      <template #cell-result="{ row }">
        <el-tag :type="asLog(row).success ? 'success' : 'danger'">
          {{ t(`audit.resultOption.${asLog(row).success ? "SUCCESS" : "FAIL"}`) }}
        </el-tag>
      </template>
    </DataTable>
  </div>
</template>
