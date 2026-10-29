<script setup lang="ts">
import NameCode from "@/components/NameCode.vue";
import { Picture } from "@element-plus/icons-vue";
import { useRoute } from "vue-router";
import { ElMessage, ElMessageBox } from "element-plus";
import { computed, onMounted, reactive, ref, watch } from "vue";
import { useI18n } from "vue-i18n";
import { listKols } from "@/api/kol/kol";
import { rebuildLinkIndex } from "@/api/link/link";
import { listKolItems } from "@/api/product/kol-item";
import { listShops } from "@/api/product/shop";
import type { KolItemVO, KolVO, ShopOptionVO } from "@/api/types";
import CopyText from "@/components/CopyText.vue";
import DataTable, { type DataColumn } from "@/components/DataTable.vue";
import ImportDialog from "@/components/ImportDialog.vue";
import ListSearch, { type SearchChip } from "@/components/ListSearch.vue";
import RowActions, { type RowAction } from "@/components/RowActions.vue";
import { useListExport } from "@/composables/useListExport";
import { useUserStore } from "@/stores/user";
import { formatInstant, formatNameWithCode } from "@/utils/format";
import { notifyError } from "@/utils/notify";
import { importTemplateFileName, MAPPING_IMPORT } from "@/utils/import-types";
import { canExport, kolAbilities, linkAbilities, productAbilities } from "@/utils/permission";
import { formatRate } from "@/utils/rate";
import { readPageSize } from "@/utils/table-prefs";
import PromoSheetUploadDialog from "@/views/kol/PromoSheetUploadDialog.vue";
import KolItemDetailDrawer from "./KolItemDetailDrawer.vue";

defineOptions({ name: "KolItemListPage" });

const { t } = useI18n();
const route = useRoute();
const me = useUserStore();
const abilities = computed(() => productAbilities(me.user));
const links = computed(() => linkAbilities(me.user));
const kolTools = computed(() => kolAbilities(me.user));
const exportAllowed = computed(() => canExport(me.user));
const rebuilding = ref(false);
const loading = ref(false);
const rows = ref<KolItemVO[]>([]);
const total = ref(0);
const shops = ref<ShopOptionVO[]>([]);
const query = reactive<{
  page: number;
  size: number;
  linkCode: string;
  kolId?: string;
  shopId?: string;
}>({
  page: 1,
  size: readPageSize("kol-items", 20),
  linkCode: ""
});

function asItem(row: object): KolItemVO {
  return row as KolItemVO;
}

function kolText(row: KolItemVO): string {
  if (row.kolName || row.kolCode) {
    return formatNameWithCode(row.kolName, row.kolCode);
  }
  return "-";
}

function shopText(row: KolItemVO): string {
  if (row.targetShopName || row.targetShopCode) {
    return formatNameWithCode(row.targetShopName, row.targetShopCode);
  }
  return "-";
}

function shopOptionLabel(shop: ShopOptionVO): string {
  return formatNameWithCode(shop.shopName, shop.shopCode);
}

function named(group: "channels" | "statuses" | "cleanups", value: string | null): string {
  return value ? t(`mapping.${group}.${value}`) : "-";
}

const columns = computed<DataColumn[]>(() => [
  { key: "linkCode", align: "left" as const, label: t("mapping.linkCode"), minWidth: 180 },
  {
    key: "product",
    align: "left" as const,
    label: t("mapping.product"),
    minWidth: 200,
    tooltip: true
  },
  { key: "kol", align: "left" as const, label: t("mapping.kol"), minWidth: 160 },
  { key: "targetShop", align: "left" as const, label: t("mapping.targetShop"), minWidth: 160 },
  { key: "targetChannel", label: t("mapping.targetChannel"), minWidth: 110 },
  { key: "targetItemId", align: "left" as const, label: t("mapping.targetItemId"), minWidth: 160 },
  { key: "targetItemUrl", align: "left" as const, label: t("mapping.targetItemUrl"), width: 90 },
  { key: "sourceItemId", align: "left" as const, label: t("mapping.sourceItemId"), minWidth: 150 },
  { key: "sourceChannel", label: t("mapping.sourceChannel"), minWidth: 110 },
  { key: "currentRate", label: t("mapping.currentRate"), minWidth: 140 },
  { key: "salesQuantity", label: t("mapping.salesQuantity"), width: 80 },
  { key: "payAmount", label: t("mapping.payAmount"), minWidth: 120, amount: true },
  { key: "status", label: t("mapping.status"), width: 90 },
  { key: "cleanupStatus", label: t("mapping.cleanupStatus"), minWidth: 120 },
  { key: "createTime", label: t("mapping.createTime"), width: 170 },
  { key: "actions", label: t("common.actions"), width: 88 }
]);

