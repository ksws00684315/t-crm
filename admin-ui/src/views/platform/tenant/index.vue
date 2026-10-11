<script setup lang="ts">
import { ElMessage, ElMessageBox } from "element-plus";
import { onMounted, ref } from "vue";
import { listTenants, updateTenantStatus, type TenantVO } from "@/api/platform/tenant";
import { formatInstant } from "@/utils/format";
import { notifyError } from "@/utils/notify";
import TenantAdminsDialog from "./TenantAdminsDialog.vue";
import TenantFormDialog from "./TenantFormDialog.vue";

defineOptions({ name: "PlatformTenantPage" });

const loading = ref(false);
const rows = ref<TenantVO[]>([]);
const formVisible = ref(false);
const editingTenant = ref<TenantVO | null>(null);
const adminsVisible = ref(false);
const adminsTenant = ref<TenantVO | null>(null);

async function load() {
  loading.value = true;
  try {
    rows.value = await listTenants();
  } catch (error) {
    notifyError(error);
  } finally {
    loading.value = false;
  }
}

function openCreate() {
  editingTenant.value = null;
  formVisible.value = true;
}

function openEdit(row: TenantVO) {
  editingTenant.value = row;
  formVisible.value = true;
}

function openAdmins(row: TenantVO) {
  adminsTenant.value = row;
  adminsVisible.value = true;
}

async function toggleStatus(row: TenantVO) {
  const status = row.status === 1 ? 0 : 1;
  const action = status === 0 ? "停用" : "启用";
  const detail =
    status === 0
      ? "停用后，该公司所有管理员将无法登录，已登录的会在下一次操作时退出；中转链接不受影响。"
      : `确定启用公司 ${row.name} 吗？`;
  try {
    await ElMessageBox.confirm(detail, `${action}公司`, { type: "warning" });
  } catch {
    return;
  }
  try {
    await updateTenantStatus(row.id, status);
    ElMessage.success(`公司已${action}`);
    await load();
  } catch (error) {
    notifyError(error);
  }
}

onMounted(load);
</script>

<template>
  <el-card shadow="never">
    <div class="toolbar">
      <el-button type="primary" @click="openCreate">新建公司</el-button>
    </div>
    <el-table v-loading="loading" :data="rows" row-key="id" border>
      <el-table-column prop="tenantCode" label="公司编码" min-width="150" />
      <el-table-column prop="name" label="公司名称" min-width="160" />
      <el-table-column label="状态" width="100">
        <template #default="{ row }">
          <el-tag :type="(row as TenantVO).status === 1 ? 'success' : 'info'">
            {{ (row as TenantVO).status === 1 ? "启用" : "停用" }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="remark" label="备注" min-width="200" show-overflow-tooltip />
      <el-table-column label="创建时间" width="180">
        <template #default="{ row }">{{ formatInstant((row as TenantVO).createTime) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="290" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="openEdit(row as TenantVO)">编辑</el-button>
          <el-button link type="primary" @click="openAdmins(row as TenantVO)">
            超级管理员
          </el-button>
          <el-button
            link
            :type="(row as TenantVO).status === 1 ? 'danger' : 'success'"
            @click="toggleStatus(row as TenantVO)"
          >
            {{ (row as TenantVO).status === 1 ? "停用" : "启用" }}
          </el-button>
        </template>
      </el-table-column>
    </el-table>

    <TenantFormDialog v-model="formVisible" :tenant="editingTenant" @saved="load" />
    <TenantAdminsDialog v-model="adminsVisible" :tenant="adminsTenant" />
  </el-card>
</template>

<style scoped>
.toolbar {
  margin-bottom: 12px;
}
</style>
