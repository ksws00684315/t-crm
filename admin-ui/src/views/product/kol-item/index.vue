<script setup lang="ts">
import { computed, onMounted, reactive, ref } from "vue";
import { useI18n } from "vue-i18n";
import { ElMessage, ElMessageBox } from "element-plus";
import { rebuildLinkIndex } from "@/api/link/link";
import { downloadKolItemTemplate, listKolItems, uploadKolItemImport } from "@/api/product/kol-item";
import { listShops } from "@/api/product/shop";
import type { KolItemVO, ShopVO } from "@/api/types";
import CopyText from "@/components/CopyText.vue";
import DataTable, { type DataColumn } from "@/components/DataTable.vue";
import ImportDialog from "@/components/ImportDialog.vue";
import ListSearch, { type SearchChip } from "@/components/ListSearch.vue";
import { useUserStore } from "@/stores/user";
import { formatInstant } from "@/utils/format";
import { notifyError } from "@/utils/notify";
import { linkAbilities, productAbilities } from "@/utils/permission";
import { readPageSize } from "@/utils/table-prefs";

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
  size: readPageSize("kol-items", 20),
  kolCode: "",
  productCode: "",
  weidianItemId: "",
  includeHistory: false
});

function asItem(row: object): KolItemVO {
  return row as KolItemVO;
}

const columns = computed<DataColumn[]>(() => [
  { key: "kolCode", label: t("mapping.kolCode"), width: 140, mono: true },
  { key: "kolName", label: t("mapping.kolName"), prop: "kolName", minWidth: 110 },
  { key: "productCode", label: t("mapping.productCode"), width: 150, mono: true },
  { key: "productName", label: t("mapping.productName"), prop: "productName", minWidth: 140 },
  { key: "shopName", label: t("mapping.shop"), prop: "shopName", minWidth: 110 },
  { key: "weidianItemId", label: t("mapping.exclusiveId"), minWidth: 150, mono: true },
  { key: "validFrom", label: t("mapping.validFrom"), width: 170, mono: true },
  { key: "validTo", label: t("mapping.validTo"), width: 170, mono: true }
]);

const applied = ref({
  kolCode: "",
  productCode: "",
  shopId: undefined as string | undefined,
  weidianItemId: "",
  includeHistory: false
});

const chips = computed<SearchChip[]>(() => {
  const list: SearchChip[] = [];
  const kolCode = applied.value.kolCode;
  const productCode = applied.value.productCode;
  const weidianItemId = applied.value.weidianItemId;
  if (kolCode) list.push({ key: "kolCode", label: t("mapping.kolCode"), text: kolCode });
  if (productCode) {
    list.push({ key: "productCode", label: t("mapping.productCode"), text: productCode });
  }
  if (applied.value.shopId) {
    const shop = shops.value.find(item => item.id === applied.value.shopId);
    list.push({
      key: "shop",
      label: t("mapping.shop"),
      text: shop?.shopName ?? applied.value.shopId
    });
  }
  if (weidianItemId) {
    list.push({ key: "weidianItemId", label: t("mapping.exclusiveId"), text: weidianItemId });
  }
  if (applied.value.includeHistory) {
    list.push({
      key: "history",
      label: t("mapping.includeHistory"),
      text: t("mapping.includeHistory")
    });
  }
  return list;
});
const importVisible = ref(false);
const importTitle = computed(() => t("mapping.importTitle"));
const importLimitHint = computed(() => t("mapping.limitHint"));

function hasPrecisionFilter(): boolean {
  return (
    applied.value.kolCode !== "" ||
    applied.value.productCode !== "" ||
    applied.value.weidianItemId !== ""
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
      kolCode: applied.value.kolCode || undefined,
      productCode: applied.value.productCode || undefined,
      shopId: applied.value.shopId || undefined,
      weidianItemId: applied.value.weidianItemId || undefined,
      current: !applied.value.includeHistory
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

function snapshotFilters(): void {
  applied.value = {
    kolCode: query.kolCode.trim(),
    productCode: query.productCode.trim(),
    shopId: query.shopId,
    weidianItemId: query.weidianItemId.trim(),
    includeHistory: query.includeHistory
  };
}

function search() {
  query.page = 1;
  snapshotFilters();
  void load();
}

function reset() {
  query.kolCode = "";
  query.productCode = "";
  query.shopId = undefined;
  query.weidianItemId = "";
  query.includeHistory = false;
  query.page = 1;
  applied.value = {
    kolCode: "",
    productCode: "",
    shopId: undefined,
    weidianItemId: "",
    includeHistory: false
  };
  clearResults();
}

function removeChip(key: string): void {
  if (key === "kolCode") query.kolCode = "";
  if (key === "productCode") query.productCode = "";
  if (key === "shop") query.shopId = undefined;
  if (key === "weidianItemId") query.weidianItemId = "";
  if (key === "history") query.includeHistory = false;
  search();
}

function onPage(page: number): void {
  query.page = page;
  void load();
}

function onSize(size: number): void {
  query.size = size;
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
  <div class="list-page">
    <ListSearch :chips="chips" has-more @search="search" @reset="reset" @remove="removeChip">
      <template #primary>
        <el-form-item :label="t('mapping.kolCode')">
          <el-input v-model="query.kolCode" clearable maxlength="16" data-test="kol-code" />
        </el-form-item>
        <el-form-item :label="t('mapping.productCode')">
          <el-input v-model="query.productCode" clearable maxlength="16" data-test="product-code" />
        </el-form-item>
      </template>
      <template #more>
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
          />
        </el-form-item>
        <el-form-item :label="t('mapping.includeHistory')">
          <el-switch v-model="query.includeHistory" data-test="history" />
        </el-form-item>
      </template>
      <template #actions>
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
      </template>
    </ListSearch>

    <el-alert
      v-if="!hasQueried"
      data-test="search-tip"
      type="info"
      :closable="false"
      :title="t('mapping.searchTip')"
      show-icon
      class="tip"
    />

    <DataTable
      v-else
      table-id="kol-items"
      :rows="rows"
      :columns="columns"
      :loading="loading"
      :total="total"
      :page="query.page"
      :size="query.size"
      :filter-count="chips.length"
      @update:page="onPage"
      @update:size="onSize"
      @refresh="load"
      @clear-filters="reset"
    >
      <template #cell-kolCode="{ row }"><CopyText :value="asItem(row).kolCode" /></template>
      <template #cell-productCode="{ row }"><CopyText :value="asItem(row).productCode" /></template>
      <template #cell-weidianItemId="{ row }">
        <CopyText :value="asItem(row).weidianItemId" />
      </template>
      <template #cell-validFrom="{ row }">{{ formatInstant(asItem(row).validFrom) }}</template>
      <template #cell-validTo="{ row }">
        {{ asItem(row).validTo ? formatInstant(asItem(row).validTo) : t("mapping.current") }}
      </template>
    </DataTable>

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
  </div>
</template>

<style scoped>
.tip {
  margin-top: 8px;
}
</style>
