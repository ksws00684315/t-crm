<script setup lang="ts">
import { ElMessage, ElMessageBox } from "element-plus";
import { computed, onMounted, reactive, ref, watch } from "vue";
import { useI18n } from "vue-i18n";
import { useRoute } from "vue-router";
import {
  cancelBdTransfer,
  listBdTransfers,
  type BdTransferStatus,
  type BdTransferVO
} from "@/api/kol/bd-transfer";
import { listBdUsers } from "@/api/system/bd-user";
import type { BdUserVO } from "@/api/types";
import CopyText from "@/components/CopyText.vue";
import DataTable, { type DataColumn } from "@/components/DataTable.vue";
import ListSearch, { type SearchChip } from "@/components/ListSearch.vue";
import { formatInstant } from "@/utils/format";
import { ApiError } from "@/utils/http/error";
import { notifyError } from "@/utils/notify";
import { readPageSize } from "@/utils/table-prefs";

defineOptions({ name: "KolBdTransferPage" });

const { t } = useI18n();
const route = useRoute();

const STATUSES: BdTransferStatus[] = ["SCHEDULED", "DONE", "CANCELLED"];

const loading = ref(false);
const rows = ref<BdTransferVO[]>([]);
const total = ref(0);
const bdUsers = ref<BdUserVO[]>([]);

/** Query params `status` and `kolCode` arrive from notification links (task 20). */
function filtersFromRoute() {
  const status = route.query.status;
  const kolCode = route.query.kolCode;
  return {
    status:
      typeof status === "string" && STATUSES.includes(status as BdTransferStatus)
        ? (status as BdTransferStatus)
        : undefined,
    kolCode: typeof kolCode === "string" ? kolCode : ""
  };
}

const query = reactive<{
  page: number;
  size: number;
  kolCode: string;
  status?: BdTransferStatus;
  fromBdUserId?: string;
  toBdUserId?: string;
}>({ page: 1, size: readPageSize("kol-bd-transfers", 20), ...filtersFromRoute() });

function asTransfer(row: object): BdTransferVO {
  return row as BdTransferVO;
}

function statusLabel(status: BdTransferStatus): string {
  return t(`bdTransfer.status.${status}`);
}

function statusTag(status: BdTransferStatus): "warning" | "success" | "info" {
  if (status === "SCHEDULED") return "warning";
  if (status === "DONE") return "success";
  return "info";
}

const columns = computed<DataColumn[]>(() => [
  { key: "kol", label: t("bdTransfer.kol"), minWidth: 170 },
  { key: "fromBd", label: t("bdTransfer.fromBd"), minWidth: 120 },
  { key: "toBd", label: t("bdTransfer.toBd"), minWidth: 120 },
  { key: "effectiveAt", label: t("bdTransfer.effectiveAt"), width: 170, mono: true },
  { key: "status", label: t("common.status"), width: 90 },
  { key: "executedAt", label: t("bdTransfer.executedAt"), width: 170, mono: true },
  { key: "reason", label: t("bdTransfer.reason"), minWidth: 140, tooltip: true },
  { key: "cancelReason", label: t("bdTransfer.cancelReason"), minWidth: 140, tooltip: true },
  { key: "createdAt", label: t("common.createdAt"), width: 170, mono: true },
  { key: "actions", label: t("common.actions"), width: 110 }
]);

const applied = ref({
  kolCode: "",
  status: undefined as BdTransferStatus | undefined,
  fromBdUserId: undefined as string | undefined,
  toBdUserId: undefined as string | undefined
});

const chips = computed<SearchChip[]>(() => {
  const list: SearchChip[] = [];
  if (applied.value.kolCode) {
    list.push({ key: "kolCode", label: t("kol.code"), text: applied.value.kolCode });
  }
  if (applied.value.status) {
    list.push({
      key: "status",
      label: t("common.status"),
      text: statusLabel(applied.value.status)
    });
  }
  if (applied.value.fromBdUserId) {
    list.push({
      key: "fromBd",
      label: t("bdTransfer.fromBd"),
      text: bdNameOrId(applied.value.fromBdUserId)
    });
  }
  if (applied.value.toBdUserId) {
    list.push({
      key: "toBd",
      label: t("bdTransfer.toBd"),
      text: bdNameOrId(applied.value.toBdUserId)
    });
  }
  return list;
});

function bdNameOrId(id: string): string {
  const bd = bdUsers.value.find(item => item.id === id);
  return bd ? t("kol.bdOption", { nickname: bd.nickname, username: bd.username }) : id;
}

async function load() {
  loading.value = true;
  try {
    const page = await listBdTransfers({
      page: query.page,
      size: query.size,
      kolCode: applied.value.kolCode || undefined,
      status: applied.value.status,
      fromBdUserId: applied.value.fromBdUserId || undefined,
      toBdUserId: applied.value.toBdUserId || undefined
    });
    rows.value = page.items;
    total.value = Number(page.total);
  } catch (error) {
    notifyError(error);
  } finally {
    loading.value = false;
  }
}

function snapshotFilters(): void {
  applied.value = {
    kolCode: query.kolCode.trim().toUpperCase(),
    status: query.status,
    fromBdUserId: query.fromBdUserId,
    toBdUserId: query.toBdUserId
  };
}

