<script setup lang="ts">
import { ElMessage, ElMessageBox } from "element-plus";
import { computed, onMounted, ref } from "vue";
import { useI18n } from "vue-i18n";
import { listLinkTemplates, updateLinkTemplateStatus } from "@/api/link/template";
import type { LinkTemplateVO } from "@/api/types";
import DataTable, { type DataColumn } from "@/components/DataTable.vue";
import ListSearch from "@/components/ListSearch.vue";
import { notifyError } from "@/utils/notify";
import { formatInstantToMinute } from "@/utils/format";
import LinkTemplateFormDialog from "./LinkTemplateFormDialog.vue";

defineOptions({ name: "SystemLinkTemplatePage" });

const { t } = useI18n();
const loading = ref(false);
const rows = ref<LinkTemplateVO[]>([]);
const query = ref("");
const appliedQuery = ref("");
const formVisible = ref(false);
const editing = ref<LinkTemplateVO | null>(null);

function asTemplate(row: object): LinkTemplateVO {
  return row as LinkTemplateVO;
}

const columns = computed<DataColumn[]>(() => [
  {
    key: "templateCode",
    label: t("linkTemplate.code"),
    prop: "templateCode",
    minWidth: 160
  },
  {
    key: "templateUrl",
    label: t("linkTemplate.url"),
    prop: "templateUrl",
    minWidth: 280,
    tooltip: true
  },
  { key: "createTime", label: t("linkTemplate.createTime"), width: 180 },
  { key: "status", label: t("common.status"), width: 90 },
  { key: "actions", label: t("common.actions"), width: 160 }
]);

const visibleRows = computed(() => {
  const term = appliedQuery.value.toLocaleLowerCase();
  return term
    ? rows.value.filter(row => row.templateCode.toLocaleLowerCase().includes(term))
    : rows.value;
});

function search() {
  appliedQuery.value = query.value.trim();
}

function reset() {
  query.value = "";
  appliedQuery.value = "";
}

async function load() {
  loading.value = true;
  try {
    rows.value = await listLinkTemplates();
  } catch (error) {
    notifyError(error);
  } finally {
    loading.value = false;
  }
}

function openCreate() {
  editing.value = null;
  formVisible.value = true;
}

function openEdit(row: LinkTemplateVO) {
  editing.value = row;
  formVisible.value = true;
}

async function toggleStatus(row: LinkTemplateVO) {
  const target = row.status === 1 ? 0 : 1;
  const disabling = target === 0;
  try {
    await ElMessageBox.confirm(
      disabling
        ? t("linkTemplate.confirmDisable")
        : t("linkTemplate.confirmEnable", { name: row.templateCode }),
      disabling ? t("linkTemplate.disableTitle") : t("linkTemplate.enableTitle"),
      { type: "warning" }
    );
  } catch {
    return;
  }
  try {
    await updateLinkTemplateStatus(row.id, target);
    ElMessage.success(disabling ? t("linkTemplate.disabled") : t("linkTemplate.enabled"));
    await load();
  } catch (error) {
    notifyError(error);
  }
}

onMounted(() => void load());
</script>

<template>
  <div class="list-page">
    <ListSearch @search="search" @reset="reset">
      <template #primary>
        <el-form-item :label="t('linkTemplate.code')">
          <el-input v-model="query" clearable :placeholder="t('linkTemplate.searchPlaceholder')" />
        </el-form-item>
      </template>
      <template #actions>
        <el-button type="primary" data-test="create" @click="openCreate">
          {{ t("linkTemplate.create") }}
        </el-button>
      </template>
    </ListSearch>

    <DataTable
      table-id="link-templates"
      :rows="visibleRows"
      :columns="columns"
      :loading="loading"
      :total="visibleRows.length"
      :paged="false"
      @refresh="load"
    >
      <template #cell-createTime="{ row }">{{
        formatInstantToMinute(asTemplate(row).createTime)
      }}</template>
      <template #cell-status="{ row }">
        <el-tag :type="asTemplate(row).status === 1 ? 'success' : 'info'">
          {{ asTemplate(row).status === 1 ? t("common.enabled") : t("common.disabled") }}
        </el-tag>
      </template>
      <template #actions="{ row }">
        <el-button link type="primary" data-test="edit" @click="openEdit(asTemplate(row))">
          {{ t("common.edit") }}
        </el-button>
        <el-button
          link
          :type="asTemplate(row).status === 1 ? 'danger' : 'success'"
          data-test="status"
          @click="toggleStatus(asTemplate(row))"
        >
          {{ asTemplate(row).status === 1 ? t("common.disable") : t("common.enable") }}
        </el-button>
      </template>
    </DataTable>

    <LinkTemplateFormDialog v-model="formVisible" :template="editing" @saved="load" />
  </div>
</template>
