<script setup lang="ts">
import { ElMessage, ElMessageBox } from "element-plus";
import { onMounted, ref } from "vue";
import { useI18n } from "vue-i18n";
import { listTenants, updateTenantStatus, type TenantVO } from "@/api/platform/tenant";
import { formatInstant } from "@/utils/format";
import { notifyError } from "@/utils/notify";
import TenantAdminsDialog from "./TenantAdminsDialog.vue";
import TenantFormDialog from "./TenantFormDialog.vue";

defineOptions({ name: "PlatformTenantPage" });

const { t } = useI18n();
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
  const disabling = status === 0;
  try {
    await ElMessageBox.confirm(
      disabling ? t("tenant.confirmDisable") : t("tenant.confirmEnable", { name: row.name }),
      disabling ? t("tenant.disableTitle") : t("tenant.enableTitle"),
      { type: "warning" }
    );
  } catch {
    return;
  }
  try {
    await updateTenantStatus(row.id, status);
    ElMessage.success(disabling ? t("tenant.disabled") : t("tenant.enabled"));
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
      <el-button type="primary" @click="openCreate">{{ t("tenant.create") }}</el-button>
    </div>
    <el-table v-loading="loading" :data="rows" row-key="id" border>
      <el-table-column prop="tenantCode" :label="t('tenant.code')" min-width="150" />
      <el-table-column prop="name" :label="t('tenant.name')" min-width="160" />
      <el-table-column :label="t('common.status')" width="100">
        <template #default="{ row }">
          <el-tag :type="(row as TenantVO).status === 1 ? 'success' : 'info'">
            {{ (row as TenantVO).status === 1 ? t("common.enabled") : t("common.disabled") }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column
        prop="remark"
        :label="t('common.remark')"
        min-width="200"
        show-overflow-tooltip
      />
      <el-table-column :label="t('common.createdAt')" width="180">
        <template #default="{ row }">{{ formatInstant((row as TenantVO).createTime) }}</template>
      </el-table-column>
      <el-table-column :label="t('common.actions')" width="290" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="openEdit(row as TenantVO)">{{
            t("common.edit")
          }}</el-button>
          <el-button link type="primary" @click="openAdmins(row as TenantVO)">
            {{ t("tenant.admins") }}
          </el-button>
          <el-button
            link
            :type="(row as TenantVO).status === 1 ? 'danger' : 'success'"
            @click="toggleStatus(row as TenantVO)"
          >
            {{ (row as TenantVO).status === 1 ? t("common.disabled") : t("common.enabled") }}
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
