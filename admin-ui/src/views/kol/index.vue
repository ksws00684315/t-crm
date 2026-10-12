<script setup lang="ts">
import { ElMessage, ElMessageBox } from "element-plus";
import { computed, onMounted, reactive, ref } from "vue";
import { useI18n } from "vue-i18n";
import { listKols, updateKolStatus } from "@/api/kol/kol";
import { listBdUsers } from "@/api/system/bd-user";
import type { BdUserVO, KolVO } from "@/api/types";
import CopyText from "@/components/CopyText.vue";
import DataTable, { type DataColumn } from "@/components/DataTable.vue";
import ListSearch, { type SearchChip } from "@/components/ListSearch.vue";
import { useBatchAction } from "@/composables/useBatchAction";
import { useListExport } from "@/composables/useListExport";
import { useUserStore } from "@/stores/user";
import { formatInstant } from "@/utils/format";
import { notifyError } from "@/utils/notify";
import { kolAbilities, linkAbilities } from "@/utils/permission";
import { formatRate } from "@/utils/rate";
import { readPageSize } from "@/utils/table-prefs";
import ChangeBdDialog from "./ChangeBdDialog.vue";
import KolDetailDrawer from "./KolDetailDrawer.vue";
import KolFormDialog from "./KolFormDialog.vue";
import KolImportDialog from "./KolImportDialog.vue";
import KolLinksDrawer from "./KolLinksDrawer.vue";

defineOptions({ name: "KolListPage" });

const { t } = useI18n();
const me = useUserStore();
/** Button visibility only; the backend enforces the same rules (task 04 §4.3). */
const abilities = computed(() => kolAbilities(me.user));
const links = computed(() => linkAbilities(me.user));

const loading = ref(false);
const rows = ref<KolVO[]>([]);
const total = ref(0);
const bdUsers = ref<BdUserVO[]>([]);
const query = reactive<{
  page: number;
  size: number;
  keyword: string;
  country: string;
  status?: 0 | 1;
  bdUserId?: string;
}>({ page: 1, size: readPageSize("kol", 20), keyword: "", country: "" });

function asKol(row: object): KolVO {
  return row as KolVO;
}

const columns = computed<DataColumn[]>(() => [
  { key: "kolCode", label: t("kol.code"), width: 160, mono: true },
  { key: "name", label: t("common.name"), prop: "name", minWidth: 120 },
  { key: "email", label: t("common.email"), minWidth: 200, mono: true },
  { key: "country", label: t("common.country"), prop: "country", width: 80 },
  { key: "bd", label: t("kol.bd"), minWidth: 110 },
  { key: "rate", label: t("kol.defaultRate"), width: 110, amount: true },
  { key: "status", label: t("common.status"), width: 90 },
  { key: "createdAt", label: t("common.createdAt"), width: 180, mono: true },
  { key: "actions", label: t("common.actions"), width: 280 }
]);

/** Chips and empty-state filter-count reflect the last applied search, not in-progress typing. */
const applied = ref({
  keyword: "",
  country: "",
  status: undefined as 0 | 1 | undefined,
  bdUserId: undefined as string | undefined
});

const chips = computed<SearchChip[]>(() => {
  const list: SearchChip[] = [];
  const keyword = applied.value.keyword;
  const country = applied.value.country;
  if (keyword) list.push({ key: "keyword", label: t("common.keyword"), text: keyword });
  if (country) list.push({ key: "country", label: t("common.country"), text: country });
  if (applied.value.status === 0 || applied.value.status === 1) {
    list.push({
      key: "status",
      label: t("common.status"),
      text: applied.value.status === 1 ? t("common.enabled") : t("common.disabled")
    });
  }
  if (applied.value.bdUserId) {
    const bd = bdUsers.value.find(item => item.id === applied.value.bdUserId);
    list.push({
      key: "bd",
      label: t("kol.bd"),
      text: bd
        ? t("kol.bdOption", { nickname: bd.nickname, username: bd.username })
        : applied.value.bdUserId
    });
  }
  return list;
});

const formVisible = ref(false);
const editingKol = ref<KolVO | null>(null);
const bdVisible = ref(false);
const bdKol = ref<KolVO | null>(null);
const detailVisible = ref(false);
const detailId = ref<string | null>(null);
const importVisible = ref(false);
const linksVisible = ref(false);
const linksKol = ref<KolVO | null>(null);

async function load() {
  loading.value = true;
  try {
    const page = await listKols({
      page: query.page,
      size: query.size,
      ...appliedQuery()
    });
    rows.value = page.items;
    total.value = Number(page.total);
  } catch (error) {
    notifyError(error);
  } finally {
    loading.value = false;
  }
}

