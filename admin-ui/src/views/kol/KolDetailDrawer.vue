<script setup lang="ts">
import { ref, watch } from "vue";
import { getKol } from "@/api/kol/kol";
import type { BdHistoryVO, KolDetailVO } from "@/api/types";
import { formatInstant } from "@/utils/format";
import { notifyError } from "@/utils/notify";
import { formatRate } from "@/utils/rate";

defineOptions({ name: "KolDetailDrawer" });

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

const bdName = (row: BdHistoryVO) => row.bdUserName ?? (row.bdUserId ? row.bdUserId : "未分配");
</script>

<template>
  <el-drawer
    :model-value="modelValue"
    title="达人详情"
    size="560px"
    @update:model-value="emit('update:modelValue', $event)"
  >
    <div v-loading="loading">
      <template v-if="detail">
        <el-descriptions :column="1" border>
          <el-descriptions-item label="达人编号">{{ detail.kolCode }}</el-descriptions-item>
          <el-descriptions-item label="姓名">{{ detail.name }}</el-descriptions-item>
          <el-descriptions-item label="邮箱">{{ detail.email }}</el-descriptions-item>
          <el-descriptions-item label="国家">{{ detail.country }}</el-descriptions-item>
          <el-descriptions-item label="负责商务">
            {{ detail.bdUserName ?? "未分配" }}
          </el-descriptions-item>
          <el-descriptions-item label="默认佣金比例">
            {{ formatRate(detail.defaultRate) }}
          </el-descriptions-item>
          <el-descriptions-item label="状态">
            {{ detail.status === 1 ? "启用" : "停用" }}
          </el-descriptions-item>
          <el-descriptions-item label="社媒账号">
            <div v-if="detail.socialAccounts.length === 0">-</div>
            <div v-for="(account, index) in detail.socialAccounts" :key="index">
              {{ account.platform }}：{{ account.handle }}
              <template v-if="account.followers">（粉丝 {{ account.followers }}）</template>
              <a v-if="account.url" :href="account.url" target="_blank" rel="noopener noreferrer">
                主页
              </a>
            </div>
          </el-descriptions-item>
          <el-descriptions-item label="备注">{{ detail.remark || "-" }}</el-descriptions-item>
          <el-descriptions-item label="创建时间">
            {{ formatInstant(detail.createTime) }}
          </el-descriptions-item>
        </el-descriptions>
        <h4 class="title">商务归属历史</h4>
        <el-table :data="detail.bdHistory" border size="small">
          <el-table-column label="商务" min-width="120">
            <template #default="{ row }">{{ bdName(row as BdHistoryVO) }}</template>
          </el-table-column>
          <el-table-column label="开始时间" width="170">
            <template #default="{ row }">
              {{ formatInstant((row as BdHistoryVO).startTime) }}
            </template>
          </el-table-column>
          <el-table-column label="结束时间" width="170">
            <template #default="{ row }">
              {{
                (row as BdHistoryVO).endTime ? formatInstant((row as BdHistoryVO).endTime) : "当前"
              }}
            </template>
          </el-table-column>
        </el-table>
      </template>
    </div>
  </el-drawer>
</template>

<style scoped>
.title {
  margin: 20px 0 8px;
}
</style>
