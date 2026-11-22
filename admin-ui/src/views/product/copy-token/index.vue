<script setup lang="ts">
import { ElMessage, ElMessageBox } from "element-plus";
import { computed, onMounted, reactive, ref, watch } from "vue";
import { useRoute } from "vue-router";
import { useI18n } from "vue-i18n";
import {
  listCopyTokens,
  revealCopyToken,
  revokeCopyToken,
  type CopyTokenDisplayStatus
} from "@/api/kol/copy-token";
import { listJobsByToken, type TokenJobVO } from "@/api/product/copy-job";
import CopyText from "@/components/CopyText.vue";
import { listKols } from "@/api/kol/kol";
import type { CopyTokenVO, KolVO } from "@/api/types";
import DataTable, { type DataColumn } from "@/components/DataTable.vue";
import ListSearch, { type SearchChip } from "@/components/ListSearch.vue";
import NameCode from "@/components/NameCode.vue";
import RowActions, { type RowAction } from "@/components/RowActions.vue";
import { useUserStore } from "@/stores/user";
import { copyText } from "@/utils/copy";
import { formatInstant, formatNameWithCode } from "@/utils/format";
import { notifyError } from "@/utils/notify";
import { accessOf } from "@/utils/permission";
import { useLocaleStore } from "@/stores/locale";
import { readPageSize } from "@/utils/table-prefs";
import CopyTokenCreateDialog from "./CopyTokenCreateDialog.vue";

defineOptions({ name: "CopyTokenPage" });

const { t } = useI18n();
const route = useRoute();
const me = useUserStore();
const canManage = computed(() => accessOf(me.user).can("kol:copy-token"));

const STATUSES: CopyTokenDisplayStatus[] = ["ACTIVE", "EXHAUSTED", "EXPIRED", "REVOKED"];

const loading = ref(false);
const loadFailed = ref(false);
const rows = ref<CopyTokenVO[]>([]);
const total = ref(0);
let listRequest = 0;
const createVisible = ref(false);
const revealing = ref<string | null>(null);
const kolOptions = ref<KolVO[]>([]);
const kolSearching = ref(false);

const query = reactive<{
  page: number;
  size: number;
  hint: string;
  jobCode: string;
  kolId?: string;
  targetChannel?: string;
  status?: CopyTokenDisplayStatus;
}>({
  page: 1,
  size: readPageSize("copy-tokens", 20),
  hint: "",
  jobCode: ""
});

const applied = ref({
  hint: "",
  jobCode: "",
  kolId: undefined as string | undefined,
  targetChannel: undefined as string | undefined,
  status: undefined as CopyTokenDisplayStatus | undefined
});

function asRow(row: object): CopyTokenVO {
  return row as CopyTokenVO;
}

function statusTag(status: CopyTokenDisplayStatus): "success" | "warning" | "info" | "danger" {
  if (status === "ACTIVE") return "success";
  if (status === "EXHAUSTED") return "info";
  if (status === "EXPIRED") return "warning";
  return "info";
}

/** Map display status to locale key without dynamic casting issues. */
function statusText(status: CopyTokenDisplayStatus): string {
  switch (status) {
    case "ACTIVE":
      return t("copyToken.statusActive");
    case "EXHAUSTED":
      return t("copyToken.statusExhausted");
    case "EXPIRED":
      return t("copyToken.statusExpired");
    case "REVOKED":
      return t("copyToken.statusRevoked");
  }
}

const zhWidth = computed(() => useLocaleStore().locale === "zh-CN");
/** One width per language: the action labels differ in length (norm 10 §3). */
const px = (zh: number, en: number): number => (zhWidth.value ? zh : en);

