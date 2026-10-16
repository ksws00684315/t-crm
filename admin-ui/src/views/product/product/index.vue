<script setup lang="ts">
import { ElMessage, ElMessageBox } from "element-plus";
import { computed, onMounted, reactive, ref } from "vue";
import { useI18n } from "vue-i18n";
import {
  downloadProductTemplate,
  editProduct,
  listProducts,
  updateProductStatus,
  uploadProductImport
} from "@/api/product/product";
import { listShops } from "@/api/product/shop";
import type { ProductVO, ShopVO } from "@/api/types";
import CopyText from "@/components/CopyText.vue";
import DataTable, { type DataColumn } from "@/components/DataTable.vue";
import ImportDialog from "@/components/ImportDialog.vue";
import ListSearch, { type SearchChip } from "@/components/ListSearch.vue";
import { useBatchAction } from "@/composables/useBatchAction";
import { useListExport } from "@/composables/useListExport";
import { useUserStore } from "@/stores/user";
import { formatInstant } from "@/utils/format";
import { notifyError } from "@/utils/notify";
import { canExport, productAbilities } from "@/utils/permission";
import { formatRate, percentToRate, rateToPercent, validatePercent } from "@/utils/rate";
import { readPageSize } from "@/utils/table-prefs";

defineOptions({ name: "ProductListPage" });

const { t } = useI18n();
const me = useUserStore();
const abilities = computed(() => productAbilities(me.user));
const exportAllowed = computed(() => canExport(me.user));
const showActions = computed(
  () => abilities.value.canEditProduct || abilities.value.canChangeStatus
);

const loading = ref(false);
const rows = ref<ProductVO[]>([]);
const total = ref(0);
const shops = ref<ShopVO[]>([]);
const query = reactive<{
  page: number;
  size: number;
  keyword: string;
  shopId?: string;
  status?: 0 | 1;
  category: string;
}>({ page: 1, size: readPageSize("products", 20), keyword: "", category: "" });

function asProduct(row: object): ProductVO {
  return row as ProductVO;
}

const columns = computed<DataColumn[]>(() => {
  const list: DataColumn[] = [
    { key: "productCode", label: t("product.code"), width: 130, mono: true },
    { key: "name", label: t("product.name"), prop: "name", minWidth: 160 },
    { key: "shopName", label: t("product.shop"), prop: "shopName", minWidth: 120 },
    { key: "weidianItemId", label: t("product.weidianItemId"), minWidth: 130, mono: true },
    { key: "category", label: t("product.category"), prop: "category", width: 100 },
    { key: "price", label: t("product.price"), width: 100, amount: true },
    { key: "rate", label: t("product.rate"), width: 100, amount: true },
    { key: "status", label: t("common.status"), width: 80 },
    { key: "updatedAt", label: t("common.updatedAt"), width: 170, mono: true }
  ];
  if (showActions.value) {
    list.push({ key: "actions", label: t("common.actions"), width: 140 });
  }
  return list;
});

const applied = ref({
  keyword: "",
  shopId: undefined as string | undefined,
  status: undefined as 0 | 1 | undefined,
  category: ""
});

const chips = computed<SearchChip[]>(() => {
  const list: SearchChip[] = [];
  const keyword = applied.value.keyword;
  const category = applied.value.category;
  if (keyword) list.push({ key: "keyword", label: t("common.keyword"), text: keyword });
  if (applied.value.shopId) {
    const shop = shops.value.find(item => item.id === applied.value.shopId);
    list.push({
      key: "shop",
      label: t("product.shop"),
      text: shop?.shopName ?? applied.value.shopId
    });
  }
  if (applied.value.status === 0 || applied.value.status === 1) {
    list.push({
      key: "status",
      label: t("common.status"),
      text: applied.value.status === 1 ? t("product.onSale") : t("product.offShelf")
    });
  }
  if (category) list.push({ key: "category", label: t("product.category"), text: category });
  return list;
});

const formVisible = ref(false);
const editing = ref<ProductVO | null>(null);
const form = reactive({
  name: "",
  category: "",
  imageUrl: "",
  price: "",
  ratePercent: ""
});
const saving = ref(false);
const importVisible = ref(false);
const importTitle = computed(() => t("product.importTitle"));
const importLimitHint = computed(() => t("product.limitHint"));

function appliedQuery() {
  return {
    keyword: applied.value.keyword || undefined,
    shopId: applied.value.shopId || undefined,
    status: applied.value.status,
    category: applied.value.category || undefined
  };
}

