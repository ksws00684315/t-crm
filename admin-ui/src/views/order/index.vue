<script setup lang="ts">
import { computed, onMounted, reactive, ref } from "vue";
import { useI18n } from "vue-i18n";
import { useRouter } from "vue-router";
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
  Channel,
  OrderStatus,
  OrderSyncStateVO,
  OrderVO,
  PageResult
} from "@/api/types";
import CopyText from "@/components/CopyText.vue";
import DataTable, { type DataColumn } from "@/components/DataTable.vue";
import ImportDialog from "@/components/ImportDialog.vue";
import ListSearch, { type SearchChip } from "@/components/ListSearch.vue";
import RowActions, { type RowAction } from "@/components/RowActions.vue";
import { useLocaleStore } from "@/stores/locale";
import { useUserStore } from "@/stores/user";
import { formatInstant, formatNameWithCode } from "@/utils/format";
import { notifyError } from "@/utils/notify";
import { orderAbilities } from "@/utils/permission";
import {
  ATTRIBUTION_STATUSES,
  CHANNELS,
  ORDER_STATUSES,
  attributionStatusText,
  channelText,
  orderRefundStatusText,
  orderRefundTagType,
  orderStatusText,
  payRangeToQuery
} from "@/utils/order";
import { readPageSize } from "@/utils/table-prefs";
import KolDetailDrawer from "@/views/kol/KolDetailDrawer.vue";
import OrderDetailDrawer from "./OrderDetailDrawer.vue";
import OrderLines from "./OrderLines.vue";
import SyncStatesDialog from "./SyncStatesDialog.vue";

defineOptions({ name: "OrderListPage" });

const { t } = useI18n();
const router = useRouter();
const me = useUserStore();
const abilities = computed(() => orderAbilities(me.user));

const loading = ref(false);
const rows = ref<OrderVO[]>([]);
const total = ref(0);
const shops = ref<OrderSyncStateVO[]>([]);
const query = reactive<{
  page: number;
  size: number;
  orderNo: string;
  channel?: Channel;
  shopId?: string;
  payRange: [Date, Date] | null;
  kolCode: string;
  orderStatus?: OrderStatus;
  attributionStatus?: AttributionStatus;
}>({
  page: 1,
  size: readPageSize("orders", 20),
  orderNo: "",
  payRange: null,
  kolCode: ""
});

function asOrder(row: object): OrderVO {
  return row as OrderVO;
}

/** Shop cell per 09.04: "name (code)"; the code comes with task 37's backend and may be null. */
function shopText(order: OrderVO): string {
  if (!order.shopName && !order.shopCode) return "-";
  return order.shopCode
    ? formatNameWithCode(order.shopName ?? "-", order.shopCode)
    : (order.shopName ?? "-");
}

/** Creator cell per 09.04: "name (code)"; "-" while the order is not attributed to a creator. */
function kolText(order: OrderVO): string {
  if (!order.kolId && !order.kolCode) return "-";
  return order.kolCode
    ? formatNameWithCode(order.kolName ?? order.kolCode, order.kolCode)
    : (order.kolName ?? "-");
}

const zhWidth = computed(() => useLocaleStore().locale === "zh-CN");
const px = (zh: number, en: number): number => (zhWidth.value ? zh : en);

const columns = computed<DataColumn[]>(() => [
  { key: "weidianOrderId", label: t("order.orderId"), width: 150 },
  // L-002: channel, freight and create time are secondary — hidden by default so pay time and the
  // core columns stay clear of the pinned action column at 1440 (column settings can show them).
  // The en minWidths of shop/creator/statuses sit at the measured header minimums so the visible
  // set (1148px) fits the 1158px card (task 59 browser measurement).
  { key: "channel", label: t("order.channel"), width: 80, defaultHidden: true },
  { key: "shopName", label: t("order.shop"), minWidth: px(170, 162) },
  { key: "kol", label: t("order.kol"), minWidth: px(170, 160) },
  { key: "orderStatus", label: t("order.orderStatus"), width: px(110, 116) },
  { key: "refundStatus", label: t("order.refundStatusColumn"), width: px(120, 126) },
  { key: "payAmount", label: t("order.payAmount"), width: 100, amount: true },
  {
    key: "freight",
    label: t("order.freight"),
    width: px(80, 90),
    amount: true,
    defaultHidden: true
  },
  { key: "payTime", label: t("order.payTime"), width: px(170, 166) },
  { key: "createTime", label: t("order.createTime"), width: 170, defaultHidden: true },
  { key: "actions", label: t("common.actions"), width: px(104, 128) }
]);

const applied = ref(snapshot());

function snapshot() {
  return {
    orderNo: query.orderNo.trim(),
    channel: query.channel,
    shopId: query.shopId,
    payRange: query.payRange,
    kolCode: query.kolCode.trim(),
    orderStatus: query.orderStatus,
    attributionStatus: query.attributionStatus
  };
}

function appliedQuery(): OrderQuery {
  return {
    orderNo: applied.value.orderNo || undefined,
    channel: applied.value.channel,
    shopId: applied.value.shopId || undefined,
    ...payRangeToQuery(applied.value.payRange),
    kolCode: applied.value.kolCode || undefined,
    orderStatus: applied.value.orderStatus,
    attributionStatus: applied.value.attributionStatus
  };
}

