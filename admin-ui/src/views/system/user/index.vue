<script setup lang="ts">
import { ElMessage, ElMessageBox } from "element-plus";
import { onMounted, reactive, ref } from "vue";
import { listUsers, updateUserStatus } from "@/api/system/user";
import type { Role, UserVO } from "@/api/types";
import { useUserStore } from "@/stores/user";
import { formatInstant } from "@/utils/format";
import { notifyError } from "@/utils/notify";
import { ROLE_LABELS, ROLES } from "@/utils/permission";
import ResetPasswordDialog from "./ResetPasswordDialog.vue";
import UserFormDialog from "./UserFormDialog.vue";

defineOptions({ name: "SystemUserPage" });

const me = useUserStore();
const loading = ref(false);
const rows = ref<UserVO[]>([]);
const total = ref(0);
const query = reactive<{
  page: number;
  size: number;
  username: string;
  role?: Role;
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
    total.value = page.total;
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
  const verb = target === 0 ? "停用" : "启用";
  try {
    await ElMessageBox.confirm(`确定要${verb}用户 ${row.username} 吗？`, `${verb}用户`, {
      type: "warning"
    });
  } catch {
    return; // cancelled
  }
  try {
    await updateUserStatus(row.id, target);
    ElMessage.success(`已${verb}`);
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
      <el-form-item label="用户名">
        <el-input v-model="query.username" clearable maxlength="64" @keyup.enter="search" />
      </el-form-item>
      <el-form-item label="角色">
        <el-select v-model="query.role" clearable placeholder="全部" style="width: 140px">
          <el-option v-for="role in ROLES" :key="role" :label="ROLE_LABELS[role]" :value="role" />
        </el-select>
      </el-form-item>
      <el-form-item label="状态">
        <el-select v-model="query.status" clearable placeholder="全部" style="width: 120px">
          <el-option label="启用" :value="1" />
          <el-option label="停用" :value="0" />
        </el-select>
      </el-form-item>
      <el-form-item>
        <el-button type="primary" @click="search">查询</el-button>
        <el-button @click="reset">重置</el-button>
        <el-button type="success" @click="openCreate">新增用户</el-button>
      </el-form-item>
    </el-form>

    <el-table v-loading="loading" :data="rows" row-key="id" border>
      <el-table-column prop="username" label="用户名" min-width="130" />
      <el-table-column prop="nickname" label="昵称" min-width="120" />
      <el-table-column label="角色" width="110">
        <template #default="{ row }">{{ ROLE_LABELS[(row as UserVO).role] }}</template>
      </el-table-column>
      <el-table-column label="数据范围" width="100">
        <template #default="{ row }">
          {{ (row as UserVO).dataScope === "ALL" ? "全部" : "仅本人" }}
        </template>
      </el-table-column>
      <el-table-column label="状态" width="80">
        <template #default="{ row }">
          <el-tag :type="(row as UserVO).status === 1 ? 'success' : 'info'">
            {{ (row as UserVO).status === 1 ? "启用" : "停用" }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="最近登录" width="170">
        <template #default="{ row }">{{ formatInstant((row as UserVO).lastLoginTime) }}</template>
      </el-table-column>
      <el-table-column label="创建时间" width="170">
        <template #default="{ row }">{{ formatInstant((row as UserVO).createTime) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="230" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="openEdit(row as UserVO)">编辑</el-button>
          <el-button
            link
            :type="(row as UserVO).status === 1 ? 'danger' : 'success'"
            :disabled="(row as UserVO).id === me.user?.id"
            @click="toggleStatus(row as UserVO)"
          >
            {{ (row as UserVO).status === 1 ? "停用" : "启用" }}
          </el-button>
          <el-button link type="warning" @click="openReset(row as UserVO)">重置密码</el-button>
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
