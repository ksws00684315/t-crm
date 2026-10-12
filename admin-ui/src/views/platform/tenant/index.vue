<script setup lang="ts">
import { ElMessage, ElMessageBox } from "element-plus";
import { computed, onMounted, ref } from "vue";
import { useI18n } from "vue-i18n";
import { listTenants, updateTenantStatus, type TenantVO } from "@/api/platform/tenant";
import CopyText from "@/components/CopyText.vue";
import DataTable, { type DataColumn } from "@/components/DataTable.vue";
import ListSearch from "@/components/ListSearch.vue";
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

function asTenant(row: object): TenantVO {
  return row as TenantVO;
}

const columns = computed<DataColumn[]>(() => [
  { key: "tenantCode", label: t("tenant.code"), minWidth: 150, mono: true },
  { key: "name", label: t("tenant.name"), prop: "name", minWidth: 160 },
  { key: "status", label: t("common.status"), width: 100 },
  { key: "remark", label: t("common.remark"), prop: "remark", minWidth: 200, tooltip: true },
  { key: "createdAt", label: t("common.createdAt"), width: 180, mono: true },
  { key: "actions", label: t("common.actions"), width: 290 }
]);

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
  <div class="list-page">
    <ListSearch @search="load" @reset="load">
      <template #actions>
        <el-button type="primary" @click="openCreate">{{ t("tenant.create") }}</el-button>
      </template>
    </ListSearch>
    <DataTable
      table-id="tenants"
      :rows="rows"
      :columns="columns"
      :loading="loading"
      :total="rows.length"
      :paged="false"
      @refresh="load"
    >
      <template #cell-tenantCode="{ row }"><CopyText :value="asTenant(row).tenantCode" /></template>
      <template #cell-status="{ row }">
        <el-tag :type="asTenant(row).status === 1 ? 'success' : 'info'">
          {{ asTenant(row).status === 1 ? t("common.enabled") : t("common.disabled") }}
        </el-tag>
      </template>
      <template #cell-createdAt="{ row }">{{ formatInstant(asTenant(row).createTime) }}</template>
      <template #actions="{ row }">
        <el-button link type="primary" @click="openEdit(asTenant(row))">{{
          t("common.edit")
        }}</el-button>
        <el-button link type="primary" @click="openAdmins(asTenant(row))">
          {{ t("tenant.admins") }}
        </el-button>
        <el-button
          link
          :type="asTenant(row).status === 1 ? 'danger' : 'success'"
          @click="toggleStatus(asTenant(row))"
        >
          {{ asTenant(row).status === 1 ? t("common.disabled") : t("common.enabled") }}
        </el-button>
      </template>
    </DataTable>

    <TenantFormDialog v-model="formVisible" :tenant="editingTenant" @saved="load" />
    <TenantAdminsDialog v-model="adminsVisible" :tenant="adminsTenant" />
  </div>
</template>
