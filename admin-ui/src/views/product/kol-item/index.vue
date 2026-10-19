<script setup lang="ts">
import { computed, onMounted, reactive, ref } from "vue";
import { useI18n } from "vue-i18n";
import { ElMessage, ElMessageBox } from "element-plus";
import { rebuildLinkIndex } from "@/api/link/link";
import { listKols } from "@/api/kol/kol";
import { downloadKolItemTemplate, listKolItems, uploadKolItemImport } from "@/api/product/kol-item";
import { listShops } from "@/api/product/shop";
import type { KolItemVO, KolVO, ShopVO } from "@/api/types";
import CopyText from "@/components/CopyText.vue";
import DataTable, { type DataColumn } from "@/components/DataTable.vue";
import ImportDialog from "@/components/ImportDialog.vue";
import ListSearch, { type SearchChip } from "@/components/ListSearch.vue";
import { useListExport } from "@/composables/useListExport";
import { useUserStore } from "@/stores/user";
import { formatInstant } from "@/utils/format";
import { notifyError } from "@/utils/notify";
import { canExport, linkAbilities, productAbilities } from "@/utils/permission";
import { readPageSize } from "@/utils/table-prefs";
import KolLinksDrawer from "@/views/kol/KolLinksDrawer.vue";
import PromoSheetUploadDialog from "@/views/kol/PromoSheetUploadDialog.vue";

defineOptions({ name: "KolItemListPage" });

const { t } = useI18n();
const me = useUserStore();
const abilities = computed(() => productAbilities(me.user));
const links = computed(() => linkAbilities(me.user));
const exportAllowed = computed(() => canExport(me.user));
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
  source?: string;
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
  { key: "source", label: t("mapping.source"), width: 120 },
  { key: "validFrom", label: t("mapping.validFrom"), width: 170, mono: true },
  { key: "validTo", label: t("mapping.validTo"), width: 170, mono: true },
  { key: "actions", label: t("common.actions"), width: 90 }
]);

const sourceOptions = ["IMPORT", "SHEET_UPLOAD", "COPY"];

