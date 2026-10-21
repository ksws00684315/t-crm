<script setup lang="ts">
import { ElMessage, ElMessageBox } from "element-plus";
import { computed, onMounted, reactive, ref } from "vue";
import { useI18n } from "vue-i18n";
import type { TransferTarget } from "@/api/kol/bd-transfer";
import { listKols } from "@/api/kol/kol";
import { listUsers, updateUserStatus } from "@/api/system/user";
import type { CompanyRole, UserVO } from "@/api/types";
import DataTable, { type DataColumn } from "@/components/DataTable.vue";
import ListSearch, { type SearchChip } from "@/components/ListSearch.vue";
import { useBatchAction } from "@/composables/useBatchAction";
import { useListExport } from "@/composables/useListExport";
import { useUserStore } from "@/stores/user";
import { isMultiCompany } from "@/utils/company";
import { formatInstant } from "@/utils/format";
import { notifyError } from "@/utils/notify";
import {
  canExport,
  isStaff,
  isSuperAdminAccount,
  permissionText,
  postLabel,
  roleLabel,
  SELECTABLE_ROLES
} from "@/utils/permission";
import { readPageSize } from "@/utils/table-prefs";
import BdTransferDialog from "@/views/kol/BdTransferDialog.vue";
import PermissionsDialog from "./PermissionsDialog.vue";
import ResetPasswordDialog from "./ResetPasswordDialog.vue";
import UserFormDialog from "./UserFormDialog.vue";

defineOptions({ name: "SystemUserPage" });

const { t, locale } = useI18n();
const me = useUserStore();
/** Widths are tuned for the 14px Chinese labels; English needs more room and scrolls instead. */
const px = (zh: number, en: number): number => (locale.value === "zh-CN" ? zh : en);
const exportAllowed = computed(() => canExport(me.user));
const loading = ref(false);
const rows = ref<UserVO[]>([]);
const total = ref(0);
const query = reactive<{
  page: number;
  size: number;
  username: string;
  role?: CompanyRole;
  status?: 0 | 1;
}>({
  page: 1,
  size: readPageSize("users", 20),
  username: ""
});

function asUser(row: object): UserVO {
  return row as UserVO;
}

const columns = computed<DataColumn[]>(() => [
  { key: "username", label: t("common.username"), prop: "username", minWidth: 130 },
  { key: "nickname", label: t("common.nickname"), prop: "nickname", minWidth: 120 },
  { key: "role", label: t("user.role"), width: 120 },
  { key: "post", label: t("user.post"), width: 90 },
  { key: "permissions", label: t("user.permissionCount"), width: 92 },
  { key: "dataScope", label: t("user.dataScope"), width: 92 },
  { key: "status", label: t("common.status"), width: 80 },
  { key: "lastLogin", label: t("user.lastLogin"), width: 170 },
  { key: "createdAt", label: t("common.createdAt"), width: 170 },
  // Five actions on a BD row need room for Chinese text, 8px gaps and cell padding; the count and
  // scope columns only show a short value, so their slack pays for it and the row keeps one line.
  // English labels are about a third longer, so that locale gets a wider column and scrolls.
  { key: "actions", label: t("common.actions"), width: px(340, 444) }
]);

const applied = ref({
  username: "",
  role: undefined as typeof query.role,
  status: undefined as 0 | 1 | undefined
});

const chips = computed<SearchChip[]>(() => {
  const list: SearchChip[] = [];
  const username = applied.value.username;
  if (username) list.push({ key: "username", label: t("common.username"), text: username });
  if (applied.value.role) {
    list.push({ key: "role", label: t("user.role"), text: roleLabel(applied.value.role) });
  }
  if (applied.value.status === 0 || applied.value.status === 1) {
    list.push({
      key: "status",
      label: t("common.status"),
      text: applied.value.status === 1 ? t("common.enabled") : t("common.disabled")
    });
  }
  return list;
});

const formVisible = ref(false);
const editingUser = ref<UserVO | null>(null);
const resetVisible = ref(false);
const resetUser = ref<UserVO | null>(null);
const permissionsVisible = ref(false);
const permissionsUser = ref<UserVO | null>(null);
/** KOLs per BD row (task 16b): drives the transfer button and the disable confirmation. */
const kolCounts = ref<Record<string, number>>({});
const transferVisible = ref(false);
const transferTarget = ref<TransferTarget | null>(null);

