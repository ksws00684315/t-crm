<script setup lang="ts">
import { ElMessage, ElMessageBox } from "element-plus";
import { computed, onMounted, reactive, ref } from "vue";
import { useI18n } from "vue-i18n";
import {
  clearCopyTarget,
  createShop,
  editShop,
  listShops,
  setCopyTarget,
  updateShopStatus
} from "@/api/product/shop";
import type { ShopVO } from "@/api/types";
import CopyText from "@/components/CopyText.vue";
import DataTable, { type DataColumn } from "@/components/DataTable.vue";
import ListSearch from "@/components/ListSearch.vue";
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

function asShop(row: object): ShopVO {
  return row as ShopVO;
}

const columns = computed<DataColumn[]>(() => {
  const list: DataColumn[] = [
    { key: "shopName", label: t("shop.name"), prop: "shopName", minWidth: 140 },
    { key: "weidianShopId", label: t("shop.weidianId"), minWidth: 140 },
    { key: "status", label: t("common.status"), width: 90 },
    { key: "copyTarget", label: t("shop.copyTarget"), width: 110 },
    { key: "remark", label: t("common.remark"), prop: "remark", minWidth: 160, tooltip: true },
    { key: "updatedAt", label: t("common.updatedAt"), width: 170 }
  ];
  if (abilities.value.canManageShop) {
    list.push({ key: "actions", label: t("common.actions"), width: 280 });
  }
  return list;
});

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

async function markCopyTarget(row: ShopVO) {
  try {
    await setCopyTarget(row.id);
    ElMessage.success(t("shop.setCopyTargetDone"));
    await load();
  } catch (error) {
    notifyError(error);
  }
}

async function unmarkCopyTarget(row: ShopVO) {
  try {
    await clearCopyTarget(row.id);
    ElMessage.success(t("shop.clearCopyTargetDone"));
    await load();
  } catch (error) {
    notifyError(error);
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
  <div class="list-page">
    <ListSearch @search="load" @reset="load">
      <template #primary>
        <span class="hint">{{ t("shop.hint") }}</span>
      </template>
      <template v-if="abilities.canManageShop" #actions>
        <el-button type="primary" data-test="create" @click="openCreate">
          {{ t("shop.create") }}
        </el-button>
      </template>
    </ListSearch>

    <DataTable
      table-id="shops"
      :rows="rows"
      :columns="columns"
      :loading="loading"
      :total="rows.length"
      :paged="false"
      @refresh="load"
    >
      <template #cell-weidianShopId="{ row }">
        <CopyText :value="asShop(row).weidianShopId" />
      </template>
      <template #cell-status="{ row }">
        <el-tag :type="asShop(row).status === 1 ? 'success' : 'info'">
          {{ asShop(row).status === 1 ? t("common.enabled") : t("common.disabled") }}
        </el-tag>
      </template>
      <template #cell-copyTarget="{ row }">
        <el-tag v-if="asShop(row).copyTarget" type="warning" data-test="copy-target-tag">
          {{ t("shop.copyTarget") }}
        </el-tag>
        <span v-else>—</span>
      </template>
      <template #cell-updatedAt="{ row }">{{ formatInstant(asShop(row).updateTime) }}</template>
      <template v-if="abilities.canManageShop" #actions="{ row }">
        <el-button link type="primary" data-test="edit" @click="openEdit(asShop(row))">
          {{ t("common.edit") }}
        </el-button>
        <el-button
          v-if="asShop(row).status === 1 && !asShop(row).copyTarget"
          link
          type="primary"
          data-test="set-copy-target"
          @click="markCopyTarget(asShop(row))"
        >
          {{ t("shop.setCopyTarget") }}
        </el-button>
        <el-button
          v-if="asShop(row).copyTarget"
          link
          type="warning"
          data-test="clear-copy-target"
          @click="unmarkCopyTarget(asShop(row))"
        >
          {{ t("shop.clearCopyTarget") }}
        </el-button>
        <el-button
          link
          :type="asShop(row).status === 1 ? 'danger' : 'success'"
          data-test="status"
          @click="toggleStatus(asShop(row))"
        >
          {{ asShop(row).status === 1 ? t("common.disable") : t("common.enable") }}
        </el-button>
      </template>
    </DataTable>

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
  </div>
</template>

<style scoped>
.hint {
  color: var(--tcrm-color-text-secondary);
  font-size: var(--tcrm-font-size-help);
  line-height: var(--tcrm-line-height-help);
}
</style>
