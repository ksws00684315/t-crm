<script setup lang="ts">
import { ElMessage, ElMessageBox } from "element-plus";
import { computed, onMounted, reactive, ref } from "vue";
import { createShop, editShop, listShops, updateShopStatus } from "@/api/product/shop";
import type { ShopVO } from "@/api/types";
import { useUserStore } from "@/stores/user";
import { formatInstant } from "@/utils/format";
import { notifyError } from "@/utils/notify";
import { productAbilities } from "@/utils/permission";

defineOptions({ name: "ShopListPage" });

const me = useUserStore();
const abilities = computed(() => productAbilities(me.user));

const loading = ref(false);
const rows = ref<ShopVO[]>([]);
const formVisible = ref(false);
const editing = ref<ShopVO | null>(null);
const form = reactive({ shopName: "", weidianShopId: "", remark: "" });
const saving = ref(false);

async function load() {
  loading.value = true;
  try {
    rows.value = await listShops();
  } catch (error) {
    notifyError(error);
  } finally {
    loading.value = false;
  }
}

function openCreate() {
  editing.value = null;
  form.shopName = "";
  form.weidianShopId = "";
  form.remark = "";
  formVisible.value = true;
}

function openEdit(row: ShopVO) {
  editing.value = row;
  form.shopName = row.shopName;
  form.weidianShopId = row.weidianShopId;
  form.remark = row.remark ?? "";
  formVisible.value = true;
}

async function save() {
  saving.value = true;
  try {
    if (editing.value) {
      await editShop(editing.value.id, {
        shopName: form.shopName.trim(),
        remark: form.remark.trim() || null
      });
      ElMessage.success("已保存");
    } else {
      await createShop({
        shopName: form.shopName.trim(),
        weidianShopId: form.weidianShopId.trim(),
        remark: form.remark.trim() || null
      });
      ElMessage.success("已创建");
    }
    formVisible.value = false;
    await load();
  } catch (error) {
    notifyError(error);
  } finally {
    saving.value = false;
  }
}

async function toggleStatus(row: ShopVO) {
  const target = row.status === 1 ? 0 : 1;
  const verb = target === 0 ? "停用" : "启用";
  try {
    await ElMessageBox.confirm(`确定要${verb}店铺「${row.shopName}」吗？`, `${verb}店铺`, {
      type: "warning"
    });
  } catch {
    return;
  }
  try {
    await updateShopStatus(row.id, target);
    ElMessage.success(`已${verb}`);
    await load();
  } catch (error) {
    notifyError(error);
  }
}

onMounted(() => void load());
</script>

<template>
  <el-card shadow="never">
    <div class="toolbar">
      <span class="hint">全系统最多 3 个店铺；停用后不可再导入商品或对应关系。</span>
      <el-button
        v-if="abilities.canManageShop"
        type="primary"
        data-test="create"
        @click="openCreate"
      >
        新增店铺
      </el-button>
    </div>

    <el-table v-loading="loading" :data="rows" row-key="id" border>
      <el-table-column prop="shopName" label="店铺名称" min-width="140" />
      <el-table-column prop="weidianShopId" label="微店店铺 ID" min-width="140" />
      <el-table-column label="状态" width="90">
        <template #default="{ row }">
          <el-tag :type="(row as ShopVO).status === 1 ? 'success' : 'info'">
            {{ (row as ShopVO).status === 1 ? "启用" : "停用" }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="remark" label="备注" min-width="160" />
      <el-table-column label="更新时间" width="170">
        <template #default="{ row }">{{ formatInstant((row as ShopVO).updateTime) }}</template>
      </el-table-column>
      <el-table-column v-if="abilities.canManageShop" label="操作" width="160" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" data-test="edit" @click="openEdit(row as ShopVO)">
            编辑
          </el-button>
          <el-button
            link
            :type="(row as ShopVO).status === 1 ? 'danger' : 'success'"
            data-test="status"
            @click="toggleStatus(row as ShopVO)"
          >
            {{ (row as ShopVO).status === 1 ? "停用" : "启用" }}
          </el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog
      v-model="formVisible"
      :title="editing ? '编辑店铺' : '新增店铺'"
      width="480px"
      :close-on-click-modal="false"
    >
      <el-form label-width="110px" @submit.prevent>
        <el-form-item label="店铺名称" required>
          <el-input v-model="form.shopName" maxlength="64" />
        </el-form-item>
        <el-form-item label="微店店铺 ID" required>
          <el-input
            v-model="form.weidianShopId"
            maxlength="32"
            :disabled="!!editing"
            placeholder="数字，创建后不可修改"
          />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="form.remark" type="textarea" maxlength="500" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="formVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存</el-button>
      </template>
    </el-dialog>
  </el-card>
</template>

<style scoped>
.toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 12px;
}
.hint {
  color: var(--el-text-color-secondary);
  font-size: 13px;
}
</style>
