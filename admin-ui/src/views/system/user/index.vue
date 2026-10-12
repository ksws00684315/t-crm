<script setup lang="ts">
import { ElMessage, ElMessageBox } from "element-plus";
import { computed, onMounted, reactive, ref } from "vue";
import { useI18n } from "vue-i18n";
import { listUsers, updateUserStatus } from "@/api/system/user";
import type { CompanyRole, UserVO } from "@/api/types";
import DataTable, { type DataColumn } from "@/components/DataTable.vue";
import ListSearch, { type SearchChip } from "@/components/ListSearch.vue";
import { useUserStore } from "@/stores/user";
import { formatInstant } from "@/utils/format";
import { notifyError } from "@/utils/notify";
import { roleLabel, SELECTABLE_ROLES } from "@/utils/permission";
import { readPageSize } from "@/utils/table-prefs";
import ResetPasswordDialog from "./ResetPasswordDialog.vue";
import UserFormDialog from "./UserFormDialog.vue";

defineOptions({ name: "SystemUserPage" });

const { t } = useI18n();
const me = useUserStore();
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
  { key: "role", label: t("user.role"), width: 110 },
  { key: "dataScope", label: t("user.dataScope"), width: 100 },
  { key: "status", label: t("common.status"), width: 80 },
  { key: "lastLogin", label: t("user.lastLogin"), width: 170, mono: true },
  { key: "createdAt", label: t("common.createdAt"), width: 170, mono: true },
  { key: "actions", label: t("common.actions"), width: 230 }
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

async function load() {
  loading.value = true;
  try {
    const page = await listUsers({
      page: query.page,
      size: query.size,
      username: applied.value.username || undefined,
      role: applied.value.role,
      status: applied.value.status
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

async function toggleStatus(row: UserVO) {
  const target = row.status === 1 ? 0 : 1;
  const disabling = target === 0;
  try {
    await ElMessageBox.confirm(
      disabling
        ? t("user.confirmDisable", { username: row.username })
        : t("user.confirmEnable", { username: row.username }),
      disabling ? t("user.disableTitle") : t("user.enableTitle"),
      { type: "warning" }
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
      @update:page="onPage"
      @update:size="onSize"
      @refresh="load"
      @clear-filters="reset"
    >
      <template #cell-role="{ row }">
        {{ asUser(row).role ? roleLabel(asUser(row).role) : "" }}
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
        <el-button link type="primary" @click="openEdit(asUser(row))">{{
          t("common.edit")
        }}</el-button>
        <el-button
          link
          :type="asUser(row).status === 1 ? 'danger' : 'success'"
          :disabled="asUser(row).id === me.user?.id"
          @click="toggleStatus(asUser(row))"
        >
          {{ asUser(row).status === 1 ? t("common.disabled") : t("common.enabled") }}
        </el-button>
        <el-button link type="warning" @click="openReset(asUser(row))">
          {{ t("user.resetPassword") }}
        </el-button>
      </template>
    </DataTable>

    <UserFormDialog v-model="formVisible" :user="editingUser" @saved="load" />
    <ResetPasswordDialog v-model="resetVisible" :user="resetUser" />
  </div>
</template>