function isBdRow(row: UserVO): boolean {
  return isStaff(row) && row.dataScope === "OWN";
}

function bdCount(row: UserVO): number | undefined {
  return kolCounts.value[row.id];
}

async function loadKolCounts(rows: UserVO[]) {
  const bds = rows.filter(isBdRow);
  await Promise.all(
    bds.map(async row => {
      try {
        // Super administrator context, so bdUserId filters as typed (backend ignores it for OWN).
        const page = await listKols({ bdUserId: row.id, page: 1, size: 1 });
        kolCounts.value[row.id] = Number(page.total);
      } catch {
        // Count stays unknown: the transfer still works, the dialogs just show less context.
      }
    })
  );
}

function openTransfer(row: UserVO) {
  transferTarget.value = {
    fromBdUserId: row.id,
    fromBdName: row.nickname || row.username,
    count: bdCount(row) ?? 0
  };
  transferVisible.value = true;
}

function permissionEntries(row: UserVO): { code: string; name: string }[] {
  return (row.permissions ?? []).map(code => ({ code, name: permissionText(code, "name") }));
}

function appliedQuery() {
  return {
    username: applied.value.username || undefined,
    role: applied.value.role,
    status: applied.value.status
  };
}

const { exporting, exportCsv } = useListExport<UserVO>({
  fileBase: () => t("export.fileUser"),
  fetchPage: (page, size) => listUsers({ page, size, ...appliedQuery() }),
  columns: () => [
    { label: t("common.username"), value: row => row.username },
    { label: t("common.nickname"), value: row => row.nickname },
    { label: t("user.role"), value: row => (row.role ? roleLabel(row.role) : "") },
    { label: t("user.post"), value: row => postLabel(row.postTemplate) },
    {
      label: t("user.permissionCount"),
      value: row =>
        isSuperAdminAccount(row)
          ? t("user.allPermissionsShort")
          : t("user.permissionItems", { n: row.permissions?.length ?? 0 })
    },
    {
      label: t("user.dataScope"),
      value: row => (row.dataScope === "ALL" ? t("scope.allShort") : t("scope.ownShort"))
    },
    {
      label: t("common.status"),
      value: row => (row.status === 1 ? t("common.enabled") : t("common.disabled"))
    },
    { label: t("user.lastLogin"), value: row => formatInstant(row.lastLoginTime) },
    { label: t("common.createdAt"), value: row => formatInstant(row.createTime) }
  ]
});

const selection = ref<UserVO[]>([]);
const batch = useBatchAction();
/** The row action is disabled for the signed-in account, so selection is too. */
const selectableUser = (row: object) => (row as UserVO).id !== me.user?.id;

function batchStatus(target: 0 | 1): void {
  const targets = selection.value.filter(row => row.status !== target && selectableUser(row));
  if (targets.length === 0) {
    ElMessage.info(t("batch.nothingToChange"));
    return;
  }
  void batch.run({
    rows: targets,
    title: target === 0 ? t("user.disableTitle") : t("user.enableTitle"),
    message:
      target === 0
        ? t("batch.confirmDisable", { n: targets.length })
        : t("batch.confirmEnable", { n: targets.length }),
    label: row => row.username,
    action: row => updateUserStatus(row.id, target),
    after: load
  });
}

async function load() {
  loading.value = true;
  try {
    const page = await listUsers({
      page: query.page,
      size: query.size,
      ...appliedQuery()
    });
    rows.value = page.items;
    total.value = Number(page.total);
    // One request per BD row; the table shows at once and the counts fill in as they arrive.
    void loadKolCounts(page.items);
  } catch (error) {
    notifyError(error);
  } finally {
    loading.value = false;
  }
}

function snapshotFilters(): void {
  applied.value = {
    username: query.username.trim(),
    role: query.role,
    status: query.status
  };
}

function search() {
  query.page = 1;
  snapshotFilters();
  void load();
}

function reset() {
  query.username = "";
  query.role = undefined;
  query.status = undefined;
  search();
}

function removeChip(key: string): void {
  if (key === "username") query.username = "";
  if (key === "role") query.role = undefined;
  if (key === "status") query.status = undefined;
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
  editingUser.value = null;
  formVisible.value = true;
}