const applied = ref({
  linkCode: "",
  kolKeyword: "",
  shopId: undefined as string | undefined
});

const chips = computed<SearchChip[]>(() => {
  const list: SearchChip[] = [];
  if (applied.value.linkCode) {
    list.push({ key: "linkCode", label: t("mapping.linkCode"), text: applied.value.linkCode });
  }
  if (applied.value.kolKeyword) {
    const remembered =
      selectedFilterKol.value?.kolCode === applied.value.kolKeyword
        ? selectedFilterKol.value
        : undefined;
    const kol =
      remembered ?? filterKolOptions.value.find(item => item.kolCode === applied.value.kolKeyword);
    list.push({
      key: "kol",
      label: t("mapping.kol"),
      text: kol ? formatNameWithCode(kol.name, kol.kolCode) : applied.value.kolKeyword
    });
  }
  if (applied.value.shopId) {
    const shop = shops.value.find(item => item.id === applied.value.shopId);
    list.push({
      key: "shop",
      label: t("mapping.targetShop"),
      text: shop?.shopName ?? applied.value.shopId
    });
  }
  return list;
});

const importVisible = ref(false);
const promoSheetVisible = ref(false);
const promoSheetKol = ref<{ id: string; label: string; name: string; code: string } | null>(null);
const kolPickerVisible = ref(false);
const kolSearchKeyword = ref("");
const kolSearching = ref(false);
const kolOptions = ref<KolVO[]>([]);
const filterKolOptions = ref<KolVO[]>([]);
const selectedFilterKol = ref<KolVO | null>(null);
const filterSearching = ref(false);
const detailVisible = ref(false);
const detailId = ref<string | null>(null);

function openDetail(row: KolItemVO) {
  detailId.value = row.id;
  detailVisible.value = true;
}

function rowActions(row: KolItemVO): RowAction[] {
  return [
    {
      key: "view",
      label: t("mapping.view"),
      testId: "view",
      onClick: () => openDetail(row)
    }
  ];
}

async function searchKols(keyword: string): Promise<void> {
  await searchKolList(keyword, kolOptions, kolSearching);
}

async function searchFilterKols(keyword: string): Promise<void> {
  const text = keyword.trim();
  if (!text) {
    filterKolOptions.value = selectedFilterKol.value ? [selectedFilterKol.value] : [];
    return;
  }
  await searchKolList(text, filterKolOptions, filterSearching);
  const selected = selectedFilterKol.value;
  if (selected && !filterKolOptions.value.some(item => item.id === selected.id)) {
    filterKolOptions.value = [selected, ...filterKolOptions.value];
  }
}

async function searchKolList(
  keyword: string,
  target: { value: KolVO[] },
  pending: { value: boolean }
): Promise<void> {
  const text = keyword.trim();
  if (!text) {
    target.value = [];
    return;
  }
  pending.value = true;
  try {
    const page = await listKols({ page: 1, size: 20, keyword: text });
    target.value = page.items;
  } catch (error) {
    notifyError(error);
  } finally {
    pending.value = false;
  }
}

function pickKol(kolId: string) {
  const kol = kolOptions.value.find(item => item.id === kolId);
  if (!kol) return;
  promoSheetKol.value = {
    id: kol.id,
    label: formatNameWithCode(kol.name, kol.kolCode),
    name: kol.name,
    code: kol.kolCode
  };
  kolPickerVisible.value = false;
  promoSheetVisible.value = true;
}

function onKolFilter(kolId: string | undefined) {
  query.kolId = kolId;
  if (!kolId) {
    selectedFilterKol.value = null;
    return;
  }
  const kol = filterKolOptions.value.find(item => item.id === kolId);
  if (kol) selectedFilterKol.value = kol;
}

function selectedKolCode(): string {
  const selected = selectedFilterKol.value;
  if (!selected || selected.id !== query.kolId) return "";
  return selected.kolCode;
}

function appliedQuery() {
  return {
    linkCode: applied.value.linkCode || undefined,
    kolKeyword: applied.value.kolKeyword || undefined,
    shopId: applied.value.shopId || undefined
  };
}