const { exporting, exportCsv } = useListExport<ProductVO>({
  fileBase: () => t("export.fileProduct"),
  fetchPage: (page, size) => listProducts({ page, size, ...appliedQuery() }),
  columns: () => [
    { label: t("product.code"), value: row => row.productCode },
    { label: t("product.name"), value: row => row.name },
    { label: t("product.shop"), value: row => row.shopName },
    { label: t("product.weidianItemId"), value: row => row.weidianItemId },
    { label: t("product.category"), value: row => row.category },
    { label: t("product.price"), value: row => row.price },
    { label: t("product.rate"), value: row => formatRate(row.rate) },
    {
      label: t("common.status"),
      value: row => (row.status === 1 ? t("product.onSale") : t("product.offShelf"))
    },
    { label: t("common.updatedAt"), value: row => formatInstant(row.updateTime) }
  ]
});

const selection = ref<ProductVO[]>([]);
const batch = useBatchAction();

function batchStatus(target: 0 | 1): void {
  const targets = selection.value.filter(row => row.status !== target);
  if (targets.length === 0) {
    ElMessage.info(t("batch.nothingToChange"));
    return;
  }
  void batch.run({
    rows: targets,
    title: target === 0 ? t("product.offTitle") : t("product.onTitle"),
    message:
      target === 0
        ? t("batch.confirmOff", { n: targets.length })
        : t("batch.confirmOn", { n: targets.length }),
    label: row => row.productCode,
    action: row => updateProductStatus(row.id, target),
    after: load
  });
}