const applied = ref({
  kolCode: "",
  productCode: "",
  shopId: undefined as string | undefined,
  weidianItemId: "",
  source: undefined as string | undefined,
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
  if (applied.value.source) {
    list.push({
      key: "source",
      label: t("mapping.source"),
      text: t("mapping.sources." + applied.value.source)
    });
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
const linksVisible = ref(false);
const linksKol = ref<{ kolCode: string; kolName: string } | null>(null);
const promoSheetVisible = ref(false);
const promoSheetKol = ref<{ id: string; label: string } | null>(null);
const kolPickerVisible = ref(false);
const kolSearchKeyword = ref("");
const kolSearching = ref(false);
const kolOptions = ref<KolVO[]>([]);

function openLinks(row: KolItemVO) {
  linksKol.value = { kolCode: row.kolCode ?? "", kolName: row.kolName ?? "" };
  linksVisible.value = true;
}

async function searchKols(keyword: string): Promise<void> {
  const text = keyword.trim();
  if (!text) {
    kolOptions.value = [];
    return;
  }
  kolSearching.value = true;
  try {
    const page = await listKols({ page: 1, size: 20, keyword: text });
    kolOptions.value = page.items;
  } catch (error) {
    notifyError(error);
  } finally {
    kolSearching.value = false;
  }
}

function pickKol(kolId: string) {
  const kol = kolOptions.value.find(item => item.id === kolId);
  if (!kol) return;
  promoSheetKol.value = { id: kol.id, label: `${kol.name} (${kol.kolCode})` };
  kolPickerVisible.value = false;
  promoSheetVisible.value = true;
}

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

function appliedQuery() {
  return {
    kolCode: applied.value.kolCode || undefined,
    productCode: applied.value.productCode || undefined,
    shopId: applied.value.shopId || undefined,
    weidianItemId: applied.value.weidianItemId || undefined,
    source: applied.value.source || undefined,
    current: !applied.value.includeHistory
  };
}

const { exporting, exportCsv } = useListExport<KolItemVO>({
  fileBase: () => t("export.fileMapping"),
  fetchPage: (page, size) => listKolItems({ page, size, ...appliedQuery() }),
  columns: () => [
    { label: t("mapping.kolCode"), value: row => row.kolCode },
    { label: t("mapping.kolName"), value: row => row.kolName },
    { label: t("mapping.productCode"), value: row => row.productCode },
    {
      label: t("mapping.productName"),
      value: row => row.productName ?? row.itemName ?? ""
    },
    { label: t("mapping.shop"), value: row => row.shopName },
    { label: t("mapping.exclusiveId"), value: row => row.weidianItemId },
    {
      label: t("mapping.source"),
      value: row => (row.source ? t("mapping.sources." + row.source) : "")
    },
    { label: t("mapping.validFrom"), value: row => formatInstant(row.validFrom) },
    {
      label: t("mapping.validTo"),
      value: row => (row.validTo ? formatInstant(row.validTo) : t("mapping.current"))
    }
  ]
});

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
      ...appliedQuery()
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
    source: query.source,
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
  query.source = undefined;
  query.includeHistory = false;
  query.page = 1;
  applied.value = {
    kolCode: "",
    productCode: "",
    shopId: undefined,
    weidianItemId: "",
    source: undefined,
    includeHistory: false
  };
  clearResults();
}

function removeChip(key: string): void {
  if (key === "kolCode") query.kolCode = "";
  if (key === "productCode") query.productCode = "";
  if (key === "shop") query.shopId = undefined;
  if (key === "weidianItemId") query.weidianItemId = "";
  if (key === "source") query.source = undefined;
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
        <el-form-item :label="t('mapping.source')">
          <el-select
            v-model="query.source"
            clearable
            :placeholder="t('common.all')"
            style="width: 160px"
            data-test="source-filter"
          >
            <el-option
              v-for="value in sourceOptions"
              :key="value"
              :label="t('mapping.sources.' + value)"
              :value="value"
            />
          </el-select>
        </el-form-item>
        <el-form-item :label="t('mapping.includeHistory')">
          <el-switch v-model="query.includeHistory" data-test="history" />
        </el-form-item>
      </template>
      <template #actions>
        <el-button
          v-if="abilities.canImportMapping"
          data-test="upload-promo-sheet"
          @click="kolPickerVisible = true"
        >
          {{ t("promoSheet.open") }}
        </el-button>
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
      :exportable="exportAllowed"
      :exporting="exporting"
      @export="exportCsv"
      @update:page="onPage"
      @update:size="onSize"
      @refresh="load"
      @clear-filters="reset"
    >
      <template #cell-kolCode="{ row }"><CopyText :value="asItem(row).kolCode" /></template>
      <template #cell-productCode="{ row }">
        <CopyText v-if="asItem(row).productCode" :value="asItem(row).productCode" />
        <el-tag v-else size="small" type="info" data-test="no-product">{{
          t("mapping.noProduct")
        }}</el-tag>
      </template>
      <template #cell-productName="{ row }">
        {{ asItem(row).productName ?? asItem(row).itemName ?? "-" }}
      </template>
      <template #cell-source="{ row }">
        <el-tag v-if="asItem(row).source" size="small">{{
          t("mapping.sources." + asItem(row).source)
        }}</el-tag>
        <span v-else>-</span>
      </template>
      <template #cell-weidianItemId="{ row }">
        <CopyText :value="asItem(row).weidianItemId" />
      </template>
      <template #actions="{ row }">
        <el-button
          v-if="links.canViewLinks"
          link
          type="primary"
          data-test="links"
          @click="openLinks(asItem(row))"
        >
          {{ t("kol.links") }}
        </el-button>
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
    <KolLinksDrawer
      v-model="linksVisible"
      :kol-code="linksKol?.kolCode ?? null"
      :kol-name="linksKol?.kolName ?? undefined"
    />
    <el-dialog v-model="kolPickerVisible" :title="t('promoSheet.open')" width="480px">
      <el-select
        v-model="kolSearchKeyword"
        filterable
        remote
        clearable
        :remote-method="searchKols"
        :loading="kolSearching"
        :placeholder="t('bdTransfer.specificPlaceholder')"
        style="width: 100%"
        data-test="promo-kol-picker"
        @change="pickKol($event as string)"
      >
        <el-option
          v-for="kol in kolOptions"
          :key="kol.id"
          :label="`${kol.name} (${kol.kolCode})`"
          :value="kol.id"
        />
      </el-select>
    </el-dialog>
    <PromoSheetUploadDialog
      v-model="promoSheetVisible"
      :kol-id="promoSheetKol?.id ?? null"
      :kol-label="promoSheetKol?.label ?? ''"
    />
  </div>
</template>

<style scoped>
.tip {
  margin-top: 8px;
}
</style>
