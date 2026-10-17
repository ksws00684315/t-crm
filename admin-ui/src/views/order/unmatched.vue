<script setup lang="ts">
import { ElMessage, ElMessageBox } from "element-plus";
import { computed, onMounted, reactive, ref } from "vue";
import { useI18n } from "vue-i18n";
import {
  assignOrderAttribution,
  listSyncStates,
  listUnmatchedOrders,
  reattributeOrder,
  type OrderQuery
} from "@/api/order/order";
import { listKols } from "@/api/kol/kol";
import type { KolVO, OrderSyncStateVO, OrderVO, PageResult } from "@/api/types";
import CopyText from "@/components/CopyText.vue";
import DataTable, { type DataColumn } from "@/components/DataTable.vue";
import ListSearch, { type SearchChip } from "@/components/ListSearch.vue";
import { useUserStore } from "@/stores/user";
import { formatInstant } from "@/utils/format";
import { notifyError } from "@/utils/notify";
import { orderAbilities } from "@/utils/permission";
import {
  attributionStatusText,
  attributionTagType,
  manualAttributionRequest,
  orderStatusText,
  payRangeToQuery
} from "@/utils/order";
import { readPageSize } from "@/utils/table-prefs";
import OrderLines from "./OrderLines.vue";

defineOptions({ name: "OrderUnmatchedPage" });

const { t } = useI18n();
const me = useUserStore();
const abilities = computed(() => orderAbilities(me.user));

const loading = ref(false);
const rows = ref<OrderVO[]>([]);
const total = ref(0);
const shops = ref<OrderSyncStateVO[]>([]);
const query = reactive<{
  page: number;
  size: number;
  shopId?: string;
  payRange: [Date, Date] | null;
}>({ page: 1, size: readPageSize("order-unmatched", 20), payRange: null });

function asOrder(row: object): OrderVO {
  return row as OrderVO;
}

const columns = computed<DataColumn[]>(() => [
  { key: "weidianOrderId", label: t("order.orderId"), width: 150, mono: true },
  { key: "shopName", label: t("order.shop"), prop: "shopName", minWidth: 120 },
  { key: "payTime", label: t("order.payTime"), width: 170, mono: true },
  { key: "orderStatus", label: t("order.orderStatus"), width: 110 },
  { key: "payAmount", label: t("order.payAmount"), width: 100, amount: true },
  { key: "refundAmount", label: t("order.refundTotal"), width: 100, amount: true },
  { key: "kol", label: t("order.kol"), minWidth: 160 },
  { key: "bdUserName", label: t("order.bd"), minWidth: 110 },
  { key: "attributionStatus", label: t("order.attributionStatus"), width: 100 },
  { key: "actions", label: t("common.actions"), width: 210 }
]);

const applied = ref(snapshot());

function snapshot() {
  return { shopId: query.shopId, payRange: query.payRange };
}

function appliedQuery(): OrderQuery {
  return {
    shopId: applied.value.shopId || undefined,
    ...payRangeToQuery(applied.value.payRange)
  };
}

const chips = computed<SearchChip[]>(() => {
  const list: SearchChip[] = [];
  if (applied.value.shopId) {
    const shop = shops.value.find(item => item.shopId === applied.value.shopId);
    list.push({
      key: "shop",
      label: t("order.shop"),
      text: shop?.shopName ?? applied.value.shopId
    });
  }
  if (applied.value.payRange) {
    const [from, to] = applied.value.payRange;
    list.push({
      key: "payRange",
      label: t("order.payRange"),
      text: `${formatInstant(from.toISOString())} ~ ${formatInstant(to.toISOString())}`
    });
  }
  return list;
});

const assignVisible = ref(false);
const assigning = ref<OrderVO | null>(null);
const assignKolId = ref<string | null>(null);
const assignReason = ref("");
const assignSaving = ref(false);
const kolSearching = ref(false);
const kolOptions = ref<KolVO[]>([]);

const nonKolVisible = ref(false);
const nonKolTarget = ref<OrderVO | null>(null);
const nonKolReason = ref("");
const nonKolSaving = ref(false);

const acting = ref<string | null>(null);