function search() {
  query.page = 1;
  snapshotFilters();
  void load();
}

function reset() {
  query.kolCode = "";
  query.status = undefined;
  query.fromBdUserId = undefined;
  query.toBdUserId = undefined;
  search();
}

function removeChip(key: string): void {
  if (key === "kolCode") query.kolCode = "";
  if (key === "status") query.status = undefined;
  if (key === "fromBd") query.fromBdUserId = undefined;
  if (key === "toBd") query.toBdUserId = undefined;
  search();
}

function onPage(page: number): void {
  query.page = page;
  void load();
}

function onSize(size: number): void {
  query.size = size;
}

async function cancelScheduled(row: BdTransferVO) {
  try {
    await ElMessageBox.confirm(
      t("bdTransfer.cancelConfirm", { code: row.kolCode ?? "" }),
      t("bdTransfer.cancelTitle"),
      { type: "warning" }
    );
  } catch {
    return; // cancelled
  }
  try {
    await cancelBdTransfer(row.id);
    ElMessage.success(t("bdTransfer.cancelled"));
    await load();
  } catch (error) {
    notifyError(error);
    // Someone else executed or cancelled the reservation meanwhile.
    if (error instanceof ApiError && error.errorCode === "KOL_TRANSFER_NOT_SCHEDULED") await load();
  }
}

/** One path one tab, but notification links change the query on an already cached page. */
watch(
  () => [route.query.status, route.query.kolCode],
  () => {
    const fromRoute = filtersFromRoute();
    query.kolCode = fromRoute.kolCode;
    query.status = fromRoute.status;
    search();
  }
);

onMounted(() => {
  snapshotFilters();
  void load();
  void listBdUsers()
    .then(items => {
      bdUsers.value = items;
    })
    .catch(notifyError);
});
</script>

<template>
  <div class="list-page">
    <ListSearch :chips="chips" has-more @search="search" @reset="reset" @remove="removeChip">
      <template #primary>
        <el-form-item :label="t('kol.code')">
          <el-input
            v-model="query.kolCode"
            clearable
            maxlength="16"
            :placeholder="t('bdTransfer.kolCodePlaceholder')"
          />
        </el-form-item>
        <el-form-item :label="t('common.status')">
          <el-select
            v-model="query.status"
            clearable
            :placeholder="t('common.all')"
            style="width: 130px"
          >
            <el-option
              v-for="status in STATUSES"
              :key="status"
              :label="statusLabel(status)"
              :value="status"
            />
          </el-select>
        </el-form-item>
      </template>
      <template #more>
        <el-form-item :label="t('bdTransfer.fromBd')">
          <el-select
            v-model="query.fromBdUserId"
            clearable
            filterable
            :placeholder="t('common.all')"
            style="width: 180px"
          >
            <el-option
              v-for="bd in bdUsers"
              :key="bd.id"
              :label="t('kol.bdOption', { nickname: bd.nickname, username: bd.username })"
              :value="bd.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item :label="t('bdTransfer.toBd')">
          <el-select
            v-model="query.toBdUserId"
            clearable
            filterable
            :placeholder="t('common.all')"
            style="width: 180px"
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
    </ListSearch>

    <DataTable
      table-id="kol-bd-transfers"
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
      <template #cell-kol="{ row }">
        <CopyText v-if="asTransfer(row).kolCode" :value="asTransfer(row).kolCode ?? ''" />
        <span v-if="asTransfer(row).kolName" class="bd-transfers__name">{{
          asTransfer(row).kolName
        }}</span>
        <span v-if="!asTransfer(row).kolCode && !asTransfer(row).kolName">-</span>
      </template>
      <template #cell-fromBd="{ row }">
        {{ asTransfer(row).fromBdUserName ?? t("common.unassigned") }}
      </template>
      <template #cell-toBd="{ row }">
        {{ asTransfer(row).toBdUserName ?? t("common.unassigned") }}
      </template>
      <template #cell-effectiveAt="{ row }">{{
        formatInstant(asTransfer(row).effectiveAt)
      }}</template>
      <template #cell-status="{ row }">
        <el-tag :type="statusTag(asTransfer(row).status)" data-test="status-tag">
          {{ statusLabel(asTransfer(row).status) }}
        </el-tag>
      </template>
      <template #cell-executedAt="{ row }">{{
        formatInstant(asTransfer(row).executedAt)
      }}</template>
      <template #cell-reason="{ row }">{{ asTransfer(row).reason || "-" }}</template>
      <template #cell-cancelReason="{ row }">{{ asTransfer(row).cancelReason || "-" }}</template>
      <template #cell-createdAt="{ row }">{{ formatInstant(asTransfer(row).createTime) }}</template>
      <template #actions="{ row }">
        <el-button
          v-if="asTransfer(row).status === 'SCHEDULED'"
          link
          type="danger"
          data-test="cancel-transfer"
          @click="cancelScheduled(asTransfer(row))"
        >
          {{ t("bdTransfer.cancelTitle") }}
        </el-button>
      </template>
    </DataTable>
  </div>
</template>

<style scoped>
.bd-transfers__name {
  margin-left: var(--tcrm-space-1);
  color: var(--tcrm-color-text-secondary);
}
</style>
