<script setup lang="ts">
import { ElMessage, ElMessageBox } from "element-plus";
import { computed, onMounted, reactive, ref } from "vue";
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
    ElMessage.success("已保存");
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
  const verb = target === 0 ? "下架" : "上架";
  try {
    await ElMessageBox.confirm(`确定要${verb}商品 ${row.productCode} 吗？`, `${verb}商品`, {
      type: "warning"
    });
  } catch {
    return;
  }
  try {
    await updateProductStatus(row.id, target);
    ElMessage.success(`已${verb}`);
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
      <el-form-item label="关键字">
        <el-input
          v-model="query.keyword"
          clearable
          maxlength="64"
          placeholder="编码 / 名称 / 微店商品 ID"
          @keyup.enter="search"
        />
      </el-form-item>
      <el-form-item label="店铺">
        <el-select v-model="query.shopId" clearable placeholder="全部" style="width: 160px">
          <el-option
            v-for="s in shops"
            :key="s.id"
            :label="s.shopName"
            :value="s.id"
          />
        </el-select>
      </el-form-item>
      <el-form-item label="状态">
        <el-select v-model="query.status" clearable placeholder="全部" style="width: 110px">
          <el-option label="在售" :value="1" />
          <el-option label="下架" :value="0" />
        </el-select>
      </el-form-item>
      <el-form-item label="分类">
        <el-input v-model="query.category" clearable maxlength="64" @keyup.enter="search" />
      </el-form-item>
      <el-form-item>
        <el-button type="primary" @click="search">查询</el-button>
        <el-button @click="reset">重置</el-button>
        <el-button
          v-if="abilities.canWriteProduct"
          data-test="import"
          @click="importVisible = true"
        >
          批量导入
        </el-button>
      </el-form-item>
    </el-form>

    <el-table v-loading="loading" :data="rows" row-key="id" border>
      <el-table-column prop="productCode" label="原商品编码" width="130" />
      <el-table-column prop="name" label="商品名称" min-width="160" />
      <el-table-column prop="shopName" label="店铺" min-width="120" />
      <el-table-column prop="weidianItemId" label="微店商品 ID" min-width="130" />
      <el-table-column prop="category" label="分类" width="100" />
      <el-table-column label="价格" width="100">
        <template #default="{ row }">{{ (row as ProductVO).price }}</template>
      </el-table-column>
      <el-table-column label="佣金比例" width="100">
        <template #default="{ row }">{{ formatRate((row as ProductVO).rate) }}</template>
      </el-table-column>
      <el-table-column label="状态" width="80">
        <template #default="{ row }">
          <el-tag :type="(row as ProductVO).status === 1 ? 'success' : 'info'">
            {{ (row as ProductVO).status === 1 ? "在售" : "下架" }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="更新时间" width="170">
        <template #default="{ row }">{{ formatInstant((row as ProductVO).updateTime) }}</template>
      </el-table-column>
      <el-table-column v-if="abilities.canWriteProduct" label="操作" width="140" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" data-test="edit" @click="openEdit(row as ProductVO)">
            编辑
          </el-button>
          <el-button
            link
            :type="(row as ProductVO).status === 1 ? 'danger' : 'success'"
            data-test="status"
            @click="toggleStatus(row as ProductVO)"
          >
            {{ (row as ProductVO).status === 1 ? "下架" : "上架" }}
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
      title="编辑原商品"
      width="520px"
      :close-on-click-modal="false"
    >
      <el-form label-width="120px" @submit.prevent>
        <el-form-item label="原商品编码">
          <el-input :model-value="editing?.productCode" disabled />
        </el-form-item>
        <el-form-item label="商品名称" required>
          <el-input v-model="form.name" maxlength="200" />
        </el-form-item>
        <el-form-item label="分类">
          <el-input v-model="form.category" maxlength="64" placeholder="可清空" />
        </el-form-item>
        <el-form-item label="图片地址">
          <el-input v-model="form.imageUrl" maxlength="512" placeholder="https://，可清空" />
        </el-form-item>
        <el-form-item label="价格" required>
          <el-input v-model="form.price" placeholder="如 128.50" />
        </el-form-item>
        <el-form-item label="佣金比例(%)">
          <el-input v-model="form.ratePercent" placeholder="0～100，可清空" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="formVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存</el-button>
      </template>
    </el-dialog>

    <ImportDialog
      v-model="importVisible"
      title="批量导入原商品"
      limit-hint="单个文件不超过 20MB、20000 行"
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