async function load() {
  loading.value = true;
  try {
    const page: PageResult<OrderVO> = await listUnmatchedOrders({
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
    shops.value = await listSyncStates();
  } catch (error) {
    notifyError(error);
  }
}

function search() {
  query.page = 1;
  applied.value = snapshot();
  void load();
}

function reset() {
  query.shopId = undefined;
  query.payRange = null;
  search();
}

function removeChip(key: string): void {
  if (key === "shop") query.shopId = undefined;
  if (key === "payRange") query.payRange = null;
  search();
}

function onPage(page: number): void {
  query.page = page;
  void load();
}

function onSize(size: number): void {
  query.size = size;
}

async function searchKols(keyword: string) {
  if (!keyword.trim()) {
    kolOptions.value = [];
    return;
  }
  kolSearching.value = true;
  try {
    const page = await listKols({ keyword: keyword.trim(), page: 1, size: 20 });
    kolOptions.value = page.items;
  } catch (error) {
    notifyError(error);
  } finally {
    kolSearching.value = false;
  }
}

function openAssign(order: OrderVO) {
  assigning.value = order;
  assignKolId.value = null;
  assignReason.value = "";
  kolOptions.value = [];
  assignVisible.value = true;
}

async function submitAssign() {
  if (!assigning.value) return;
  const body = manualAttributionRequest(
    assignKolId.value ? { kolId: assignKolId.value } : null,
    assignReason.value
  );
  if (!body) {
    ElMessage.error(assignKolId.value ? t("order.pool.reasonRequired") : t("order.pool.selectKol"));
    return;
  }
  assignSaving.value = true;
  try {
    await assignOrderAttribution(assigning.value.id, body);
    ElMessage.success(t("order.pool.assigned"));
    assignVisible.value = false;
    await load();
  } catch (error) {
    notifyError(error);
  } finally {
    assignSaving.value = false;
  }
}

function openNonKol(order: OrderVO) {
  nonKolTarget.value = order;
  nonKolReason.value = "";
  nonKolVisible.value = true;
}

async function submitNonKol() {
  if (!nonKolTarget.value) return;
  const body = manualAttributionRequest({ nonKol: true }, nonKolReason.value);
  if (!body) {
    ElMessage.error(t("order.pool.reasonRequired"));
    return;
  }
  nonKolSaving.value = true;
  try {
    await assignOrderAttribution(nonKolTarget.value.id, body);
    ElMessage.success(t("order.pool.markedNonKol"));
    nonKolVisible.value = false;
    await load();
  } catch (error) {
    notifyError(error);
  } finally {
    nonKolSaving.value = false;
  }
}

async function rematch(order: OrderVO) {
  try {
    await ElMessageBox.confirm(t("order.pool.rematchConfirm"), t("order.pool.rematchTitle"), {
      type: "warning"
    });
  } catch {
    return;
  }
  acting.value = order.id;
  try {
    await reattributeOrder(order.id);
    ElMessage.success(t("order.pool.rematched"));
    await load();
  } catch (error) {
    notifyError(error);
  } finally {
    acting.value = null;
  }
}

onMounted(() => {
  void load();
  void loadShops();
});
</script>

<template>
  <div class="list-page">
    <ListSearch :chips="chips" @search="search" @reset="reset" @remove="removeChip">
      <template #primary>
        <el-form-item :label="t('order.shop')">
          <el-select
            v-model="query.shopId"
            clearable
            :placeholder="t('common.all')"
            style="width: 180px"
          >
            <el-option v-for="s in shops" :key="s.shopId" :label="s.shopName" :value="s.shopId" />
          </el-select>
        </el-form-item>
        <el-form-item :label="t('order.payRange')">
          <el-date-picker
            v-model="query.payRange"
            type="datetimerange"
            :start-placeholder="t('order.payRange')"
            :end-placeholder="t('order.payRange')"
            style="width: 360px"
          />
        </el-form-item>
      </template>
    </ListSearch>

    <DataTable
      table-id="order-unmatched"
      :rows="rows"
      :columns="columns"
      :loading="loading"
      :total="total"
      :page="query.page"
      :size="query.size"
      :filter-count="chips.length"
      expandable
      default-expand-all
      @update:page="onPage"
      @update:size="onSize"
      @refresh="load"
      @clear-filters="reset"
    >
      <template #cell-weidianOrderId="{ row }">
        <CopyText :value="asOrder(row).weidianOrderId" />
      </template>
      <template #cell-payTime="{ row }">{{ formatInstant(asOrder(row).payTime) }}</template>
      <template #cell-orderStatus="{ row }">
        {{ orderStatusText(asOrder(row).orderStatus) }}
      </template>
      <template #cell-kol="{ row }">
        <span v-if="asOrder(row).kolCode">
          {{ asOrder(row).kolCode }}{{ asOrder(row).kolName ? ` · ${asOrder(row).kolName}` : "" }}
        </span>
        <span v-else>-</span>
      </template>
      <template #cell-bdUserName="{ row }">
        {{ asOrder(row).bdUserName ?? t("order.company") }}
      </template>
      <template #cell-attributionStatus="{ row }">
        <el-tag :type="attributionTagType(asOrder(row).attributionStatus)">
          {{ attributionStatusText(asOrder(row).attributionStatus) }}
        </el-tag>
      </template>
      <template #actions="{ row }">
        <el-button
          v-if="abilities.canViewKol"
          link
          type="primary"
          data-test="assign-kol"
          @click="openAssign(asOrder(row))"
        >
          {{ t("order.pool.assign") }}
        </el-button>
        <el-button link type="primary" data-test="mark-non-kol" @click="openNonKol(asOrder(row))">
          {{ t("order.pool.markNonKol") }}
        </el-button>
        <el-button
          link
          type="warning"
          data-test="rematch"
          :loading="acting === asOrder(row).id"
          @click="rematch(asOrder(row))"
        >
          {{ t("order.pool.rematch") }}
        </el-button>
      </template>
      <template #expand="{ row }">
        <OrderLines :order="asOrder(row)" />
      </template>
    </DataTable>

    <el-dialog
      v-model="assignVisible"
      :title="t('order.pool.assignTitle')"
      width="520px"
      :close-on-click-modal="false"
    >
      <el-form label-width="100px" @submit.prevent>
        <el-form-item :label="t('order.orderId')">
          <el-input :model-value="assigning?.weidianOrderId" disabled />
        </el-form-item>
        <el-form-item :label="t('order.kol')" required>
          <el-select
            v-model="assignKolId"
            filterable
            remote
            clearable
            :remote-method="searchKols"
            :loading="kolSearching"
            :placeholder="t('order.pool.searchKol')"
            style="width: 100%"
          >
            <el-option
              v-for="kol in kolOptions"
              :key="kol.id"
              :label="kol.kolCode + (kol.name ? ` · ${kol.name}` : '')"
              :value="kol.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item :label="t('order.pool.reason')" required>
          <el-input
            v-model="assignReason"
            type="textarea"
            :rows="3"
            maxlength="255"
            show-word-limit
            :placeholder="t('order.pool.reasonPlaceholder')"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="assignVisible = false">{{ t("common.cancel") }}</el-button>
        <el-button
          type="primary"
          :loading="assignSaving"
          data-test="assign-submit"
          @click="submitAssign"
        >
          {{ t("common.confirm") }}
        </el-button>
      </template>
    </el-dialog>

    <el-dialog
      v-model="nonKolVisible"
      :title="t('order.pool.nonKolTitle')"
      width="520px"
      :close-on-click-modal="false"
    >
      <el-form label-width="100px" @submit.prevent>
        <el-form-item :label="t('order.orderId')">
          <el-input :model-value="nonKolTarget?.weidianOrderId" disabled />
        </el-form-item>
        <el-form-item :label="t('order.pool.reason')" required>
          <el-input
            v-model="nonKolReason"
            type="textarea"
            :rows="3"
            maxlength="255"
            show-word-limit
            :placeholder="t('order.pool.reasonPlaceholder')"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="nonKolVisible = false">{{ t("common.cancel") }}</el-button>
        <el-button
          type="primary"
          :loading="nonKolSaving"
          data-test="non-kol-submit"
          @click="submitNonKol"
        >
          {{ t("common.confirm") }}
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>
