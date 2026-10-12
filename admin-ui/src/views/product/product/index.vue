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
import ImportDialog from "@/components/ImportDialog.vue";
import { useUserStore } from "@/stores/user";
import { formatInstant } from "@/utils/format";
import { notifyError } from "@/utils/notify";
import { productAbilities } from "@/utils/permission";
import { formatRate, percentToRate, rateToPercent, validatePercent } from "@/utils/rate";

defineOptions({ name: "ProductListPage" });

const { t } = useI18n();
const me = useUserStore();
const abilities = computed(() => productAbilities(me.user));

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
}>({ page: 1, size: 20, keyword: "", category: "" });

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

async function load() {
  loading.value = true;
  try {
    const page = await listProducts({
      page: query.page,
      size: query.size,
      keyword: query.keyword.trim() || undefined,
      shopId: query.shopId || undefined,
      status: query.status,
      category: query.category.trim() || undefined
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

function search() {
  query.page = 1;
  void load();
}

function reset() {
  query.keyword = "";
  query.shopId = undefined;
  query.status = undefined;
  query.category = "";
  search();
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
  <el-card shadow="never">
    <el-form :inline="true" :model="query" @submit.prevent>
      <el-form-item :label="t('common.keyword')">
        <el-input
          v-model="query.keyword"
          clearable
          maxlength="64"
          :placeholder="t('product.keywordPlaceholder')"
          @keyup.enter="search"
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
        <el-input v-model="query.category" clearable maxlength="64" @keyup.enter="search" />
      </el-form-item>
      <el-form-item>
        <el-button type="primary" @click="search">{{ t("common.search") }}</el-button>
        <el-button @click="reset">{{ t("common.reset") }}</el-button>
        <el-button
          v-if="abilities.canWriteProduct"
          data-test="import"
          @click="importVisible = true"
        >
          {{ t("kol.import") }}
        </el-button>
      </el-form-item>
    </el-form>

    <el-table v-loading="loading" :data="rows" row-key="id" border>
      <el-table-column prop="productCode" :label="t('product.code')" width="130" />
      <el-table-column prop="name" :label="t('product.name')" min-width="160" />
      <el-table-column prop="shopName" :label="t('product.shop')" min-width="120" />
      <el-table-column prop="weidianItemId" :label="t('product.weidianItemId')" min-width="130" />
      <el-table-column prop="category" :label="t('product.category')" width="100" />
      <el-table-column :label="t('product.price')" width="100">
        <template #default="{ row }">{{ (row as ProductVO).price }}</template>
      </el-table-column>
      <el-table-column :label="t('product.rate')" width="100">
        <template #default="{ row }">{{ formatRate((row as ProductVO).rate) }}</template>
      </el-table-column>
      <el-table-column :label="t('common.status')" width="80">
        <template #default="{ row }">
          <el-tag :type="(row as ProductVO).status === 1 ? 'success' : 'info'">
            {{ (row as ProductVO).status === 1 ? t("product.onSale") : t("product.offShelf") }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column :label="t('common.updatedAt')" width="170">
        <template #default="{ row }">{{ formatInstant((row as ProductVO).updateTime) }}</template>
      </el-table-column>
      <el-table-column
        v-if="abilities.canWriteProduct"
        :label="t('common.actions')"
        width="140"
        fixed="right"
      >
        <template #default="{ row }">
          <el-button link type="primary" data-test="edit" @click="openEdit(row as ProductVO)">
            {{ t("common.edit") }}
          </el-button>
          <el-button
            link
            :type="(row as ProductVO).status === 1 ? 'danger' : 'success'"
            data-test="status"
            @click="toggleStatus(row as ProductVO)"
          >
            {{ (row as ProductVO).status === 1 ? t("product.offShelf") : t("product.putOnShelf") }}
          </el-button>
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
  </el-card>
</template>

<style scoped>
.pager {
  margin-top: 16px;
  justify-content: flex-end;
}
</style>
