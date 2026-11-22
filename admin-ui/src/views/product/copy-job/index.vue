<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from "vue";
import { useRoute } from "vue-router";
import { useI18n } from "vue-i18n";
import {
  listCopyJobItems,
  listCopyJobs,
  type CopyJobItemVO,
  type CopyJobVO
} from "@/api/product/copy-job";
import { listKols } from "@/api/kol/kol";
import type { KolVO } from "@/api/types";
import CopyText from "@/components/CopyText.vue";
import DataTable, { type DataColumn } from "@/components/DataTable.vue";
import ListSearch from "@/components/ListSearch.vue";
import NameCode from "@/components/NameCode.vue";
import RowActions from "@/components/RowActions.vue";
import { useLocaleStore } from "@/stores/locale";
import { formatInstant, formatNameWithCode } from "@/utils/format";
import { notifyError } from "@/utils/notify";
import { readPageSize } from "@/utils/table-prefs";

defineOptions({ name: "CopyJobPage" });

const CHANNELS = ["WEIDIAN"] as const;
const JOB_STATUSES = [
  "processing",
  "completed",
  "partial_failed",
  "failed",
  "HAS_FAILURE"
] as const;

const { t } = useI18n();
const route = useRoute();
const loading = ref(false);
const loadFailed = ref(false);
const rows = ref<CopyJobVO[]>([]);
const total = ref(0);
let listRequest = 0;
const query = reactive({
  page: 1,
  size: readPageSize("copy-jobs", 20),
  jobCode: "",
  targetItemId: "",
  kolId: undefined as string | undefined,
  targetChannel: "",
  status: ""
});

const kolOptions = ref<KolVO[]>([]);
const kolSearching = ref(false);

async function searchKols(keyword: string) {
  if (!keyword.trim()) return;
  kolSearching.value = true;
  try {
    kolOptions.value = (await listKols({ keyword: keyword.trim(), page: 1, size: 20 })).items;
  } catch (error) {
    notifyError(error);
  } finally {
    kolSearching.value = false;
  }
}

const detailVisible = ref(false);
const detailLoading = ref(false);
const detailFailed = ref(false);
const detailJob = ref<CopyJobVO | null>(null);
const detailRows = ref<CopyJobItemVO[]>([]);
const detailTotal = ref(0);
const detailQuery = reactive({ page: 1, size: readPageSize("copy-job-items", 20) });

const detailSort = ref<"ASCENDING" | "DESCENDING">();
let detailRequest = 0;
const detailTableKey = ref(0);

function sortDetail({ order }: { order: "ascending" | "descending" | null }) {
  detailSort.value = order ? (order === "ascending" ? "ASCENDING" : "DESCENDING") : undefined;
  onDetailPage(1);
}

function asJob(row: object): CopyJobVO {
  return row as CopyJobVO;
}

function asItem(row: object): CopyJobItemVO {
  return row as CopyJobItemVO;
}

function channelText(channel: string | null | undefined): string {
  if (!channel) return t("copyJob.none");
  return channel === "WEIDIAN" ? t("copyJob.channel.WEIDIAN") : channel;
}

function jobStatusText(status: string): string {
  switch (status) {
    case "HAS_FAILURE":
      return t("workbench.jobs");
    case "processing":
      return t("copyJob.jobStatus.processing");
    case "completed":
      return t("copyJob.jobStatus.completed");
    case "partial_failed":
      return t("copyJob.jobStatus.partial_failed");
    case "failed":
      return t("copyJob.jobStatus.failed");
    default:
      return status;
  }
}

function itemStatusText(status: string): string {
  switch (status) {
    case "pending":
      return t("copyJob.itemStatus.pending");
    case "success":
      return t("copyJob.itemStatus.success");
    case "skipped":
      return t("copyJob.itemStatus.skipped");
    case "failed":
      return t("copyJob.itemStatus.failed");
    default:
      return status;
  }
}

