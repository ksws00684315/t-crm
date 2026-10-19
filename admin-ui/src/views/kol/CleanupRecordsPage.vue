<script setup lang="ts">
import { ElMessage } from "element-plus";
import { computed, onMounted, reactive, ref } from "vue";
import { useI18n } from "vue-i18n";
import { listCleanupItems, retryCleanupItem, type CleanupItemVO } from "@/api/kol/promotion";
import DataTable, { type DataColumn } from "@/components/DataTable.vue";
import ListSearch from "@/components/ListSearch.vue";
import { formatInstant } from "@/utils/format";
import { notifyError } from "@/utils/notify";

defineOptions({ name: "CleanupRecordsPage" });

const props = defineProps<{ canWrite: boolean }>();

const { t } = useI18n();
const loading = ref(false);
const rows = ref<CleanupItemVO[]>([]);
const total = ref(0);
const retrying = ref<string | null>(null);
const query = reactive<{
  kolCode: string;
  status?: CleanupItemVO["status"];
  page: number;
  size: number;
}>({ kolCode: "", page: 1, size: 20 });
const columns = computed<DataColumn[]>(() => [
  { key: "kolCode", label: t("kol.code"), width: 150, mono: true },
  { key: "shop", label: t("cleanup.shop"), width: 100 },
  { key: "item", label: t("cleanup.weidianItemId"), width: 140, mono: true },
  { key: "status", label: t("common.status"), width: 100 },
  { key: "trigger", label: t("cleanup.trigger"), width: 100 },
  { key: "reason", label: t("cleanup.reason"), minWidth: 140, tooltip: true },
  { key: "failure", label: t("cleanup.failReason"), minWidth: 180, tooltip: true },
  { key: "cleanupTime", label: t("cleanup.cleanupTime"), width: 170, mono: true },
  { key: "finishTime", label: t("cleanup.finishTime"), width: 170, mono: true },
  { key: "actions", label: t("common.actions"), width: 100 }
]);

function row(item: object): CleanupItemVO {
  return item as CleanupItemVO;
}

function statusText(status?: CleanupItemVO["status"]): string {
  return status ? t(`cleanup.status.${status}`) : "-";
}

function triggerText(trigger?: CleanupItemVO["trigger"]): string {
  return trigger ? t(`cleanup.triggerOption.${trigger}`) : "-";
}

async function load() {
  loading.value = true;
  try {
    const page = await listCleanupItems({
      kolCode: query.kolCode.trim() || undefined,
      status: query.status,
      page: query.page,
      size: query.size
    });
    rows.value = page.items;
    total.value = Number(page.total);
  } catch (error) {
    notifyError(error);
  } finally {
    loading.value = false;
  }
}

async function retry(item: CleanupItemVO) {
  retrying.value = item.id;
  try {
    await retryCleanupItem(item.id);
    ElMessage.success(t("cleanup.retryStarted"));
    await load();
  } catch (error) {
    notifyError(error);
  } finally {
    retrying.value = null;
  }
}

function search() {
  query.page = 1;
  void load();
}

function reset() {
  query.kolCode = "";
  query.status = undefined;
  search();
}

function changePage(page: number) {
  query.page = page;
  void load();
}

function changeSize(size: number) {
  query.size = size;
  query.page = 1;
  void load();
}

onMounted(() => void load());
</script>

<template>
  <section class="cleanup-records-page">
    <ListSearch :chips="[]" @search="search" @reset="reset">
      <template #primary>
        <el-form-item :label="t('kol.code')">
          <el-input v-model="query.kolCode" clearable data-test="cleanup-code" />
        </el-form-item>
        <el-form-item :label="t('common.status')">
          <el-select v-model="query.status" clearable :placeholder="t('common.all')">
            <el-option
              v-for="status in ['PENDING', 'SUCCESS', 'FAILED']"
              :key="status"
              :label="t(`cleanup.status.${status}`)"
              :value="status"
            />
          </el-select>
        </el-form-item>
      </template>
    </ListSearch>
    <DataTable
      plain
      table-id="kol-cleanup-items"
      :rows="rows"
      :columns="columns"
      :loading="loading"
      :total="total"
      :page="query.page"
      :size="query.size"
      @update:page="changePage"
      @update:size="changeSize"
      @refresh="load"
    >
      <template #cell-shop="{ row: item }">{{ row(item).shopName || row(item).shopId }}</template>
      <template #cell-item="{ row: item }">{{ row(item).weidianItemId }}</template>
      <template #cell-status="{ row: item }">
        <el-tag
          :type="
            row(item).status === 'SUCCESS'
              ? 'success'
              : row(item).status === 'FAILED'
                ? 'danger'
                : 'warning'
          "
          >{{ statusText(row(item).status) }}</el-tag
        >
      </template>
      <template #cell-trigger="{ row: item }">
        {{ triggerText(row(item).trigger) }}
      </template>
      <template #cell-reason="{ row: item }">{{ row(item).reason || "-" }}</template>
      <template #cell-failure="{ row: item }">{{ row(item).failReason || "-" }}</template>
      <template #cell-cleanupTime="{ row: item }">{{
        formatInstant(row(item).cleanupTime)
      }}</template>
      <template #cell-finishTime="{ row: item }">
        {{ row(item).finishTime ? formatInstant(row(item).finishTime!) : "-" }}
      </template>
      <template #actions="{ row: item }">
        <el-button
          v-if="props.canWrite && row(item).status === 'FAILED'"
          link
          type="primary"
          data-test="cleanup-retry"
          :loading="retrying === row(item).id"
          @click="retry(row(item))"
        >
          {{ t("cleanup.retry") }}
        </el-button>
      </template>
    </DataTable>
  </section>
</template>