const chips = computed<SearchChip[]>(() => {
  const list: SearchChip[] = [];
  if (applied.value.orderNo) {
    list.push({ key: "orderNo", label: t("order.orderId"), text: applied.value.orderNo });
  }
  if (applied.value.channel) {
    list.push({
      key: "channel",
      label: t("order.channel"),
      text: channelText(applied.value.channel)
    });
  }
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
const detailOrderId = ref<string | null>(null);
const detailKolId = ref<string | null>(null);
const kolVisible = ref(false);

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
  query.orderNo = "";
  query.channel = undefined;
  query.shopId = undefined;
  query.payRange = null;
  query.kolCode = "";
  query.orderStatus = undefined;
  query.attributionStatus = undefined;
  search();
}

function removeChip(key: string): void {
  if (key === "orderNo") query.orderNo = "";
  if (key === "channel") query.channel = undefined;
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

function openDetail(order: OrderVO) {
  detailOrderId.value = order.id;
  detailVisible.value = true;
}

function openKol(order: OrderVO) {
  if (!abilities.value.canViewKol || !order.kolId) return;
  detailKolId.value = order.kolId;
  kolVisible.value = true;
}

/** Unattributed orders have no chain: the whole path starts at the creator's item (09.04). */
function chainClickable(order: OrderVO): boolean {
  return order.kolId != null;
}

function openChain(order: OrderVO) {
  if (!chainClickable(order)) return;
  // R-17: open inside the tab system like every other cross-page jump — window.open left the
  // chain page outside the tabs, so the user could not tab back to the order list.
  void router.push({ path: `/order/orders/${order.id}/chain` });
}

function rowActions(order: OrderVO): RowAction[] {
  return [
    {
      key: "view",
      label: t("order.actionView"),
      testId: "action-view",
      onClick: () => openDetail(order)
    },
    {
      key: "chain",
      label: t("order.actionChain"),
      testId: "action-chain",
      disabled: !chainClickable(order),
      tooltip: chainClickable(order) ? undefined : t("order.chainNoKol"),
      onClick: () => openChain(order)
    }
  ];
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
        <el-form-item :label="t('order.orderId')">
          <el-input
            v-model="query.orderNo"
            clearable
            maxlength="32"
            :placeholder="t('order.orderId')"
            data-test="filter-order-no"
          />
        </el-form-item>
        <el-form-item :label="t('order.channel')">
          <el-select
            v-model="query.channel"
            clearable
            :placeholder="t('common.all')"
            data-test="filter-channel"
          >
            <el-option
              v-for="channel in CHANNELS"
              :key="channel"
              :label="channelText(channel)"
              :value="channel"
            />
          </el-select>
        </el-form-item>
        <el-form-item :label="t('order.shop')">
          <el-select
            v-model="query.shopId"
            clearable
            :placeholder="t('common.all')"
            data-test="filter-shop"
          >
            <el-option v-for="s in shops" :key="s.shopId" :label="s.shopName" :value="s.shopId" />
          </el-select>
        </el-form-item>
      </template>
      <template #more>
        <el-form-item :label="t('order.payRange')">
          <el-date-picker
            v-model="query.payRange"
            type="datetimerange"
            :start-placeholder="t('order.payRange')"
            :end-placeholder="t('order.payRange')"
            class="filter-w-lg"
          />
        </el-form-item>
        <el-form-item :label="t('order.kolCode')">
          <el-input v-model="query.kolCode" clearable maxlength="16" />
        </el-form-item>
        <el-form-item :label="t('order.orderStatus')">
          <el-select
            v-model="query.orderStatus"
            clearable
            :placeholder="t('common.all')"
            class="filter-w-sm"
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
            class="filter-w-sm"
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
      <template #cell-channel="{ row }">{{ channelText(asOrder(row).channel) }}</template>
      <template #cell-shopName="{ row }">{{ shopText(asOrder(row)) }}</template>
      <template #cell-kol="{ row }">
        <el-button
          v-if="asOrder(row).kolId && abilities.canViewKol"
          link
          type="primary"
          data-test="order-kol"
          @click="openKol(asOrder(row))"
        >
          {{ kolText(asOrder(row)) }}
        </el-button>
        <span v-else>{{ kolText(asOrder(row)) }}</span>
      </template>
      <template #cell-orderStatus="{ row }">
        {{ orderStatusText(asOrder(row).orderStatus) }}
      </template>
      <template #cell-refundStatus="{ row }">
        <el-tag :type="orderRefundTagType(asOrder(row).refundStatus)" data-test="refund-status">
          {{ orderRefundStatusText(asOrder(row).refundStatus) }}
        </el-tag>
      </template>
      <template #cell-payAmount="{ row }">{{ asOrder(row).payAmount ?? "-" }}</template>
      <template #cell-freight="{ row }">{{ asOrder(row).freight ?? "-" }}</template>
      <template #cell-payTime="{ row }">{{ formatInstant(asOrder(row).payTime) }}</template>
      <template #cell-createTime="{ row }">{{ formatInstant(asOrder(row).createTime) }}</template>
      <template #actions="{ row }">
        <RowActions :actions="rowActions(asOrder(row))" />
      </template>
      <template #expand="{ row }">
        <OrderLines :order="asOrder(row)" />
      </template>
    </DataTable>

    <SyncStatesDialog v-model="syncVisible" @synced="load" />

    <ImportDialog
      accept-csv
      require-data-cutoff
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
      require-data-cutoff
      v-model="refundImportVisible"
      :title="t('order.refundImportTitle')"
      :limit-hint="t('order.importLimitHint')"
      :template-file-name="t('order.refundTemplateFile')"
      task-type="REFUND_IMPORT"
      :download-template="downloadRefundImportTemplate"
      :upload="uploadRefundImport"
      @finished="search"
    />

    <OrderDetailDrawer v-model="detailVisible" :order-id="detailOrderId" />
    <KolDetailDrawer v-model="kolVisible" :kol-id="detailKolId" />
  </div>
</template>