function appliedQuery() {
  return {
    keyword: applied.value.keyword || undefined,
    country: applied.value.country || undefined,
    status: applied.value.status,
    bdUserId: abilities.value.canManageBd ? applied.value.bdUserId || undefined : undefined
  };
}

const { exporting, exportCsv } = useListExport<KolVO>({
  fileBase: () => t("export.fileKol"),
  fetchPage: (page, size) => listKols({ page, size, ...appliedQuery() }),
  columns: () => [
    { label: t("kol.code"), value: row => row.kolCode },
    { label: t("common.name"), value: row => row.name },
    { label: t("common.email"), value: row => row.email },
    { label: t("common.country"), value: row => row.country },
    { label: t("kol.bd"), value: row => row.bdUserName ?? t("common.unassigned") },
    { label: t("kol.defaultRate"), value: row => formatRate(row.defaultRate) },
    {
      label: t("common.status"),
      value: row => (row.status === 1 ? t("common.enabled") : t("common.disabled"))
    },
    { label: t("common.createdAt"), value: row => formatInstant(row.createTime) }
  ]
});

const selection = ref<KolVO[]>([]);
const batch = useBatchAction();

function batchStatus(target: 0 | 1): void {
  const targets = selection.value.filter(row => row.status !== target);
  if (targets.length === 0) {
    ElMessage.info(t("batch.nothingToChange"));
    return;
  }
  void batch.run({
    rows: targets,
    title: target === 0 ? t("kol.disableTitle") : t("kol.enableTitle"),
    message:
      target === 0
        ? t("batch.confirmDisable", { n: targets.length })
        : t("batch.confirmEnable", { n: targets.length }),
    label: row => row.kolCode,
    action: row => updateKolStatus(row.id, target),
    after: load
  });
}

async function loadBdUsers() {
  if (!abilities.value.canManageBd) return;
  try {
    bdUsers.value = await listBdUsers();
  } catch (error) {
    notifyError(error);
  }
}

function snapshotFilters(): void {
  applied.value = {
    keyword: query.keyword.trim(),
    country: query.country.trim().toUpperCase(),
    status: query.status,
    bdUserId: abilities.value.canManageBd ? query.bdUserId : undefined
  };
}

function search() {
  query.page = 1;
  snapshotFilters();
  void load();
}

function reset() {
  query.keyword = "";
  query.country = "";
  query.status = undefined;
  query.bdUserId = undefined;
  search();
}

function removeChip(key: string): void {
  if (key === "keyword") query.keyword = "";
  if (key === "country") query.country = "";
  if (key === "status") query.status = undefined;
  if (key === "bd") query.bdUserId = undefined;
  search();
}

function onPage(page: number): void {
  query.page = page;
  void load();
}

function onSize(size: number): void {
  query.size = size;
}

function openCreate() {
  editingKol.value = null;
  formVisible.value = true;
}

function openEdit(row: KolVO) {
  editingKol.value = row;
  formVisible.value = true;
}

function openBd(row: KolVO) {
  bdKol.value = row;
  bdVisible.value = true;
}

function openDetail(row: KolVO) {
  detailId.value = row.id;
  detailVisible.value = true;
}

function openLinks(row: KolVO) {
  linksKol.value = row;
  linksVisible.value = true;
}

async function toggleStatus(row: KolVO) {
  const target = row.status === 1 ? 0 : 1;
  const disabling = target === 0;
  try {
    await ElMessageBox.confirm(
      disabling
        ? t("kol.confirmDisable", { code: row.kolCode, name: row.name })
        : t("kol.confirmEnable", { code: row.kolCode, name: row.name }),
      disabling ? t("kol.disableTitle") : t("kol.enableTitle"),
      { type: "warning" }
    );
  } catch {
    return; // cancelled
  }
  try {
    await updateKolStatus(row.id, target);
    ElMessage.success(disabling ? t("kol.disabled") : t("kol.enabled"));
    await load();
  } catch (error) {
    notifyError(error);
  }
}

onMounted(() => {
  void load();
  void loadBdUsers();
});
</script>

