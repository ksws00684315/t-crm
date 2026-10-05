<script setup lang="ts">
import { onMounted, reactive, ref } from "vue";
import { listOperLogs, type OperLogVO } from "@/api/system/oper-log";
import { formatInstant } from "@/utils/format";
import { notifyError } from "@/utils/notify";

defineOptions({ name: "SystemOperLogPage" });

const loading = ref(false);
const rows = ref<OperLogVO[]>([]);
const total = ref(0);
const paging = reactive({ page: 1, size: 20 });

async function load() {
  loading.value = true;
  try {
    const page = await listOperLogs(paging.page, paging.size);
    rows.value = page.items;
    total.value = Number(page.total);
  } catch (error) {
    notifyError(error);
  } finally {
    loading.value = false;
  }
}

function onSizeChange() {
  paging.page = 1;
  void load();
}

onMounted(load);
</script>

<template>
  <el-card shadow="never">
    <el-table v-loading="loading" :data="rows" row-key="id" border>
      <el-table-column label="时间" width="170">
        <template #default="{ row }">{{ formatInstant((row as OperLogVO).operTime) }}</template>
      </el-table-column>
      <el-table-column prop="username" label="操作人" width="130" />
      <el-table-column prop="module" label="模块" width="100" />
      <el-table-column prop="action" label="操作" min-width="140" />
      <el-table-column prop="result" label="结果" width="100" />
      <el-table-column prop="requestUri" label="请求地址" min-width="200" show-overflow-tooltip />
      <el-table-column prop="ip" label="IP" width="140" />
      <el-table-column prop="costMs" label="耗时(ms)" width="100" />
    </el-table>

    <el-pagination
      v-model:current-page="paging.page"
      v-model:page-size="paging.size"
      class="pager"
      background
      layout="total, sizes, prev, pager, next"
      :page-sizes="[10, 20, 50, 100]"
      :total="total"
      @current-change="load"
      @size-change="onSizeChange"
    />
  </el-card>
</template>

<style scoped>
.pager {
  margin-top: 16px;
  justify-content: flex-end;
}
</style>