function openEdit(row: UserVO) {
  editingUser.value = row;
  formVisible.value = true;
}

function openReset(row: UserVO) {
  resetUser.value = row;
  resetVisible.value = true;
}

function openPermissions(row: UserVO) {
  permissionsUser.value = row;
  permissionsVisible.value = true;
}

async function toggleStatus(row: UserVO) {
  const target = row.status === 1 ? 0 : 1;
  const disabling = target === 0;
  /** A BD with creators loses them to the company on disable (design §4.13.5). */
  const creators = disabling && isBdRow(row) ? bdCount(row) : undefined;
  const base = disabling
    ? t("user.confirmDisable", { username: row.username })
    : t("user.confirmEnable", { username: row.username });
  const message =
    creators && creators > 0 ? `${base}\n${t("user.disableBdEffect", { n: creators })}` : base;
  try {
    await ElMessageBox.confirm(
      message,
      disabling ? t("user.disableTitle") : t("user.enableTitle"),
      {
        type: "warning",
        ...(message.includes("\n") ? { customStyle: { whiteSpace: "pre-line" } } : {})
      }
    );
  } catch {
    return; // cancelled
  }
  try {
    await updateUserStatus(row.id, target);
    ElMessage.success(disabling ? t("user.disabled") : t("user.enabled"));
    await load();
  } catch (error) {
    notifyError(error);
  }
}

onMounted(load);
</script>