const zhWidth = computed(() => useLocaleStore().locale === "zh-CN");
/** Action and result columns get a width per language: the labels differ in length (norm 10 §3). */
const px = (zh: number, en: number): number => (zhWidth.value ? zh : en);

const columns = computed<DataColumn[]>(() => [
  {
    key: "jobCode",
    align: "left" as const,
    label: t("copyJob.jobCode"),
    minWidth: { "zh-CN": 190, "en-US": 210 }
  },
  { key: "targetChannel", label: t("copyJob.targetChannel"), width: 110 },
  { key: "status", label: t("copyJob.status"), width: 110 },
  // R-01 (browser-measured, assets/task-59/measurements-{zh,en}.md): three nowrap tags need
  // ~232px (zh, 1-digit counts) to ~292px (en, 3-digit counts) including cell padding.
  { key: "result", label: t("copyJob.result"), minWidth: px(240, 300) },
  { key: "createTime", label: t("copyJob.createTime"), width: px(170, 180) },
  { key: "finishTime", label: t("copyJob.finishTime"), width: px(170, 180) },
  {
    key: "kol",
    align: "left" as const,
    label: t("copyToken.kol"),
    minWidth: 170,
    defaultHidden: true
  },
  {
    key: "targetShopCode",
    align: "left" as const,
    label: t("copyJob.targetShopCode"),
    width: 140,
    defaultHidden: true
  },
  {
    key: "targetShopName",
    align: "left" as const,
    label: t("copyJob.targetShopName"),
    minWidth: 140,
    defaultHidden: true
  },
  // View items: zh canvas 56px, en 98px, +24px headroom; task 100 U-007 measured 14px of extra
  // truncation on the Chinese view label at 1280, so the floor keeps one more word.
  { key: "actions", label: t("common.actions"), width: px(100, 144) }
]);

const itemColumns = computed<DataColumn[]>(() => [
  { key: "seq", label: t("copyJob.seq"), width: 70 },
  {
    key: "sourceProductId",
    align: "left" as const,
    label: t("copyJob.sourceProductId"),
    minWidth: 150,
    tooltip: false
  },
  { key: "sourceChannel", label: t("copyJob.sourceChannel"), width: 110 },
  { key: "sourceShop", align: "left" as const, label: t("copyJob.sourceShop"), minWidth: 140 },
  { key: "targetChannel", label: t("copyJob.targetChannel"), width: 110 },
  { key: "targetShop", align: "left" as const, label: t("copyJob.targetShop"), minWidth: 140 },
  {
    key: "targetItemId",
    align: "left" as const,
    label: t("copyJob.targetItemId"),
    minWidth: 140,
    tooltip: false
  },
  { key: "targetItemUrl", align: "left" as const, label: t("copyJob.targetItemUrl"), width: 90 },
  { key: "status", label: t("copyJob.status"), width: 110, sortable: true },
  {
    key: "errorMessage",
    align: "left" as const,
    label: t("copyJob.failReason"),
    minWidth: 160,
    tooltip: false
  }
]);

const detailTitle = computed(() =>
  t("copyJob.detailTitle", { jobCode: detailJob.value?.jobCode ?? "" })
);

function jobStatusTag(status: string): "success" | "warning" | "danger" | "info" {
  if (status === "completed") return "success";
  if (status === "failed") return "danger";
  if (status === "processing" || status === "partial_failed") return "warning";
  return "info";
}

function listParams() {
  return {
    page: query.page,
    size: query.size,
    jobCode: query.jobCode.trim() || undefined,
    ...(query.targetItemId.trim() ? { targetItemId: query.targetItemId.trim() } : {}),
    ...(query.kolId ? { kolId: query.kolId } : {}),
    targetChannel: query.targetChannel || undefined,
    status: query.status || undefined
  };
}

async function load(): Promise<void> {
  const request = ++listRequest;
  loading.value = true;
  loadFailed.value = false;
  try {
    const page = await listCopyJobs(listParams());
    if (request !== listRequest) return;
    rows.value = page.items;
    total.value = Number(page.total);
  } catch (error) {
    if (request !== listRequest) return;
    loadFailed.value = true;
    notifyError(error);
  } finally {
    if (request === listRequest) loading.value = false;
  }
}