<template>
  <div class="list-page">
    <ListSearch :chips="chips" has-more @search="search" @reset="reset" @remove="removeChip">
      <template #primary>
        <el-form-item :label="t('common.keyword')">
          <el-input
            v-model="query.keyword"
            clearable
            maxlength="128"
            :placeholder="t('kol.keywordPlaceholder')"
          />
        </el-form-item>
        <el-form-item :label="t('common.country')">
          <el-input
            v-model="query.country"
            clearable
            maxlength="2"
            :placeholder="t('kol.countryPlaceholder')"
            style="width: 90px"
          />
        </el-form-item>
      </template>
      <template #more>
        <el-form-item :label="t('common.status')">
          <el-select
            v-model="query.status"
            clearable
            :placeholder="t('common.all')"
            style="width: 110px"
          >
            <el-option :label="t('common.enabled')" :value="1" />
            <el-option :label="t('common.disabled')" :value="0" />
          </el-select>
        </el-form-item>
        <el-form-item v-if="abilities.canManageBd" :label="t('kol.bd')" data-test="bd-filter">
          <el-select
            v-model="query.bdUserId"
            clearable
            filterable
            :placeholder="t('common.all')"
            style="width: 160px"
          >
            <el-option
              v-for="bd in bdUsers"
              :key="bd.id"
              :label="t('kol.bdOption', { nickname: bd.nickname, username: bd.username })"
              :value="bd.id"
            />
          </el-select>
        </el-form-item>
      </template>
      <template v-if="abilities.canWrite" #actions>
        <el-button type="success" data-test="create" @click="openCreate">{{
          t("kol.create")
        }}</el-button>
        <el-button data-test="import" @click="importVisible = true">{{
          t("kol.import")
        }}</el-button>
      </template>
    </ListSearch>

    <DataTable
      table-id="kol"
      :rows="rows"
      :columns="columns"
      :loading="loading"
      :total="total"
      :page="query.page"
      :size="query.size"
      :filter-count="chips.length"
      exportable
      :exporting="exporting"
      :selectable="abilities.canWrite"
      @export="exportCsv"
      @selection-change="selection = $event as KolVO[]"
      @update:page="onPage"
      @update:size="onSize"
      @refresh="load"
      @clear-filters="reset"
    >
      <template #batch>
        <el-button
          type="success"
          plain
          size="small"
          :loading="batch.running.value"
          data-test="batch-enable"
          @click="batchStatus(1)"
          >{{ t("batch.enable") }}</el-button
        >
        <el-button
          type="danger"
          plain
          size="small"
          :loading="batch.running.value"
          data-test="batch-disable"
          @click="batchStatus(0)"
          >{{ t("batch.disable") }}</el-button
        >
      </template>
      <template #cell-kolCode="{ row }"><CopyText :value="asKol(row).kolCode" /></template>
      <template #cell-email="{ row }"><CopyText :value="asKol(row).email" /></template>
      <template #cell-bd="{ row }">{{ asKol(row).bdUserName ?? t("common.unassigned") }}</template>
      <template #cell-rate="{ row }">{{ formatRate(asKol(row).defaultRate) }}</template>
      <template #cell-status="{ row }">
        <el-tag :type="asKol(row).status === 1 ? 'success' : 'info'">
          {{ asKol(row).status === 1 ? t("common.enabled") : t("common.disabled") }}
        </el-tag>
      </template>
      <template #cell-createdAt="{ row }">{{ formatInstant(asKol(row).createTime) }}</template>
      <template #actions="{ row }">
        <el-button link type="primary" @click="openDetail(asKol(row))">{{
          t("common.detail")
        }}</el-button>
        <el-button
          v-if="links.canViewLinks"
          link
          type="primary"
          data-test="links"
          @click="openLinks(asKol(row))"
        >
          {{ t("kol.links") }}
        </el-button>
        <template v-if="abilities.canWrite">
          <el-button link type="primary" data-test="edit" @click="openEdit(asKol(row))">
            {{ t("common.edit") }}
          </el-button>
          <el-button
            link
            :type="asKol(row).status === 1 ? 'danger' : 'success'"
            data-test="status"
            @click="toggleStatus(asKol(row))"
          >
            {{ asKol(row).status === 1 ? t("common.disabled") : t("common.enabled") }}
          </el-button>
        </template>
        <el-button
          v-if="abilities.canManageBd"
          link
          type="warning"
          data-test="change-bd"
          @click="openBd(asKol(row))"
        >
          {{ t("kol.changeBd") }}
        </el-button>
      </template>
    </DataTable>

    <KolFormDialog
      v-model="formVisible"
      :kol="editingKol"
      :can-pick-bd="abilities.canManageBd"
      :bd-users="bdUsers"
      @saved="load"
    />
    <ChangeBdDialog v-model="bdVisible" :kol="bdKol" :bd-users="bdUsers" @saved="load" />
    <KolDetailDrawer v-model="detailVisible" :kol-id="detailId" />
    <KolImportDialog v-model="importVisible" @finished="search" />
    <KolLinksDrawer
      v-model="linksVisible"
      :kol-code="linksKol?.kolCode ?? null"
      :kol-name="linksKol?.name"
    />
  </div>
</template>
