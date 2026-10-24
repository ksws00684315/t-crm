<script setup lang="ts">
import { ElMessageBox } from "element-plus";
import { computed, onMounted, ref } from "vue";
import { useI18n } from "vue-i18n";
import CopyText from "@/components/CopyText.vue";
import DataTable, { type DataColumn } from "@/components/DataTable.vue";
import ListSearch, { type SearchChip } from "@/components/ListSearch.vue";
import RowActions, { type RowAction } from "@/components/RowActions.vue";
import { useLocaleStore } from "@/stores/locale";
import { loadSiteConfig } from "@/stores/site-config";
import { getSystemConfigs, type SystemConfigItem } from "@/api/system/site-config";
import { notifyError } from "@/utils/notify";
import ConfigEditDialog from "./ConfigEditDialog.vue";

defineOptions({ name: "PlatformSystemConfigPage" });

const { t } = useI18n();
const localeStore = useLocaleStore();

const items = ref<SystemConfigItem[]>([]);
const loading = ref(false);
const draftKeyword = ref("");
const appliedKeyword = ref("");
const editing = ref<SystemConfigItem | null>(null);
const dialogOpen = ref(false);

async function load() {
  loading.value = true;
  try {
    const groups = await getSystemConfigs();
    items.value = groups.flatMap(group => group.items);
  } catch (error) {
    notifyError(error);
  } finally {
    loading.value = false;
  }
}

/** Frontend filter over the full list; the ~30 keys never paginate. */
const filtered = computed(() => {
  const keyword = appliedKeyword.value.trim().toLowerCase();
  if (!keyword) return items.value;
  return items.value.filter(
    item =>
      item.key.toLowerCase().includes(keyword) || t(item.nameKey).toLowerCase().includes(keyword)
  );
});

const chips = computed<SearchChip[]>(() =>
  appliedKeyword.value
    ? [{ key: "keyword", label: t("common.keyword"), text: appliedKeyword.value }]
    : []
);

function search() {
  appliedKeyword.value = draftKeyword.value.trim();
}

function reset() {
  draftKeyword.value = "";
  appliedKeyword.value = "";
}

/** Editing url.admin touches the Weidian callback domain, so it confirms first (task 34). */
async function editItem(item: SystemConfigItem) {
  if (item.key === "url.admin") {
    try {
      await ElMessageBox.confirm(
        t("systemConfig.adminUrlWarning"),
        t("systemConfig.adminUrlTitle"),
        { type: "warning" }
      );
    } catch {
      return;
    }
  }
  editing.value = item;
  dialogOpen.value = true;
}

function rowActions(item: SystemConfigItem): RowAction[] {
  return [
    {
      key: "edit",
      label: t("common.edit"),
      testId: `config-edit-${item.key}`,
      onClick: () => void editItem(item)
    }
  ];
}

async function onSaved() {
  await Promise.all([load(), loadSiteConfig()]);
}

function asItem(row: object): SystemConfigItem {
  return row as SystemConfigItem;
}

const zhWidth = computed(() => localeStore.locale === "zh-CN");
const px = (zh: number, en: number): number => (zhWidth.value ? zh : en);

const columns = computed<DataColumn[]>(() => [
  { key: "key", label: t("systemConfig.key"), width: 320 },
  { key: "name", label: t("systemConfig.description"), minWidth: 180 },
  { key: "value", label: t("systemConfig.value"), minWidth: 220, tooltip: true },
  // English sizes to the "Actions" header so it never wraps (spec 10: header must fit).
  { key: "actions", label: t("common.actions"), width: px(56, 88) }
]);

onMounted(load);
</script>

<template>
  <div class="list-page" data-test="system-config-page">
    <el-alert
      class="config-notice"
      :title="t('systemConfig.notice')"
      type="info"
      :closable="false"
      show-icon
    />
    <ListSearch :chips="chips" @search="search" @reset="reset" @remove="reset">
      <template #primary>
        <el-form-item :label="t('common.keyword')">
          <el-input
            v-model="draftKeyword"
            clearable
            maxlength="100"
            :placeholder="t('systemConfig.keywordPlaceholder')"
            data-test="config-keyword"
            style="width: 220px"
          />
        </el-form-item>
      </template>
    </ListSearch>

    <DataTable
      table-id="platform-system-configs"
      row-key="key"
      :rows="filtered"
      :columns="columns"
      :loading="loading"
      :total="filtered.length"
      :paged="false"
    >
      <template #cell-key="{ row }">
        <span class="config-key"><CopyText :value="asItem(row).key" /></span>
      </template>
      <template #cell-name="{ row }">{{ t(asItem(row).nameKey) }}</template>
      <template #cell-value="{ row }">
        <span :data-test="`config-value-${asItem(row).key}`">{{ asItem(row).value || "—" }}</span>
      </template>
      <template #actions="{ row }">
        <RowActions :actions="rowActions(asItem(row))" />
      </template>
    </DataTable>

    <ConfigEditDialog v-model="dialogOpen" :item="editing" @saved="onSaved" />
  </div>
</template>

<style scoped>
/* The page notice is auxiliary text, not body copy. The #app prefix outspecifies the global
   `#app *` body-size rule that also carries !important. */
#app .config-notice :deep(.el-alert__title) {
  font-size: var(--tcrm-font-size-help) !important;
  line-height: var(--tcrm-line-height-help) !important;
  font-weight: 400;
}

/* Config keys are technical identifiers, not business data; the task asks for a monospace face. */
.config-key {
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
}
</style>