function search(): void {
  query.page = 1;
  void load();
}

function reset(): void {
  query.jobCode = "";
  query.targetItemId = "";
  query.kolId = undefined;
  query.targetChannel = "";
  query.status = "";
  query.page = 1;
  void load();
}

function onPage(page: number): void {
  query.page = page;
  void load();
}

function onSize(size: number): void {
  query.size = size;
  query.page = 1;
  void load();
}

async function loadItems(): Promise<void> {
  const job = detailJob.value;
  if (!job) return;
  const request = ++detailRequest;
  detailLoading.value = true;
  detailFailed.value = false;
  try {
    const page = await listCopyJobItems(job.id, {
      page: detailQuery.page,
      size: detailQuery.size,
      ...(detailSort.value ? { statusOrder: detailSort.value } : {})
    });
    if (request !== detailRequest) return;
    detailRows.value = page.items;
    detailTotal.value = Number(page.total);
  } catch (error) {
    if (request !== detailRequest) return;
    detailFailed.value = true;
    notifyError(error);
  } finally {
    if (request === detailRequest) detailLoading.value = false;
  }
}

function openDetail(row: CopyJobVO): void {
  detailJob.value = row;
  detailSort.value = undefined;
  detailTableKey.value++;
  detailRows.value = [];
  detailTotal.value = 0;
  detailQuery.page = 1;
  detailVisible.value = true;
  void loadItems();
}

function onDetailPage(page: number): void {
  detailQuery.page = page;
  void loadItems();
}

function onDetailSize(size: number): void {
  detailQuery.size = size;
  detailQuery.page = 1;
  void loadItems();
}

function rowActions(row: CopyJobVO) {
  return [
    {
      key: "items",
      label: t("copyJob.viewItems"),
      testId: "view-items",
      onClick: () => openDetail(row)
    }
  ];
}

/**
 * R-03: the order chain pushes ?jobCode=… here even when this tab is already open (one tab per
 * path, the page is kept alive), so watch the route instead of reading it once on mount.
 */
watch(
  [() => route.name, () => route.query.jobCode, () => route.query.status],
  ([routeName, jobCode, status]) => {
    if (routeName && routeName !== "ProductTasks") return;
    if (status === "HAS_FAILURE" || status === "processing") {
      query.jobCode = "";
      query.targetChannel = "";
      query.status = status;
      search();
      return;
    }
    const wanted = typeof jobCode === "string" ? jobCode.trim() : "";
    if (!wanted || wanted === query.jobCode) return;
    query.jobCode = wanted;
    if (query.status === "HAS_FAILURE" || query.status === "processing") query.status = "";
    search();
  },
  { immediate: true }
);

onMounted(() => {
  // The immediate route watch above already searched when a jobCode came with the URL.
  if (
    !route.query.jobCode &&
    route.query.status !== "HAS_FAILURE" &&
    route.query.status !== "processing"
  )
    void load();
});
</script>