const columns = computed<DataColumn[]>(() => {
  const list: DataColumn[] = [
    { key: "hint", align: "left", label: t("copyToken.hint"), minWidth: 200 },
    { key: "kol", align: "left" as const, label: t("copyToken.kol"), minWidth: 160 },
    { key: "channel", label: t("copyToken.channel"), width: 90 },
    { key: "shop", align: "left" as const, label: t("copyToken.shop"), minWidth: 120 },
    { key: "usage", label: t("copyToken.usage"), width: 110 },
    { key: "validMinutes", label: t("copyToken.validMinutes"), width: 100 },
    { key: "expiresAt", label: t("copyToken.expiresAt"), width: px(170, 180) },
    { key: "status", label: t("common.status"), width: 100 },
    { key: "createTime", label: t("common.createdAt"), width: px(170, 180) }
  ];
  if (canManage.value) {
    // Copy + Revoke: zh DOM 66px / en canvas 83px + gap and cell padding (task 59 measurements).
    list.push({ key: "actions", label: t("common.actions"), width: px(200, 235) });
  }
  return list;
});

const chips = computed<SearchChip[]>(() => {
  const list: SearchChip[] = [];
  if (applied.value.hint) {
    list.push({ key: "hint", label: t("copyToken.hint"), text: applied.value.hint });
  }
  if (applied.value.jobCode) {
    list.push({ key: "jobCode", label: t("copyJob.jobCode"), text: applied.value.jobCode });
  }
  if (applied.value.kolId) {
    const kol = kolOptions.value.find(k => k.id === applied.value.kolId);
    list.push({
      key: "kol",
      label: t("copyToken.kol"),
      text: kol ? `${kol.name} (${kol.kolCode})` : applied.value.kolId
    });
  }
  if (applied.value.targetChannel) {
    list.push({
      key: "channel",
      label: t("copyToken.channel"),
      text: t("copyToken.channelWeidian")
    });
  }
  if (applied.value.status) {
    list.push({
      key: "status",
      label: t("common.status"),
      text: statusText(applied.value.status)
    });
  }
  return list;
});