<template>
  <div class="list-page">
    <ListSearch :chips="chips" has-more @search="search" @reset="reset" @remove="removeChip">
      <template #primary>
        <el-form-item :label="t('common.username')">
          <el-input v-model="query.username" clearable maxlength="64" />
        </el-form-item>
      </template>
      <template #more>
        <el-form-item :label="t('user.role')">
          <el-select
            v-model="query.role"
            clearable
            :placeholder="t('common.all')"
            style="width: 140px"
          >
            <el-option
              v-for="role in SELECTABLE_ROLES"
              :key="role"
              :label="roleLabel(role)"
              :value="role"
            />
          </el-select>
        </el-form-item>
        <el-form-item :label="t('common.status')">
          <el-select
            v-model="query.status"
            clearable
            :placeholder="t('common.all')"
            style="width: 120px"
          >
            <el-option :label="t('common.enabled')" :value="1" />
            <el-option :label="t('common.disabled')" :value="0" />
          </el-select>
        </el-form-item>
      </template>
      <template #actions>
        <el-button type="success" @click="openCreate">{{ t("user.create") }}</el-button>
      </template>
    </ListSearch>

    <DataTable
      table-id="users"
      :rows="rows"
      :columns="columns"
      :loading="loading"
      :total="total"
      :page="query.page"
      :size="query.size"
      :filter-count="chips.length"
      :exportable="exportAllowed"
      :exporting="exporting"
      selectable
      :selectable-row="selectableUser"
      @export="exportCsv"
      @selection-change="selection = $event as UserVO[]"
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
      <template #cell-username="{ row }">
        <!-- At 1440 the column sits on its 130px minimum and long logins such as demo_bd_b_0d221
             wrapped to a second line, which made only those rows taller. Ellipsize instead. -->
        <span class="user-page__name" :title="asUser(row).username">
          {{ asUser(row).username }}
        </span>
        <el-tooltip
          v-if="isMultiCompany(asUser(row))"
          :content="t('user.multiCompanyHint')"
          placement="top"
        >
          <el-tag size="small" type="warning" class="multi-company" data-test="multi-company">
            {{ t("user.multiCompany") }}
          </el-tag>
        </el-tooltip>
      </template>
      <template #cell-role="{ row }">
        {{ asUser(row).role ? roleLabel(asUser(row).role) : "" }}
      </template>
      <template #cell-post="{ row }">
        {{ postLabel(asUser(row).postTemplate) }}
      </template>
      <template #cell-permissions="{ row }">
        <el-tooltip v-if="!isSuperAdminAccount(asUser(row))" placement="top">
          <template #content>
            <div
              v-for="entry in permissionEntries(asUser(row))"
              :key="entry.code"
              data-test="perm-name"
            >
              {{ entry.name }}
            </div>
          </template>
          <span data-test="perm-count">{{
            t("user.permissionItems", { n: asUser(row).permissions?.length ?? 0 })
          }}</span>
        </el-tooltip>
        <span v-else data-test="perm-count">{{ t("user.allPermissionsShort") }}</span>
      </template>
      <template #cell-dataScope="{ row }">
        {{ asUser(row).dataScope === "ALL" ? t("scope.allShort") : t("scope.ownShort") }}
      </template>
      <template #cell-status="{ row }">
        <el-tag :type="asUser(row).status === 1 ? 'success' : 'info'">
          {{ asUser(row).status === 1 ? t("common.enabled") : t("common.disabled") }}
        </el-tag>
      </template>
      <template #cell-lastLogin="{ row }">{{ formatInstant(asUser(row).lastLoginTime) }}</template>
      <template #cell-createdAt="{ row }">{{ formatInstant(asUser(row).createTime) }}</template>
      <template #actions="{ row }">
        <!-- One flex row: the default 12px button margins plus the wrapper margins below pushed the
             five actions of a BD account onto three lines, which made those rows 28px taller. -->
        <div class="actions-cell" data-test="row-actions">
          <el-button link type="primary" @click="openEdit(asUser(row))">{{
            t("common.edit")
          }}</el-button>
          <el-button
            v-if="isStaff(asUser(row))"
            link
            type="primary"
            data-test="permissions"
            @click="openPermissions(asUser(row))"
          >
            {{ t("user.permissionsTitle") }}
          </el-button>
          <el-tooltip
            v-if="isBdRow(asUser(row))"
            placement="top"
            :disabled="bdCount(asUser(row)) !== 0"
            :content="t('bdTransfer.noKols')"
          >
            <span class="user-page__transfer">
              <el-button
                link
                type="warning"
                data-test="transfer-kols"
                :disabled="bdCount(asUser(row)) === 0"
                @click="openTransfer(asUser(row))"
              >
                {{ t("bdTransfer.transferKols") }}
              </el-button>
            </span>
          </el-tooltip>
          <el-button
            link
            :type="asUser(row).status === 1 ? 'danger' : 'success'"
            :disabled="asUser(row).id === me.user?.id"
            @click="toggleStatus(asUser(row))"
          >
            {{ asUser(row).status === 1 ? t("common.disable") : t("common.enable") }}
          </el-button>
          <el-tooltip
            v-if="isMultiCompany(asUser(row))"
            :content="t('user.multiCompanyHint')"
            placement="top"
          >
            <!-- A disabled button swallows hover events; the wrapper keeps the hint reachable. -->
            <span class="reset-locked">
              <el-button link type="warning" disabled data-test="reset-password">
                {{ t("user.resetPassword") }}
              </el-button>
            </span>
          </el-tooltip>
          <el-button
            v-else
            link
            type="warning"
            data-test="reset-password"
            @click="openReset(asUser(row))"
          >
            {{ t("user.resetPassword") }}
          </el-button>
        </div>
      </template>
    </DataTable>

    <UserFormDialog v-model="formVisible" :user="editingUser" @saved="load" />
    <PermissionsDialog v-model="permissionsVisible" :user="permissionsUser" @saved="load" />
    <ResetPasswordDialog v-model="resetVisible" :user="resetUser" />
    <BdTransferDialog v-model="transferVisible" :target="transferTarget" @saved="load" />
  </div>
</template>

<style scoped>
.multi-company {
  margin-left: 6px;
}
.user-page__name {
  display: inline-block;
  max-width: 100%;
  overflow: hidden;
  vertical-align: bottom;
  text-overflow: ellipsis;
  white-space: nowrap;
}
/* Same row-action treatment as the creator list: tight gaps, no Element Plus button margins, so a
   BD row (five actions) keeps the same 49px height as the three-action super administrator row. */
.actions-cell {
  display: flex;
  flex-wrap: nowrap;
  align-items: center;
  gap: 0 var(--tcrm-space-2);
  white-space: nowrap;
}
.actions-cell .el-button {
  margin: 0;
  padding: 0;
  height: auto;
  line-height: var(--tcrm-line-height-body);
}
.reset-locked {
  display: inline-flex;
  margin-left: 0;
}
/* Element Plus tooltips ignore disabled buttons, so the button sits in a span. */
.user-page__transfer {
  display: inline-flex;
  margin-left: 0;
}
</style>