const { exporting, exportCsv } = useListExport<KolItemVO>({
  fileBase: () => t("export.fileMapping"),
  fetchPage: (page, size) => listKolItems({ page, size, ...appliedQuery() }),
  columns: () => [
    { label: t("mapping.linkCode"), value: row => row.linkCode },
    { label: t("mapping.product"), value: row => row.itemName ?? "" },
    { label: t("mapping.kol"), value: row => kolText(row) },
    { label: t("mapping.targetShop"), value: row => shopText(row) },
    { label: t("mapping.targetChannel"), value: row => named("channels", row.targetChannel) },
    { label: t("mapping.targetItemId"), value: row => row.targetItemId },
    { label: t("mapping.targetItemUrl"), value: row => row.targetItemUrl ?? "" },
    { label: t("mapping.sourceItemId"), value: row => row.sourceItemId ?? "" },
    { label: t("mapping.sourceChannel"), value: row => named("channels", row.sourceChannel) },
    { label: t("mapping.currentRate"), value: row => formatRate(row.currentRate) },
    { label: t("mapping.salesQuantity"), value: row => row.salesQuantity },
    { label: t("mapping.payAmount"), value: row => row.payAmount },
    { label: t("mapping.status"), value: row => named("statuses", row.status) },
    { label: t("mapping.cleanupStatus"), value: row => named("cleanups", row.cleanupStatus) },
    { label: t("mapping.createTime"), value: row => formatInstant(row.createTime) }
  ]
});

async function load() {
  loading.value = true;
  try {
    const page = await listKolItems({
      page: query.page,
      size: query.size,
      ...appliedQuery()
    });
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
    linkCode: query.linkCode.trim(),
    kolKeyword: selectedKolCode(),
    shopId: query.shopId
  };
}

function search() {
  query.page = 1;
  snapshotFilters();
  void load();
}

function reset() {
  query.linkCode = "";
  query.kolId = undefined;
  query.shopId = undefined;
  query.page = 1;
  selectedFilterKol.value = null;
  filterKolOptions.value = [];
  applied.value = { linkCode: "", kolKeyword: "", shopId: undefined };
  void load();
}

function removeChip(key: string): void {
  if (key === "linkCode") query.linkCode = "";
  if (key === "kol") {
    query.kolId = undefined;
    selectedFilterKol.value = null;
  }
  if (key === "shop") query.shopId = undefined;
  search();
}

function onPage(page: number): void {
  query.page = page;
  void load();
}

function onSize(size: number): void {
  query.size = size;
}

function openItem(url: string | null) {
  if (!url) return;
  window.open(url, "_blank", "noopener");
}

