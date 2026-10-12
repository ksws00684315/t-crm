<script setup lang="ts">
import { computed, ref, watch } from "vue";
import { useI18n } from "vue-i18n";
import { getKol } from "@/api/kol/kol";
import type { BdHistoryVO, KolDetailVO } from "@/api/types";
import CopyText from "@/components/CopyText.vue";
import DataTable, { type DataColumn } from "@/components/DataTable.vue";
import { formatInstant } from "@/utils/format";
import { notifyError } from "@/utils/notify";
import { formatRate } from "@/utils/rate";

defineOptions({ name: "KolDetailDrawer" });

const { t } = useI18n();

const props = defineProps<{ modelValue: boolean; kolId: string | null }>();
const emit = defineEmits<{ (event: "update:modelValue", value: boolean): void }>();

const loading = ref(false);
const detail = ref<KolDetailVO | null>(null);

watch(
  () => [props.modelValue, props.kolId] as const,
  async ([open, id]) => {
    if (!open || !id) return;
    loading.value = true;
    detail.value = null;
    try {
      detail.value = await getKol(id);
    } catch (error) {
      notifyError(error);
      emit("update:modelValue", false);
    } finally {
      loading.value = false;
    }
  }
);

function bdName(row: BdHistoryVO): string {
  return row.bdUserName ?? (row.bdUserId ? row.bdUserId : t("common.unassigned"));
}

function asHistory(row: object): BdHistoryVO {
  return row as BdHistoryVO;
}

const historyColumns = computed<DataColumn[]>(() => [
  { key: "bd", label: t("kol.bdColumn"), minWidth: 120 },
  { key: "startedAt", label: t("kol.startedAt"), width: 170, mono: true },
  { key: "endedAt", label: t("kol.endedAt"), width: 170, mono: true }
]);
</script>

<template>
  <el-drawer
    :model-value="modelValue"
    :title="t('kol.detailTitle')"
    size="560px"
    @update:model-value="emit('update:modelValue', $event)"
  >
    <div v-loading="loading">
      <template v-if="detail">
        <el-descriptions :column="1" border>
          <el-descriptions-item :label="t('kol.code')">
            <CopyText :value="detail.kolCode" />
          </el-descriptions-item>
          <el-descriptions-item :label="t('common.name')">{{ detail.name }}</el-descriptions-item>
          <el-descriptions-item :label="t('common.email')">
            <CopyText :value="detail.email" />
          </el-descriptions-item>
          <el-descriptions-item :label="t('common.country')">{{
            detail.country
          }}</el-descriptions-item>
          <el-descriptions-item :label="t('kol.bd')">
            {{ detail.bdUserName ?? t("common.unassigned") }}
          </el-descriptions-item>
          <el-descriptions-item :label="t('kol.rateLabel')">
            {{ formatRate(detail.defaultRate) }}
          </el-descriptions-item>
          <el-descriptions-item :label="t('common.status')">
            {{ detail.status === 1 ? t("common.enabled") : t("common.disabled") }}
          </el-descriptions-item>
          <el-descriptions-item :label="t('kol.social')">
            <div v-if="detail.socialAccounts.length === 0">-</div>
            <div v-for="(account, index) in detail.socialAccounts" :key="index">
              {{ account.platform }}: {{ account.handle }}
              <template v-if="account.followers">{{
                t("kol.followers", { count: account.followers })
              }}</template>
              <a v-if="account.url" :href="account.url" target="_blank" rel="noopener noreferrer">
                {{ t("kol.homepage") }}
              </a>
              <CopyText v-if="account.url" :value="account.url" />
            </div>
          </el-descriptions-item>
          <el-descriptions-item :label="t('common.remark')">{{
            detail.remark || "-"
          }}</el-descriptions-item>
          <el-descriptions-item :label="t('common.createdAt')">
            {{ formatInstant(detail.createTime) }}
          </el-descriptions-item>
        </el-descriptions>
        <h4 class="title">{{ t("kol.bdHistory") }}</h4>
        <DataTable
          plain
          table-id="kol-bd-history"
          :rows="detail.bdHistory"
          :columns="historyColumns"
          :total="detail.bdHistory.length"
          :paged="false"
        >
          <template #cell-bd="{ row }">{{ bdName(asHistory(row)) }}</template>
          <template #cell-startedAt="{ row }">{{
            formatInstant(asHistory(row).startTime)
          }}</template>
          <template #cell-endedAt="{ row }">
            {{ asHistory(row).endTime ? formatInstant(asHistory(row).endTime) : t("kol.current") }}
          </template>
        </DataTable>
      </template>
    </div>
  </el-drawer>
</template>

<style scoped>
.title {
  margin: 20px 0 8px;
}
</style>
