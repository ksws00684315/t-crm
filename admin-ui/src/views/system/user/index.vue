<script setup lang="ts">
import { ElMessage, ElMessageBox } from "element-plus";
import { onMounted, reactive, ref } from "vue";
import { useI18n } from "vue-i18n";
import { listUsers, updateUserStatus } from "@/api/system/user";
import type { CompanyRole, UserVO } from "@/api/types";
import { useUserStore } from "@/stores/user";
import { formatInstant } from "@/utils/format";
import { notifyError } from "@/utils/notify";
import { roleLabel, SELECTABLE_ROLES } from "@/utils/permission";
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
  size: 20,
  username: ""
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
      username: query.username.trim() || undefined,
      role: query.role,
      status: query.status
    });
    rows.value = page.items;
    total.value = Number(page.total);
  } catch (error) {
    notifyError(error);
  } finally {
    loading.value = false;
  }
}

function search() {
  query.page = 1;
  void load();
}

function reset() {
  query.username = "";
  query.role = undefined;
  query.status = undefined;
  search();
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
  <el-card shadow="never">
    <el-form :inline="true" :model="query" @submit.prevent>
      <el-form-item :label="t('common.username')">
        <el-input v-model="query.username" clearable maxlength="64" @keyup.enter="search" />
      </el-form-item>
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
      <el-form-item>
        <el-button type="primary" @click="search">{{ t("common.search") }}</el-button>
        <el-button @click="reset">{{ t("common.reset") }}</el-button>
        <el-button type="success" @click="openCreate">{{ t("user.create") }}</el-button>
      </el-form-item>
    </el-form>

    <el-table v-loading="loading" :data="rows" row-key="id" border>
      <el-table-column prop="username" :label="t('common.username')" min-width="130" />
      <el-table-column prop="nickname" :label="t('common.nickname')" min-width="120" />
      <el-table-column :label="t('user.role')" width="110">
        <template #default="{ row }">
          {{ (row as UserVO).role ? roleLabel((row as UserVO).role) : "" }}
        </template>
      </el-table-column>
      <el-table-column :label="t('user.dataScope')" width="100">
        <template #default="{ row }">
          {{ (row as UserVO).dataScope === "ALL" ? t("scope.allShort") : t("scope.ownShort") }}
        </template>
      </el-table-column>
      <el-table-column :label="t('common.status')" width="80">
        <template #default="{ row }">
          <el-tag :type="(row as UserVO).status === 1 ? 'success' : 'info'">
            {{ (row as UserVO).status === 1 ? t("common.enabled") : t("common.disabled") }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column :label="t('user.lastLogin')" width="170">
        <template #default="{ row }">{{ formatInstant((row as UserVO).lastLoginTime) }}</template>
      </el-table-column>
      <el-table-column :label="t('common.createdAt')" width="170">
        <template #default="{ row }">{{ formatInstant((row as UserVO).createTime) }}</template>
      </el-table-column>
      <el-table-column :label="t('common.actions')" width="230" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="openEdit(row as UserVO)">{{
            t("common.edit")
          }}</el-button>
          <el-button
            link
            :type="(row as UserVO).status === 1 ? 'danger' : 'success'"
            :disabled="(row as UserVO).id === me.user?.id"
            @click="toggleStatus(row as UserVO)"
          >
            {{ (row as UserVO).status === 1 ? t("common.disabled") : t("common.enabled") }}
          </el-button>
          <el-button link type="warning" @click="openReset(row as UserVO)">
            {{ t("user.resetPassword") }}
          </el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-pagination
      v-model:current-page="query.page"
      v-model:page-size="query.size"
      class="pager"
      background
      layout="total, sizes, prev, pager, next"
      :page-sizes="[10, 20, 50, 100]"
      :total="total"
      @current-change="load"
      @size-change="search"
    />

    <UserFormDialog v-model="formVisible" :user="editingUser" @saved="load" />
    <ResetPasswordDialog v-model="resetVisible" :user="resetUser" />
  </el-card>
</template>

<style scoped>
.pager {
  margin-top: 16px;
  justify-content: flex-end;
}
</style>