async function rebuildIndex() {
  try {
    await ElMessageBox.confirm(t("mapping.confirmRebuild"), t("mapping.rebuildTitle"), {
      type: "warning"
    });
  } catch {
    return;
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

/**
 * The order chain pushes ?linkCode=… here (task 59); the tab may already be open and kept alive,
 * so watch the route instead of reading it once on mount.
 */
watch(
  () => route.query.linkCode,
  linkCode => {
    const wanted = typeof linkCode === "string" ? linkCode.trim() : "";
    if (!wanted || wanted === query.linkCode) return;
    query.linkCode = wanted;
    search();
  },
  { immediate: true }
);

onMounted(() => {
  void loadShops();
  // The immediate route watch above already searched when a linkCode came with the URL.
  if (!query.linkCode) void load();
});
</script>

<template>
  <div class="list-page">
    <ListSearch :chips="chips" @search="search" @reset="reset" @remove="removeChip">
      <template #primary>
        <el-form-item :label="t('mapping.linkCode')">
          <el-input v-model="query.linkCode" clearable maxlength="16" data-test="link-code" />
        </el-form-item>
        <el-form-item :label="t('mapping.kol')">
          <el-select
            :model-value="query.kolId"
            filterable
            remote
            clearable
            :remote-method="searchFilterKols"
            :loading="filterSearching"
            :placeholder="t('mapping.kolPlaceholder')"
            data-test="kol-keyword"
            style="width: 220px"
            @update:model-value="onKolFilter"
          >
            <el-option
              v-for="kol in filterKolOptions"
              :key="kol.id"
              :label="formatNameWithCode(kol.name, kol.kolCode)"
              :value="kol.id"
              ><NameCode :name="kol.name" :code="kol.kolCode"
            /></el-option>
          </el-select>
        </el-form-item>
        <el-form-item :label="t('mapping.targetShop')">
          <el-select
            v-model="query.shopId"
            clearable
            :placeholder="t('common.all')"
            data-test="shop-filter"
            style="width: 180px"
          >
            <el-option
              v-for="shop in shops"
              :key="shop.id"
              :label="shopOptionLabel(shop)"
              :value="shop.id"
            />
          </el-select>
        </el-form-item>
      </template>
      <template #actions>
        <el-button
          v-if="kolTools.canWrite"
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
          {{ t("mapping.import") }}
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

    <DataTable
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
      <template #cell-linkCode="{ row }">
        <CopyText :value="asItem(row).linkCode" />
      </template>
      <template #cell-product="{ row }">
        <div class="product-cell">
          <el-image
            v-if="asItem(row).imageUrl"
            :src="asItem(row).imageUrl"
            fit="cover"
            class="product-thumb"
          >
            <template #error>
              <span class="product-thumb product-thumb--empty" data-test="image-fallback">
                <el-icon><Picture /></el-icon>
              </span>
            </template>
          </el-image>
          <span v-else class="product-thumb product-thumb--empty" data-test="image-placeholder">
            <el-icon><Picture /></el-icon>
          </span>
          <span class="product-name">{{ asItem(row).itemName || "-" }}</span>
        </div>
      </template>
      <template #cell-kol="{ row }"
        ><NameCode :name="asItem(row).kolName" :code="asItem(row).kolCode"
      /></template>
      <template #cell-targetShop="{ row }">{{ shopText(asItem(row)) }}</template>
      <template #cell-targetChannel="{ row }">
        {{ named("channels", asItem(row).targetChannel) }}
      </template>
      <template #cell-targetItemId="{ row }">
        <CopyText :value="asItem(row).targetItemId" />
      </template>
      <template #cell-targetItemUrl="{ row }">
        <el-button
          v-if="asItem(row).targetItemUrl"
          link
          type="primary"
          data-test="open-target"
          @click="openItem(asItem(row).targetItemUrl)"
        >
          {{ t("mapping.open") }}
        </el-button>
        <span v-else>-</span>
      </template>
      <template #cell-sourceItemId="{ row }">
        <CopyText :value="asItem(row).sourceItemId" />
      </template>
      <template #cell-sourceChannel="{ row }">
        {{ named("channels", asItem(row).sourceChannel) }}
      </template>
      <template #cell-currentRate="{ row }">{{ formatRate(asItem(row).currentRate) }}</template>
      <template #cell-salesQuantity="{ row }">{{ asItem(row).salesQuantity }}</template>
      <template #cell-payAmount="{ row }">{{ asItem(row).payAmount }}</template>
      <template #cell-status="{ row }">{{ named("statuses", asItem(row).status) }}</template>
      <template #cell-cleanupStatus="{ row }">
        {{ named("cleanups", asItem(row).cleanupStatus) }}
      </template>
      <template #cell-createTime="{ row }">{{ formatInstant(asItem(row).createTime) }}</template>
      <template #actions="{ row }">
        <RowActions :actions="rowActions(asItem(row))" />
      </template>
    </DataTable>

    <ImportDialog
      v-model="importVisible"
      :title="t(MAPPING_IMPORT.titleKey)"
      :limit-hint="t(MAPPING_IMPORT.limitHintKey)"
      :template-file-name="importTemplateFileName(MAPPING_IMPORT, key => t(key))"
      :task-type="MAPPING_IMPORT.taskType"
      :accept-csv="MAPPING_IMPORT.acceptCsv"
      :require-data-cutoff="MAPPING_IMPORT.requireDataCutoff"
      :download-template="MAPPING_IMPORT.downloadTemplate"
      :upload="MAPPING_IMPORT.upload"
      @finished="search"
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
          :label="formatNameWithCode(kol.name, kol.kolCode)"
          :value="kol.id"
          ><NameCode :name="kol.name" :code="kol.kolCode"
        /></el-option>
      </el-select>
    </el-dialog>
    <PromoSheetUploadDialog
      v-model="promoSheetVisible"
      :kol-id="promoSheetKol?.id ?? null"
      :kol-label="promoSheetKol?.label ?? ''"
      :kol-name="promoSheetKol?.name"
      :kol-code="promoSheetKol?.code"
    />
    <KolItemDetailDrawer v-if="detailVisible" v-model="detailVisible" :item-id="detailId" />
  </div>
</template>

<style scoped>
.product-cell {
  display: flex;
  align-items: center;
  gap: var(--tcrm-space-2);
  min-width: 0;
}

.product-thumb {
  width: var(--tcrm-space-6);
  height: var(--tcrm-space-6);
  flex: none;
  border-radius: var(--tcrm-radius-tag);
}

.product-thumb--empty {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  background: var(--tcrm-color-muted);
  color: var(--tcrm-color-text-secondary);
}

.product-name {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
</style>
