<script setup lang="ts">
import { computed, onMounted, reactive, ref } from "vue";
import { useI18n } from "vue-i18n";
import {
  downloadOrderImportTemplate,
  downloadRefundImportTemplate,
  listOrders,
  listSyncStates,
  uploadOrderImport,
  uploadRefundImport,
  type OrderQuery
} from "@/api/order/order";
import type {
  AttributionStatus,
  OrderStatus,
  OrderSyncStateVO,
  OrderVO,
  PageResult
} from "@/api/types";
import CopyText from "@/components/CopyText.vue";
import DataTable, { type DataColumn } from "@/components/DataTable.vue";
import ImportDialog from "@/components/ImportDialog.vue";
import ListSearch, { type SearchChip } from "@/components/ListSearch.vue";
import { useUserStore } from "@/stores/user";
import { formatInstant } from "@/utils/format";
import { notifyError } from "@/utils/notify";
import { orderAbilities } from "@/utils/permission";
import {
  ATTRIBUTION_STATUSES,
  ORDER_STATUSES,
  attributionStatusText,
  attributionTagType,
  orderStatusText,
  payRangeToQuery
} from "@/utils/order";
import { readPageSize } from "@/utils/table-prefs";
import KolDetailDrawer from "@/views/kol/KolDetailDrawer.vue";
import OrderLines from "./OrderLines.vue";
import SyncStatesDialog from "./SyncStatesDialog.vue";

defineOptions({ name: "OrderListPage" });

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
  kolCode: string;
  orderStatus?: OrderStatus;
  attributionStatus?: AttributionStatus;
}>({ page: 1, size: readPageSize("orders", 20), payRange: null, kolCode: "" });

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
  { key: "attributionStatus", label: t("order.attributionStatus"), width: 100 }
]);

const applied = ref(snapshot());

function snapshot() {
  return {
    shopId: query.shopId,
    payRange: query.payRange,
    kolCode: query.kolCode.trim(),
    orderStatus: query.orderStatus,
    attributionStatus: query.attributionStatus
  };
}

function appliedQuery(): OrderQuery {
  return {
    shopId: applied.value.shopId || undefined,
    ...payRangeToQuery(applied.value.payRange),
    kolCode: applied.value.kolCode || undefined,
    orderStatus: applied.value.orderStatus,
    attributionStatus: applied.value.attributionStatus
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
  if (applied.value.kolCode) {
    list.push({ key: "kolCode", label: t("order.kolCode"), text: applied.value.kolCode });
  }
  if (applied.value.orderStatus) {
    list.push({
      key: "orderStatus",
      label: t("order.orderStatus"),
      text: orderStatusText(applied.value.orderStatus)
    });
  }
  if (applied.value.attributionStatus) {
    list.push({
      key: "attributionStatus",
      label: t("order.attributionStatus"),
      text: attributionStatusText(applied.value.attributionStatus)
    });
  }
  return list;
});

const syncVisible = ref(false);
const orderImportVisible = ref(false);
const refundImportVisible = ref(false);
const detailVisible = ref(false);
const detailKolId = ref<string | null>(null);

async function load() {
  loading.value = true;
  try {
    const page: PageResult<OrderVO> = await listOrders({
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
  query.kolCode = "";
  query.orderStatus = undefined;
  query.attributionStatus = undefined;
  search();
}

function removeChip(key: string): void {
  if (key === "shop") query.shopId = undefined;
  if (key === "payRange") query.payRange = null;
  if (key === "kolCode") query.kolCode = "";
  if (key === "orderStatus") query.orderStatus = undefined;
  if (key === "attributionStatus") query.attributionStatus = undefined;
  search();
}

function onPage(page: number): void {
  query.page = page;
  void load();
}

function onSize(size: number): void {
  query.size = size;
}

function openKol(order: OrderVO) {
  if (!abilities.value.canViewKol || !order.kolId) return;
  detailKolId.value = order.kolId;
  detailVisible.value = true;
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
      <template #more>
        <el-form-item :label="t('order.kolCode')">
          <el-input v-model="query.kolCode" clearable maxlength="16" style="width: 160px" />
        </el-form-item>
        <el-form-item :label="t('order.orderStatus')">
          <el-select
            v-model="query.orderStatus"
            clearable
            :placeholder="t('common.all')"
            style="width: 140px"
          >
            <el-option
              v-for="status in ORDER_STATUSES"
              :key="status"
              :label="orderStatusText(status)"
              :value="status"
            />
          </el-select>
        </el-form-item>
        <el-form-item :label="t('order.attributionStatus')">
          <el-select
            v-model="query.attributionStatus"
            clearable
            :placeholder="t('common.all')"
            style="width: 140px"
          >
            <el-option
              v-for="status in ATTRIBUTION_STATUSES"
              :key="status"
              :label="attributionStatusText(status)"
              :value="status"
            />
          </el-select>
        </el-form-item>
      </template>
      <template v-if="abilities.canManage" #actions>
        <el-button data-test="sync-states" @click="syncVisible = true">
          {{ t("order.syncStatus") }}
        </el-button>
        <el-button data-test="import-orders" @click="orderImportVisible = true">
          {{ t("order.importOrders") }}
        </el-button>
        <el-button data-test="import-refunds" @click="refundImportVisible = true">
          {{ t("order.importRefunds") }}
        </el-button>
      </template>
    </ListSearch>

    <DataTable
      table-id="orders"
      :rows="rows"
      :columns="columns"
      :loading="loading"
      :total="total"
      :page="query.page"
      :size="query.size"
      :filter-count="chips.length"
      expandable
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
        <template v-if="asOrder(row).kolCode">
          <el-button
            v-if="abilities.canViewKol"
            link
            type="primary"
            data-test="order-kol"
            @click="openKol(asOrder(row))"
          >
            {{ asOrder(row).kolCode }}{{ asOrder(row).kolName ? ` · ${asOrder(row).kolName}` : "" }}
          </el-button>
          <span v-else
            >{{ asOrder(row).kolCode
            }}{{ asOrder(row).kolName ? ` · ${asOrder(row).kolName}` : "" }}</span
          >
        </template>
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
      <template #expand="{ row }">
        <OrderLines :order="asOrder(row)" />
      </template>
    </DataTable>

    <SyncStatesDialog v-model="syncVisible" @synced="load" />

    <ImportDialog
      accept-csv
      v-model="orderImportVisible"
      :title="t('order.orderImportTitle')"
      :limit-hint="t('order.importLimitHint')"
      :template-file-name="t('order.orderTemplateFile')"
      task-type="ORDER_IMPORT"
      :download-template="downloadOrderImportTemplate"
      :upload="uploadOrderImport"
      @finished="search"
    />

    <ImportDialog
      accept-csv
      v-model="refundImportVisible"
      :title="t('order.refundImportTitle')"
      :limit-hint="t('order.importLimitHint')"
      :template-file-name="t('order.refundTemplateFile')"
      task-type="REFUND_IMPORT"
      :download-template="downloadRefundImportTemplate"
      :upload="uploadRefundImport"
      @finished="search"
    />

    <KolDetailDrawer v-model="detailVisible" :kol-id="detailKolId" />
  </div>
</template>
