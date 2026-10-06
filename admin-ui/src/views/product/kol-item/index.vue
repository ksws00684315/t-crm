<script setup lang="ts">
import { computed, onMounted, reactive, ref } from "vue";
import { ElMessage, ElMessageBox } from "element-plus";
import { rebuildLinkIndex } from "@/api/link/link";
import { downloadKolItemTemplate, listKolItems, uploadKolItemImport } from "@/api/product/kol-item";
import { listShops } from "@/api/product/shop";
import type { KolItemVO, ShopVO } from "@/api/types";
import ImportDialog from "@/components/ImportDialog.vue";
import { useUserStore } from "@/stores/user";
import { formatInstant } from "@/utils/format";
import { notifyError } from "@/utils/notify";
import { linkAbilities, productAbilities } from "@/utils/permission";

defineOptions({ name: "KolItemListPage" });

const SEARCH_TIP = "请输入达人编号、原商品编码或专属商品 ID 查询";

const me = useUserStore();
const abilities = computed(() => productAbilities(me.user));
const links = computed(() => linkAbilities(me.user));
const rebuilding = ref(false);

const loading = ref(false);
/** False until the user runs a search with at least one precision filter. */
const hasQueried = ref(false);
const rows = ref<KolItemVO[]>([]);
const total = ref(0);
const shops = ref<ShopVO[]>([]);
const query = reactive<{
  page: number;
  size: number;
  kolCode: string;
  productCode: string;
  shopId?: string;
  weidianItemId: string;
  includeHistory: boolean;
}>({
  page: 1,
  size: 20,
  kolCode: "",
  productCode: "",
  weidianItemId: "",
  includeHistory: false
});
const importVisible = ref(false);

function hasPrecisionFilter(): boolean {
  return (
    query.kolCode.trim() !== "" ||
    query.productCode.trim() !== "" ||
    query.weidianItemId.trim() !== ""
  );
}

function clearResults() {
  hasQueried.value = false;
  rows.value = [];
  total.value = 0;
}

async function load() {
  if (!hasPrecisionFilter()) {
    ElMessage.warning(SEARCH_TIP);
    clearResults();
    return;
  }
  loading.value = true;
  try {
    const page = await listKolItems({
      page: query.page,
      size: query.size,
      kolCode: query.kolCode.trim() || undefined,
      productCode: query.productCode.trim() || undefined,
      shopId: query.shopId || undefined,
      weidianItemId: query.weidianItemId.trim() || undefined,
      current: !query.includeHistory
    });
    hasQueried.value = true;
    rows.value = page.items;
    total.value = Number(page.total);
  } catch (error) {
    notifyError(error);
  } finally {
    loading.value = false;
  }
}

async function loadShops() {
  try {
    shops.value = await listShops();
  } catch (error) {
    notifyError(error);
  }
}

function search() {
  query.page = 1;
  void load();
}

function reset() {
  query.kolCode = "";
  query.productCode = "";
  query.shopId = undefined;
  query.weidianItemId = "";
  query.includeHistory = false;
  query.page = 1;
  clearResults();
}

async function rebuildIndex() {
  try {
    await ElMessageBox.confirm(
      "将按当前对应关系、达人、商品与店铺状态重建全部中转链接索引，在后台执行。确定开始吗？",
      "重建链接索引",
      { type: "warning" }
    );
  } catch {
    return; // cancelled
  }
  rebuilding.value = true;
  try {
    await rebuildLinkIndex();
    ElMessage.success("已开始重建");
  } catch (error) {
    notifyError(error);
  } finally {
    rebuilding.value = false;
  }
}

onMounted(() => {
  void loadShops();
});
</script>

<template>
  <el-card shadow="never">
    <el-form :inline="true" :model="query" @submit.prevent>
      <el-form-item label="达人编号">
        <el-input
          v-model="query.kolCode"
          clearable
          maxlength="16"
          data-test="kol-code"
          @keyup.enter="search"
        />
      </el-form-item>
      <el-form-item label="原商品编码">
        <el-input
          v-model="query.productCode"
          clearable
          maxlength="16"
          data-test="product-code"
          @keyup.enter="search"
        />
      </el-form-item>
      <el-form-item label="店铺">
        <el-select v-model="query.shopId" clearable placeholder="全部" style="width: 160px">
          <el-option v-for="s in shops" :key="s.id" :label="s.shopName" :value="s.id" />
        </el-select>
      </el-form-item>
      <el-form-item label="专属商品 ID">
        <el-input
          v-model="query.weidianItemId"
          clearable
          maxlength="32"
          data-test="weidian-item-id"
          @keyup.enter="search"
        />
      </el-form-item>
      <el-form-item label="含历史">
        <el-switch v-model="query.includeHistory" data-test="history" />
      </el-form-item>
      <el-form-item>
        <el-button type="primary" data-test="search" @click="search">查询</el-button>
        <el-button data-test="reset" @click="reset">重置</el-button>
        <el-button
          v-if="abilities.canImportMapping"
          data-test="import"
          @click="importVisible = true"
        >
          批量导入
        </el-button>
        <el-button
          v-if="links.canRebuildIndex"
          type="warning"
          plain
          :loading="rebuilding"
          data-test="rebuild-index"
          @click="rebuildIndex"
        >
          重建链接索引
        </el-button>
      </el-form-item>
    </el-form>

    <el-alert
      v-if="!hasQueried"
      data-test="search-tip"
      type="info"
      :closable="false"
      :title="SEARCH_TIP"
      show-icon
      class="tip"
    />

    <template v-else>
      <el-table v-loading="loading" :data="rows" row-key="id" border>
        <el-table-column prop="kolCode" label="达人编号" width="120" />
        <el-table-column prop="kolName" label="达人姓名" min-width="110" />
        <el-table-column prop="productCode" label="原商品编码" width="130" />
        <el-table-column prop="productName" label="原商品名称" min-width="140" />
        <el-table-column prop="shopName" label="店铺" min-width="110" />
        <el-table-column prop="weidianItemId" label="专属商品 ID" min-width="130" />
        <el-table-column label="生效时间" width="170">
          <template #default="{ row }">{{ formatInstant((row as KolItemVO).validFrom) }}</template>
        </el-table-column>
        <el-table-column label="失效时间" width="170">
          <template #default="{ row }">
            {{
              (row as KolItemVO).validTo ? formatInstant((row as KolItemVO).validTo) : "当前有效"
            }}
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
    </template>

    <ImportDialog
      v-model="importVisible"
      title="批量导入对应关系"
      limit-hint="单个文件不超过 20MB、20000 行"
      template-file-name="kol-item-import-template.xlsx"
      task-type="MAPPING_IMPORT"
      :download-template="downloadKolItemTemplate"
      :upload="uploadKolItemImport"
      @finished="search"
    />
  </el-card>
</template>

<style scoped>
.tip {
  margin-top: 8px;
}
.pager {
  margin-top: 16px;
  justify-content: flex-end;
}
</style>