async function load() {
  const request = ++listRequest;
  if (!canManage.value) {
    rows.value = [];
    total.value = 0;
    return;
  }
  loading.value = true;
  loadFailed.value = false;
  try {
    const page = await listCopyTokens({
      page: query.page,
      size: query.size,
      hint: applied.value.hint || undefined,
      ...(applied.value.jobCode ? { jobCode: applied.value.jobCode } : {}),
      kolId: applied.value.kolId,
      targetChannel: applied.value.targetChannel,
      status: applied.value.status
    });
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

function search() {
  applied.value = {
    hint: query.hint.trim(),
    jobCode: query.jobCode.trim(),
    kolId: query.kolId,
    targetChannel: query.targetChannel,
    status: query.status
  };
  query.page = 1;
  void load();
}

function reset() {
  query.hint = "";
  query.jobCode = "";
  query.kolId = undefined;
  query.targetChannel = undefined;
  query.status = undefined;
  search();
}

function removeChip(key: string) {
  if (key === "jobCode") query.jobCode = "";
  if (key === "hint") query.hint = "";
  if (key === "kol") query.kolId = undefined;
  if (key === "channel") query.targetChannel = undefined;
  if (key === "status") query.status = undefined;
  search();
}

function onPage(page: number) {
  query.page = page;
  void load();
}

function onSize(size: number) {
  query.size = size;
  query.page = 1;
  void load();
}

async function searchKols(keyword: string) {
  if (!keyword.trim()) {
    kolOptions.value = [];
    return;
  }
  kolSearching.value = true;
  try {
    const page = await listKols({ keyword: keyword.trim(), page: 1, size: 20 });
    kolOptions.value = page.items;
  } catch (error) {
    notifyError(error);
  } finally {
    kolSearching.value = false;
  }
}

function clickReveal(id: string) {
  void (async () => {
    revealing.value = id;
    try {
      const { token: plain } = await revealCopyToken(id);
      const ok = await copyText(plain);
      if (ok) ElMessage.success(t("copyToken.copied"));
      else ElMessage.error(t("copy.failed"));
    } catch (error) {
      notifyError(error);
    } finally {
      revealing.value = null;
    }
  })();
}

async function clickRevoke(id: string) {
  if (!rows.value.some(row => row.id === id && canRevoke(row))) return;
  try {
    await ElMessageBox.confirm(t("copyToken.revokeConfirm"), t("copyToken.revoke"), {
      type: "warning"
    });
  } catch {
    return;
  }
  try {
    await revokeCopyToken(id);
    ElMessage.success(t("copyToken.revokeDone"));
    await load();
  } catch (error) {
    notifyError(error);
  }
}

function canRevoke(row: CopyTokenVO): boolean {
  return row.displayStatus === "ACTIVE";
}

const jobsVisible = ref(false);
const jobsToken = ref("");
const jobRows = ref<TokenJobVO[]>([]);
const jobsTotal = ref(0);
const jobsLoading = ref(false);
const jobsFailed = ref(false);
const jobsPage = reactive({ page: 1, size: readPageSize("copy-token-jobs", 20) });
let jobsRequest = 0;

const jobColumns = computed<DataColumn[]>(() => [
  { key: "jobCode", label: t("copyJob.jobCode"), minWidth: 195, align: "left", tooltip: false },
  { key: "status", label: t("copyJob.status"), minWidth: 100 },
  { key: "totalCount", label: t("copyToken.jobTotalCount"), minWidth: 90 },
  { key: "createTime", label: t("copyJob.createTime"), minWidth: 175 }
]);

function asJob(row: object) {
  return row as TokenJobVO;
}

async function loadJobs() {
  const request = ++jobsRequest;
  jobsLoading.value = true;
  jobsFailed.value = false;
  try {
    const result = await listJobsByToken(jobsToken.value, { ...jobsPage });
    if (request !== jobsRequest) return;
    jobRows.value = result.items;
    jobsTotal.value = Number(result.total);
  } catch (error) {
    if (request !== jobsRequest) return;
    jobsFailed.value = true;
    notifyError(error);
  } finally {
    if (request === jobsRequest) jobsLoading.value = false;
  }
}

function openJobs(id: string) {
  jobsToken.value = id;
  jobsPage.page = 1;
  jobRows.value = [];
  jobsTotal.value = 0;
  jobsVisible.value = true;
  void loadJobs();
}

function changeJobsPage(page: number) {
  jobsPage.page = page;
  void loadJobs();
}

function changeJobsSize(size: number) {
  jobsPage.size = size;
  changeJobsPage(1);
}

function rowActions(row: CopyTokenVO): RowAction[] {
  return [
    {
      key: "jobs",
      testId: "token-jobs",
      label: t("copyToken.jobDetails"),
      onClick: () => openJobs(row.id)
    },
    {
      key: "copy",
      label: t("common.copy"),
      testId: "reveal",
      disabled: revealing.value === row.id,
      onClick: () => clickReveal(row.id)
    },
    {
      key: "revoke",
      label: t("copyToken.revoke"),
      testId: "revoke",
      type: "danger",
      disabled: !canRevoke(row),
      tooltip: canRevoke(row) ? undefined : t("copyToken.revokeDisabled"),
      onClick: () => void clickRevoke(row.id)
    }
  ];
}

function channelText(channel: string): string {
  return channel === "WEIDIAN" ? t("copyToken.channelWeidian") : channel;
}

watch(
  () => route?.fullPath,
  () => {
    if (route?.query.status !== "ACTIVE" || route.name !== "CopyTokenList") return;
    query.hint = "";
    query.kolId = undefined;
    query.status = "ACTIVE";
    search();
  },
  { immediate: true }
);

onMounted(() => {
  if (!query.status) void load();
});
</script>

<template>
  <div v-if="!canManage" class="empty">{{ t("errors.AUTH_FORBIDDEN") }}</div>
  <div v-else class="list-page">
    <ListSearch :chips="chips" has-more @search="search" @reset="reset" @remove="removeChip">
      <template #primary>
        <el-form-item :label="t('copyToken.hint')">
          <el-input
            v-model="query.hint"
            clearable
            :placeholder="t('copyToken.hintPlaceholder')"
            data-test="filter-hint"
          />
        </el-form-item>
        <el-form-item :label="t('copyJob.jobCode')">
          <el-input v-model="query.jobCode" maxlength="64" clearable data-test="filter-job-code" />
        </el-form-item>
        <el-form-item :label="t('copyToken.kol')">
          <el-select
            v-model="query.kolId"
            filterable
            remote
            clearable
            :remote-method="searchKols"
            :loading="kolSearching"
            :placeholder="t('copyToken.kolPlaceholder')"
            data-test="filter-kol"
            class="filter-w-md"
          >
            <el-option
              v-for="kol in kolOptions"
              :key="kol.id"
              :label="formatNameWithCode(kol.name, kol.kolCode)"
              :value="kol.id"
              ><NameCode :name="kol.name" :code="kol.kolCode"
            /></el-option>
          </el-select>
        </el-form-item>
        <el-form-item :label="t('copyToken.channel')">
          <el-select
            v-model="query.targetChannel"
            clearable
            data-test="filter-channel"
            class="filter-w-sm"
          >
            <el-option :label="t('copyToken.channelWeidian')" value="WEIDIAN" />
          </el-select>
        </el-form-item>
      </template>
      <template #more>
        <el-form-item :label="t('common.status')">
          <el-select v-model="query.status" clearable data-test="filter-status" class="filter-w-sm">
            <el-option
              v-for="status in STATUSES"
              :key="status"
              :label="statusText(status)"
              :value="status"
            />
          </el-select>
        </el-form-item>
      </template>
      <template #actions>
        <el-button type="primary" data-test="create-token" @click="createVisible = true">
          {{ t("copyToken.create") }}
        </el-button>
      </template>
    </ListSearch>

    <DataTable
      table-id="copy-tokens"
      fill-available-height
      :columns="columns"
      :rows="rows"
      :loading="loading"
      :failed="loadFailed"
      :total="total"
      :page="query.page"
      :size="query.size"
      :filter-count="chips.length"
      @update:page="onPage"
      @update:size="onSize"
      @refresh="load"
      @retry="load"
      @clear-filters="reset"
    >
      <template #cell-hint="{ row }">
        <span>{{ asRow(row).tokenHint }}</span>
      </template>
      <template #cell-kol="{ row }">
        <NameCode :name="asRow(row).kolName" :code="asRow(row).kolCode" />
      </template>
      <template #cell-channel="{ row }">
        {{ channelText(asRow(row).targetChannel) }}
      </template>
      <template #cell-shop="{ row }">
        {{
          asRow(row).targetShopName
            ? `${asRow(row).targetShopName} (${asRow(row).targetShopCode ?? ""})`
            : t("copyToken.shopAuto")
        }}
      </template>
      <template #cell-usage="{ row }">
        <span data-test="usage">{{ asRow(row).used }} / {{ asRow(row).quota }}</span>
      </template>
      <template #cell-validMinutes="{ row }">{{ asRow(row).validMinutes }}</template>
      <template #cell-expiresAt="{ row }">{{ formatInstant(asRow(row).expiresAt) }}</template>
      <template #cell-status="{ row }">
        <el-tag :type="statusTag(asRow(row).displayStatus)" data-test="status">
          {{ statusText(asRow(row).displayStatus) }}
        </el-tag>
      </template>
      <template #cell-createTime="{ row }">{{ formatInstant(asRow(row).createTime) }}</template>
      <template #actions="{ row }">
        <RowActions :actions="rowActions(asRow(row))" />
      </template>
    </DataTable>

    <el-drawer v-model="jobsVisible" :title="t('copyToken.jobDetails')" size="min(760px, 100%)">
      <DataTable
        table-id="copy-token-jobs"
        row-key="id"
        plain
        :rows="jobRows"
        :columns="jobColumns"
        :loading="jobsLoading"
        :failed="jobsFailed"
        :total="jobsTotal"
        :page="jobsPage.page"
        :size="jobsPage.size"
        :empty-text="t('copyToken.noJobs')"
        @update:page="changeJobsPage"
        @update:size="changeJobsSize"
        @retry="loadJobs"
      >
        <template #cell-jobCode="{ row }"><CopyText :value="asJob(row).jobCode" /></template>
        <template #cell-status="{ row }">{{
          asJob(row).status ? t(`copyJob.jobStatus.${asJob(row).status}`) : ""
        }}</template>
        <template #cell-createTime="{ row }">{{ formatInstant(asJob(row).createTime) }}</template>
      </DataTable>
    </el-drawer>

    <CopyTokenCreateDialog v-model="createVisible" @created="load" />
  </div>
</template>

<style scoped>
.token-filter {
  width: 100%;
}
.empty {
  padding: 24px;
}
.kol-code {
  margin-left: 6px;
}
</style>
