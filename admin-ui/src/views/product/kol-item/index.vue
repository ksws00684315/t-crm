<script setup lang="ts">
import { computed, onMounted, reactive, ref } from "vue";
import { useI18n } from "vue-i18n";
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

const { t } = useI18n();
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
const importTitle = computed(() => t("mapping.importTitle"));
const importLimitHint = computed(() => t("mapping.limitHint"));

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
    ElMessage.warning(t("mapping.searchTip"));
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
    await ElMessageBox.confirm(t("mapping.confirmRebuild"), t("mapping.rebuildTitle"), {
      type: "warning"
    });
  } catch {
    return; // cancelled
  }
  rebuilding.value = true;
  try {
    await rebuildLinkIndex();
    ElMessage.success(t("mapping.rebuildStarted"));
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
      <el-form-item :label="t('mapping.kolCode')">
        <el-input
          v-model="query.kolCode"
          clearable
          maxlength="16"
          data-test="kol-code"
          @keyup.enter="search"
        />
      </el-form-item>
      <el-form-item :label="t('mapping.productCode')">
        <el-input
          v-model="query.productCode"
          clearable
          maxlength="16"
          data-test="product-code"
          @keyup.enter="search"
        />
      </el-form-item>
      <el-form-item :label="t('mapping.shop')">
        <el-select
          v-model="query.shopId"
          clearable
          :placeholder="t('common.all')"
          style="width: 160px"
        >
          <el-option v-for="s in shops" :key="s.id" :label="s.shopName" :value="s.id" />
        </el-select>
      </el-form-item>
      <el-form-item :label="t('mapping.exclusiveId')">
        <el-input
          v-model="query.weidianItemId"
          clearable
          maxlength="32"
          data-test="weidian-item-id"
          @keyup.enter="search"
        />
      </el-form-item>
      <el-form-item :label="t('mapping.includeHistory')">
        <el-switch v-model="query.includeHistory" data-test="history" />
      </el-form-item>
      <el-form-item>
        <el-button type="primary" data-test="search" @click="search">{{
          t("common.search")
        }}</el-button>
        <el-button data-test="reset" @click="reset">{{ t("common.reset") }}</el-button>
        <el-button
          v-if="abilities.canImportMapping"
          data-test="import"
          @click="importVisible = true"
        >
          {{ t("kol.import") }}
        </el-button>
        <el-button
          v-if="links.canRebuildIndex"
          type="warning"
          plain
          :loading="rebuilding"
          data-test="rebuild-index"
          @click="rebuildIndex"
        >
          {{ t("mapping.rebuildTitle") }}
        </el-button>
      </el-form-item>
    </el-form>

    <el-alert
      v-if="!hasQueried"
      data-test="search-tip"
      type="info"
      :closable="false"
      :title="t('mapping.searchTip')"
      show-icon
      class="tip"
    />

    <template v-else>
      <el-table v-loading="loading" :data="rows" row-key="id" border>
        <el-table-column prop="kolCode" :label="t('mapping.kolCode')" width="120" />
        <el-table-column prop="kolName" :label="t('mapping.kolName')" min-width="110" />
        <el-table-column prop="productCode" :label="t('mapping.productCode')" width="130" />
        <el-table-column prop="productName" :label="t('mapping.productName')" min-width="140" />
        <el-table-column prop="shopName" :label="t('mapping.shop')" min-width="110" />
        <el-table-column prop="weidianItemId" :label="t('mapping.exclusiveId')" min-width="130" />
        <el-table-column :label="t('mapping.validFrom')" width="170">
          <template #default="{ row }">{{ formatInstant((row as KolItemVO).validFrom) }}</template>
        </el-table-column>
        <el-table-column :label="t('mapping.validTo')" width="170">
          <template #default="{ row }">
            {{
              (row as KolItemVO).validTo
                ? formatInstant((row as KolItemVO).validTo)
                : t("mapping.current")
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
      :title="importTitle"
      :limit-hint="importLimitHint"
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
