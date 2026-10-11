<script setup lang="ts">
import { ElMessage, ElMessageBox } from "element-plus";
import { computed, onMounted, reactive, ref } from "vue";
import { useI18n } from "vue-i18n";
import { createShop, editShop, listShops, updateShopStatus } from "@/api/product/shop";
import type { ShopVO } from "@/api/types";
import { useUserStore } from "@/stores/user";
import { formatInstant } from "@/utils/format";
import { notifyError } from "@/utils/notify";
import { productAbilities } from "@/utils/permission";

defineOptions({ name: "ShopListPage" });

const { t } = useI18n();
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
      ElMessage.success(t("shop.saved"));
    } else {
      await createShop({
        shopName: form.shopName.trim(),
        weidianShopId: form.weidianShopId.trim(),
        remark: form.remark.trim() || null
      });
      ElMessage.success(t("shop.created"));
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
  const disabling = target === 0;
  try {
    await ElMessageBox.confirm(
      disabling
        ? t("shop.confirmDisable", { name: row.shopName })
        : t("shop.confirmEnable", { name: row.shopName }),
      disabling ? t("shop.disableTitle") : t("shop.enableTitle"),
      { type: "warning" }
    );
  } catch {
    return;
  }
  try {
    await updateShopStatus(row.id, target);
    ElMessage.success(disabling ? t("shop.disabled") : t("shop.enabled"));
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
      <span class="hint">{{ t("shop.hint") }}</span>
      <el-button
        v-if="abilities.canManageShop"
        type="primary"
        data-test="create"
        @click="openCreate"
      >
        {{ t("shop.create") }}
      </el-button>
    </div>

    <el-table v-loading="loading" :data="rows" row-key="id" border>
      <el-table-column prop="shopName" :label="t('shop.name')" min-width="140" />
      <el-table-column prop="weidianShopId" :label="t('shop.weidianId')" min-width="140" />
      <el-table-column :label="t('common.status')" width="90">
        <template #default="{ row }">
          <el-tag :type="(row as ShopVO).status === 1 ? 'success' : 'info'">
            {{ (row as ShopVO).status === 1 ? t("common.enabled") : t("common.disabled") }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="remark" :label="t('common.remark')" min-width="160" />
      <el-table-column :label="t('common.updatedAt')" width="170">
        <template #default="{ row }">{{ formatInstant((row as ShopVO).updateTime) }}</template>
      </el-table-column>
      <el-table-column
        v-if="abilities.canManageShop"
        :label="t('common.actions')"
        width="160"
        fixed="right"
      >
        <template #default="{ row }">
          <el-button link type="primary" data-test="edit" @click="openEdit(row as ShopVO)">
            {{ t("common.edit") }}
          </el-button>
          <el-button
            link
            :type="(row as ShopVO).status === 1 ? 'danger' : 'success'"
            data-test="status"
            @click="toggleStatus(row as ShopVO)"
          >
            {{ (row as ShopVO).status === 1 ? t("common.disabled") : t("common.enabled") }}
          </el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog
      v-model="formVisible"
      :title="editing ? t('shop.editTitle') : t('shop.createTitle')"
      width="480px"
      :close-on-click-modal="false"
    >
      <el-form label-width="110px" @submit.prevent>
        <el-form-item :label="t('shop.name')" required>
          <el-input v-model="form.shopName" maxlength="64" />
        </el-form-item>
        <el-form-item :label="t('shop.weidianId')" required>
          <el-input
            v-model="form.weidianShopId"
            maxlength="32"
            :disabled="!!editing"
            :placeholder="t('shop.weidianPlaceholder')"
          />
        </el-form-item>
        <el-form-item :label="t('common.remark')">
          <el-input v-model="form.remark" type="textarea" maxlength="500" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="formVisible = false">{{ t("common.cancel") }}</el-button>
        <el-button type="primary" :loading="saving" @click="save">{{ t("common.save") }}</el-button>
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
