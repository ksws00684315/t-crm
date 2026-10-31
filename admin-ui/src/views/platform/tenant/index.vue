<script setup lang="ts">
import { ElMessage, ElMessageBox } from "element-plus";
import { computed, onMounted, ref } from "vue";
import { useI18n } from "vue-i18n";
import { listTenants, updateTenantStatus, type TenantVO } from "@/api/platform/tenant";
import CopyText from "@/components/CopyText.vue";
import DataTable, { type DataColumn } from "@/components/DataTable.vue";
import ListSearch from "@/components/ListSearch.vue";
import RowActions, { type RowAction } from "@/components/RowActions.vue";
import { useLocaleStore } from "@/stores/locale";
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

const zhWidth = computed(() => useLocaleStore().locale === "zh-CN");
/** One width per language: the action labels differ in length (norm 10 §3). */
const px = (zh: number, en: number): number => (zhWidth.value ? zh : en);

const columns = computed<DataColumn[]>(() => [
  { key: "tenantCode", align: "left" as const, label: t("tenant.code"), minWidth: 150 },
  { key: "name", align: "left" as const, label: t("tenant.name"), prop: "name", minWidth: 160 },
  { key: "status", label: t("common.status"), width: 100 },
  {
    key: "remark",
    align: "left" as const,
    label: t("common.remark"),
    prop: "remark",
    minWidth: 200,
    tooltip: true
  },
  { key: "createdAt", label: t("common.createdAt"), width: 180 },
  // Edit / Admins / Disable via RowActions (R-10): zh canvas 84 / en 180 + gaps, padding, headroom.
  { key: "actions", label: t("common.actions"), width: px(128, 184) }
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

/** Row actions via RowActions (norm 11 §2, R-10): the status toggle flips type and label. */
function rowActions(row: TenantVO): RowAction[] {
  const enabled = row.status === 1;
  return [
    {
      key: "edit",
      label: t("common.edit"),
      testId: "tenant-edit",
      onClick: () => openEdit(row)
    },
    {
      key: "admins",
      label: t("tenant.admins"),
      testId: "tenant-admins",
      onClick: () => openAdmins(row)
    },
    {
      key: "status",
      label: enabled ? t("common.disable") : t("common.enable"),
      testId: "tenant-status",
      type: enabled ? "danger" : "success",
      onClick: () => void toggleStatus(row)
    }
  ];
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
        <RowActions :actions="rowActions(asTenant(row))" />
      </template>
    </DataTable>

    <TenantFormDialog v-model="formVisible" :tenant="editingTenant" @saved="load" />
    <TenantAdminsDialog v-model="adminsVisible" :tenant="adminsTenant" />
  </div>
</template>
