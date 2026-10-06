<script setup lang="ts">
import { ElMessage } from "element-plus";
import { reactive, ref, watch } from "vue";
import { listLinks } from "@/api/link/link";
import type { LinkUnavailableReason, LinkVO } from "@/api/types";
import { notifyError } from "@/utils/notify";

defineOptions({ name: "KolLinksDrawer" });

const props = defineProps<{ modelValue: boolean; kolCode: string | null; kolName?: string }>();
const emit = defineEmits<{ (event: "update:modelValue", value: boolean): void }>();

const REASONS: Readonly<Record<LinkUnavailableReason, string>> = {
  KOL_DISABLED: "达人停用",
  PRODUCT_OFF_SHELF: "商品下架",
  SHOP_DISABLED: "店铺停用"
};

const loading = ref(false);
const rows = ref<LinkVO[]>([]);
const total = ref(0);
const query = reactive({ page: 1, size: 20, productCode: "" });

async function load() {
  if (!props.kolCode) return;
  loading.value = true;
  try {
    const page = await listLinks({
      kolCode: props.kolCode,
      productCode: query.productCode.trim() || undefined,
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

function search() {
  query.page = 1;
  void load();
}

function reset() {
  query.productCode = "";
  search();
}

watch(
  () => [props.modelValue, props.kolCode] as const,
  ([open, code]) => {
    if (!open || !code) return;
    rows.value = [];
    total.value = 0;
    query.productCode = "";
    search();
  }
);

function reasonText(row: LinkVO): string {
  return row.unavailableReasons.map(reason => REASONS[reason] ?? reason).join("、");
}

async function copy(row: LinkVO) {
  try {
    await navigator.clipboard.writeText(row.linkUrl);
    ElMessage.success("已复制链接");
  } catch {
    ElMessage.error("复制失败，请手动选择链接复制");
  }
}
</script>

<template>
  <el-drawer
    :model-value="modelValue"
    :title="`中转链接 - ${kolCode ?? ''}${kolName ? `（${kolName}）` : ''}`"
    size="960px"
    @update:model-value="emit('update:modelValue', $event)"
  >
    <el-form :inline="true" :model="query" @submit.prevent>
      <el-form-item label="原商品编码">
        <el-input
          v-model="query.productCode"
          clearable
          maxlength="32"
          placeholder="精确匹配"
          data-test="link-product-code"
          @keyup.enter="search"
        />
      </el-form-item>
      <el-form-item>
        <el-button type="primary" data-test="link-search" @click="search">查询</el-button>
        <el-button @click="reset">重置</el-button>
      </el-form-item>
    </el-form>

    <el-table v-loading="loading" :data="rows" row-key="kolItemId" border>
      <el-table-column prop="productCode" label="原商品编码" width="120" />
      <el-table-column prop="productName" label="名称" min-width="120" />
      <el-table-column prop="weidianItemId" label="专属商品 ID" width="130" />
      <el-table-column label="中转链接" min-width="260">
        <template #default="{ row }">
          <span class="link">{{ (row as LinkVO).linkUrl }}</span>
          <el-button link type="primary" data-test="copy" @click="copy(row as LinkVO)">
            复制
          </el-button>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="150">
        <template #default="{ row }">
          <el-tag v-if="(row as LinkVO).available" type="success" data-test="status">可用</el-tag>
          <el-tooltip v-else :content="reasonText(row as LinkVO)" placement="top">
            <el-tag type="danger" data-test="status"
              >不可用：{{ reasonText(row as LinkVO) }}</el-tag
            >
          </el-tooltip>
        </template>
      </el-table-column>
      <el-table-column label="近 30 天点击（全部 / 有效）" width="130">
        <template #default="{ row }">
          {{ (row as LinkVO).clicks30d }} / {{ (row as LinkVO).validClicks30d }}
        </template>
      </el-table-column>
    </el-table>

    <el-pagination
      v-model:current-page="query.page"
      v-model:page-size="query.size"
      class="pager"
      background
      layout="total, sizes, prev, pager, next"
      :page-sizes="[10, 20, 50, 100]"
      :total="total"
      @current-change="load"
      @size-change="search"
    />
  </el-drawer>
</template>

<style scoped>
.link {
  margin-right: 8px;
  word-break: break-all;
}
.pager {
  margin-top: 16px;
  justify-content: flex-end;
}
</style>