async function load() {
  loading.value = true;
  try {
    const page = await listProducts({
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
    keyword: query.keyword.trim(),
    shopId: query.shopId,
    status: query.status,
    category: query.category.trim()
  };
}

function search() {
  query.page = 1;
  snapshotFilters();
  void load();
}

function reset() {
  query.keyword = "";
  query.shopId = undefined;
  query.status = undefined;
  query.category = "";
  search();
}

function removeChip(key: string): void {
  if (key === "keyword") query.keyword = "";
  if (key === "shop") query.shopId = undefined;
  if (key === "status") query.status = undefined;
  if (key === "category") query.category = "";
  search();
}

function onPage(page: number): void {
  query.page = page;
  void load();
}

function onSize(size: number): void {
  query.size = size;
}

function openEdit(row: ProductVO) {
  editing.value = row;
  form.name = row.name;
  form.category = row.category ?? "";
  form.imageUrl = row.imageUrl ?? "";
  form.price = row.price;
  form.ratePercent = rateToPercent(row.rate);
  formVisible.value = true;
}

async function save() {
  if (!editing.value) return;
  const rateError = validatePercent(form.ratePercent);
  if (rateError) {
    ElMessage.error(rateError);
    return;
  }
  saving.value = true;
  try {
    await editProduct(editing.value.id, {
      name: form.name.trim(),
      category: form.category.trim() || null,
      imageUrl: form.imageUrl.trim() || null,
      price: form.price.trim(),
      rate: percentToRate(form.ratePercent)
    });
    ElMessage.success(t("product.saved"));
    formVisible.value = false;
    await load();
  } catch (error) {
    notifyError(error);
  } finally {
    saving.value = false;
  }
}

async function toggleStatus(row: ProductVO) {
  const target = row.status === 1 ? 0 : 1;
  const takingOff = target === 0;
  try {
    await ElMessageBox.confirm(
      takingOff
        ? t("product.confirmOff", { code: row.productCode })
        : t("product.confirmOn", { code: row.productCode }),
      takingOff ? t("product.offTitle") : t("product.onTitle"),
      { type: "warning" }
    );
  } catch {
    return;
  }
  try {
    await updateProductStatus(row.id, target);
    ElMessage.success(takingOff ? t("product.doneOff") : t("product.doneOn"));
    await load();
  } catch (error) {
    notifyError(error);
  }
}

onMounted(() => {
  void load();
  void loadShops();
});
</script>

<template>
  <div class="list-page">
    <ListSearch :chips="chips" has-more @search="search" @reset="reset" @remove="removeChip">
      <template #primary>
        <el-form-item :label="t('common.keyword')">
          <el-input
            v-model="query.keyword"
            clearable
            maxlength="64"
            :placeholder="t('product.keywordPlaceholder')"
          />
        </el-form-item>
        <el-form-item :label="t('product.shop')">
          <el-select
            v-model="query.shopId"
            clearable
            :placeholder="t('common.all')"
            style="width: 160px"
          >
            <el-option v-for="s in shops" :key="s.id" :label="s.shopName" :value="s.id" />
          </el-select>
        </el-form-item>
      </template>
      <template #more>
        <el-form-item :label="t('common.status')">
          <el-select
            v-model="query.status"
            clearable
            :placeholder="t('common.all')"
            style="width: 110px"
          >
            <el-option :label="t('product.onSale')" :value="1" />
            <el-option :label="t('product.offShelf')" :value="0" />
          </el-select>
        </el-form-item>
        <el-form-item :label="t('product.category')">
          <el-input v-model="query.category" clearable maxlength="64" />
        </el-form-item>
      </template>
      <template v-if="abilities.canEditProduct" #actions>
        <el-button data-test="import" @click="importVisible = true">
          {{ t("kol.import") }}
        </el-button>
      </template>
    </ListSearch>

    <DataTable
      table-id="products"
      :rows="rows"
      :columns="columns"
      :loading="loading"
      :total="total"
      :page="query.page"
      :size="query.size"
      :filter-count="chips.length"
      :exportable="exportAllowed"
      :exporting="exporting"
      :selectable="abilities.canChangeStatus"
      @export="exportCsv"
      @selection-change="selection = $event as ProductVO[]"
      @update:page="onPage"
      @update:size="onSize"
      @refresh="load"
      @clear-filters="reset"
    >
      <template #cell-productCode="{ row }">
        <CopyText :value="asProduct(row).productCode" />
      </template>
      <template #cell-weidianItemId="{ row }">
        <CopyText :value="asProduct(row).weidianItemId" />
      </template>
      <template #batch>
        <el-button
          type="success"
          plain
          size="small"
          :loading="batch.running.value"
          data-test="batch-enable"
          @click="batchStatus(1)"
          >{{ t("batch.on") }}</el-button
        >
        <el-button
          type="danger"
          plain
          size="small"
          :loading="batch.running.value"
          data-test="batch-disable"
          @click="batchStatus(0)"
          >{{ t("batch.off") }}</el-button
        >
      </template>
      <template #cell-price="{ row }">{{ asProduct(row).price }}</template>
      <template #cell-rate="{ row }">{{ formatRate(asProduct(row).rate) }}</template>
      <template #cell-status="{ row }">
        <el-tag :type="asProduct(row).status === 1 ? 'success' : 'info'">
          {{ asProduct(row).status === 1 ? t("product.onSale") : t("product.offShelf") }}
        </el-tag>
      </template>
      <template #cell-updatedAt="{ row }">{{ formatInstant(asProduct(row).updateTime) }}</template>
      <template v-if="showActions" #actions="{ row }">
        <el-button
          v-if="abilities.canEditProduct"
          link
          type="primary"
          data-test="edit"
          @click="openEdit(asProduct(row))"
        >
          {{ t("common.edit") }}
        </el-button>
        <el-button
          v-if="abilities.canChangeStatus"
          link
          :type="asProduct(row).status === 1 ? 'danger' : 'success'"
          data-test="status"
          @click="toggleStatus(asProduct(row))"
        >
          {{ asProduct(row).status === 1 ? t("product.offShelf") : t("product.putOnShelf") }}
        </el-button>
      </template>
    </DataTable>

    <el-dialog
      v-model="formVisible"
      :title="t('product.editTitle')"
      width="520px"
      :close-on-click-modal="false"
    >
      <el-form label-width="120px" @submit.prevent>
        <el-form-item :label="t('product.code')">
          <el-input :model-value="editing?.productCode" disabled />
        </el-form-item>
        <el-form-item :label="t('product.name')" required>
          <el-input v-model="form.name" maxlength="200" />
        </el-form-item>
        <el-form-item :label="t('product.category')">
          <el-input
            v-model="form.category"
            maxlength="64"
            :placeholder="t('product.categoryPlaceholder')"
          />
        </el-form-item>
        <el-form-item :label="t('product.image')">
          <el-input
            v-model="form.imageUrl"
            maxlength="512"
            :placeholder="t('product.imagePlaceholder')"
          />
        </el-form-item>
        <el-form-item :label="t('product.price')" required>
          <el-input v-model="form.price" :placeholder="t('product.pricePlaceholder')" />
        </el-form-item>
        <el-form-item :label="t('product.rateLabel')">
          <el-input v-model="form.ratePercent" :placeholder="t('product.ratePlaceholder')" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="formVisible = false">{{ t("common.cancel") }}</el-button>
        <el-button type="primary" :loading="saving" @click="save">{{ t("common.save") }}</el-button>
      </template>
    </el-dialog>

    <ImportDialog
      v-model="importVisible"
      :title="importTitle"
      :limit-hint="importLimitHint"
      template-file-name="product-import-template.xlsx"
      task-type="PRODUCT_IMPORT"
      :download-template="downloadProductTemplate"
      :upload="uploadProductImport"
      @finished="search"
    />
  </div>
</template>