<template>
  <div class="list-page" data-test="copy-job-page">
    <ListSearch @search="search" @reset="reset">
      <template #primary>
        <el-form-item :label="t('copyJob.jobCode')">
          <el-input
            v-model="query.jobCode"
            clearable
            maxlength="64"
            data-test="job-code"
            class="copy-job__filter"
          />
        </el-form-item>
        <el-form-item :label="t('copyJob.targetItemId')">
          <el-input
            v-model="query.targetItemId"
            maxlength="32"
            clearable
            data-test="target-item-id-filter"
          />
        </el-form-item>
        <el-form-item :label="t('copyJob.creatorFilter')">
          <el-select
            v-model="query.kolId"
            filterable
            remote
            clearable
            :remote-method="searchKols"
            :loading="kolSearching"
            :placeholder="t('copyJob.creatorPlaceholder')"
            class="filter-w-md"
            data-test="job-kol-filter"
          >
            <el-option
              v-for="kol in kolOptions"
              :key="kol.id"
              :label="formatNameWithCode(kol.name, kol.kolCode)"
              :value="kol.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item :label="t('copyJob.targetChannel')">
          <el-select
            v-model="query.targetChannel"
            clearable
            data-test="target-channel"
            class="filter-w-sm"
            :placeholder="t('common.all')"
          >
            <el-option
              v-for="channel in CHANNELS"
              :key="channel"
              :label="channelText(channel)"
              :value="channel"
            />
          </el-select>
        </el-form-item>
        <el-form-item :label="t('copyJob.status')">
          <el-select
            v-model="query.status"
            clearable
            data-test="job-status"
            class="filter-w-sm"
            :placeholder="t('common.all')"
          >
            <el-option
              v-for="status in JOB_STATUSES"
              :key="status"
              :label="jobStatusText(status)"
              :value="status"
            />
          </el-select>
        </el-form-item>
      </template>
    </ListSearch>

    <DataTable
      class="copy-job-table"
      table-id="copy-jobs"
      fill-available-height
      :rows="rows"
      :columns="columns"
      :loading="loading"
      :failed="loadFailed"
      :total="total"
      :page="query.page"
      :size="query.size"
      @update:page="onPage"
      @update:size="onSize"
      @refresh="load"
      @retry="load"
    >
      <template #cell-jobCode="{ row }">
        <CopyText :value="asJob(row).jobCode" />
      </template>
      <template #cell-kol="{ row }"
        ><NameCode :name="asJob(row).kolName" :code="asJob(row).kolCode"
      /></template>
      <template #cell-targetChannel="{ row }">
        {{ channelText(asJob(row).targetChannel) }}
      </template>
      <template #cell-status="{ row }">
        <el-tag :type="jobStatusTag(asJob(row).status)" data-test="job-status-text">{{
          jobStatusText(asJob(row).status)
        }}</el-tag>
      </template>
      <template #cell-result="{ row }">
        <span class="copy-job__result" data-test="job-result">
          <el-tag data-test="result-total">{{
            t("copyJob.total", { n: asJob(row).totalCount })
          }}</el-tag>
          <el-tag type="success" data-test="result-success">{{
            t("copyJob.success", { n: asJob(row).successCount })
          }}</el-tag>
          <el-tag :type="asJob(row).failCount > 0 ? 'danger' : 'info'" data-test="result-fail">{{
            t("copyJob.fail", { n: asJob(row).failCount })
          }}</el-tag>
          <span
            v-if="asJob(row).status === 'completed' && asJob(row).failCount > 0"
            class="copy-job__hint"
            data-test="fail-hint"
            >{{ t("copyJob.completedHasFailures") }}</span
          >
        </span>
      </template>
      <template #cell-createTime="{ row }">{{ formatInstant(asJob(row).createTime) }}</template>
      <template #cell-finishTime="{ row }">{{ formatInstant(asJob(row).finishTime) }}</template>
      <template #cell-targetShopName="{ row }">
        <span data-test="target-shop-name">{{
          asJob(row).targetShopName || asJob(row).targetShopCode
            ? formatNameWithCode(asJob(row).targetShopName, asJob(row).targetShopCode)
            : t("copyJob.none")
        }}</span>
      </template>
      <template #actions="{ row }">
        <RowActions :actions="rowActions(asJob(row))" />
      </template>
    </DataTable>

    <el-dialog
      v-model="detailVisible"
      :title="detailTitle"
      width="92%"
      destroy-on-close
      data-test="copy-job-items-dialog"
    >
      <el-alert
        v-if="detailJob?.errorMessage"
        :title="detailJob.errorMessage"
        type="error"
        :closable="false"
        show-icon
      />
      <DataTable
        :key="detailTableKey"
        class="copy-job-table"
        table-id="copy-job-items"
        row-key="seq"
        plain
        :rows="detailRows"
        :columns="itemColumns"
        :loading="detailLoading"
        :failed="detailFailed"
        :total="detailTotal"
        :page="detailQuery.page"
        :size="detailQuery.size"
        @update:page="onDetailPage"
        @update:size="onDetailSize"
        @retry="loadItems"
        @sort-change="sortDetail"
      >
        <template #cell-sourceChannel="{ row }">{{
          channelText(asItem(row).sourceChannel)
        }}</template>
        <template #cell-sourceProductId="{ row }">
          <CopyText class="copy-job__item-id" :value="asItem(row).sourceProductId" hide-tooltip />
        </template>
        <template #cell-sourceShop="{ row }">
          <NameCode
            :name="asItem(row).sourceShopName"
            :code="asItem(row).sourceWeidianShopId || t('copyJob.none')"
            data-test="source-shop"
          />
        </template>
        <template #cell-sourceMerchantNo="{ row }">
          {{ asItem(row).sourceMerchantNo || t("copyJob.none") }}
        </template>
        <template #cell-targetChannel="{ row }">{{
          channelText(asItem(row).targetChannel)
        }}</template>
        <template #cell-targetShop="{ row }">
          <NameCode
            :name="asItem(row).targetShopName"
            :code="asItem(row).targetWeidianShopId || t('copyJob.none')"
            data-test="target-shop"
          />
        </template>
        <template #cell-targetItemId="{ row }">
          <span class="copy-job__item-id" data-test="target-item-id">{{
            asItem(row).targetItemId || t("copyJob.none")
          }}</span>
        </template>
        <template #cell-targetItemUrl="{ row }">
          <a
            v-if="asItem(row).targetItemUrl"
            :href="asItem(row).targetItemUrl ?? undefined"
            target="_blank"
            rel="noopener noreferrer"
            data-test="open-target"
            >{{ t("copyJob.open") }}</a
          >
          <span v-else>{{ t("copyJob.none") }}</span>
        </template>
        <template #cell-status="{ row }"
          ><el-tag
            :type="
              asItem(row).status === 'success'
                ? 'success'
                : asItem(row).status === 'failed'
                  ? 'danger'
                  : 'info'
            "
            >{{ itemStatusText(asItem(row).status) }}</el-tag
          ></template
        >
        <template #cell-errorMessage="{ row }">
          <span v-if="!asItem(row).errorMessage">{{ t("copyJob.none") }}</span>
          <el-tooltip
            v-else
            :content="asItem(row).errorMessage ?? ''"
            :enterable="false"
            :hide-after="0"
            placement="top"
          >
            <span class="copy-job__error" data-test="item-error">{{
              asItem(row).errorMessage
            }}</span>
          </el-tooltip>
        </template>
      </DataTable>
    </el-dialog>
  </div>
</template>

<style scoped>
.copy-job-table :deep(.el-table) {
  --el-table-row-hover-bg-color: var(--tcrm-color-card);
}
.copy-job-table :deep(.el-table__body td.el-table__cell) {
  background-color: var(--tcrm-color-card);
  transition: none;
}
.copy-job-table :deep(.el-table__body tr:hover > td.el-table__cell) {
  background-color: var(--tcrm-color-muted);
}
.copy-job__item-id,
.copy-job__item-id :deep(.copy-text__value) {
  overflow-wrap: anywhere;
  white-space: normal;
}

.copy-job__filter {
  width: 100%;
}

.copy-job__result {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: center;
  gap: 6px;
}

/* R-01: the result tags were flex children that shrank into unreadable slivers when the cell ran
   out of width. Freeze them at their text width; the "has failures" hint drops onto its own line
   inside the cell instead of stretching the row. */
.copy-job__result :deep(.el-tag) {
  flex: none;
  white-space: nowrap;
}

.copy-job__hint {
  flex-basis: 100%;
  color: var(--tcrm-color-danger);
  font-size: var(--tcrm-font-size-help);
}

.copy-job__error {
  display: inline-block;
  max-width: 220px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  vertical-align: bottom;
}

.copy-job__shop-id {
  display: block;
  color: var(--tcrm-color-text-secondary);
  font-size: var(--tcrm-font-size-help);
  line-height: 20px;
}
</style>
