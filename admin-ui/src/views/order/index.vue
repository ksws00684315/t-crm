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
import type { OrderItemVO, OrderStatus, OrderSyncStateVO, OrderVO, PageResult } from "@/api/types";
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
import { orderRefundStatusText, orderRefundTagType, orderTimeRangeToQuery } from "@/utils/order";
import { readPageSize } from "@/utils/table-prefs";
import OrderDetailDrawer from "./OrderDetailDrawer.vue";
import SyncStatesDialog from "./SyncStatesDialog.vue";

defineOptions({ name: "OrderListPage" });

const { t } = useI18n();
const router = useRouter();
const me = useUserStore();
const abilities = computed(() => orderAbilities(me.user));

type OrderFilterStatus = OrderStatus | "REFUNDING" | "REFUND_SUCCESS" | "ABNORMAL";

interface GroupedOrderRow {
  id: string;
  kind: "header" | "item";
  order: OrderVO;
  item: OrderItemVO | null;
  itemIndex: number;
  itemCount: number;
}

const loading = ref(false);
const rows = ref<OrderVO[]>([]);
const total = ref(0);
const shops = ref<OrderSyncStateVO[]>([]);
const query = reactive<{
  page: number;
  size: number;
  orderNo: string;
  shopId?: string;
  kolKeyword: string;
  status?: OrderFilterStatus;
  orderTimeRange: [Date, Date] | null;
}>({
  page: 1,
  size: readPageSize("orders", 20),
  orderNo: "",
  kolKeyword: "",
  orderTimeRange: null
});

/** Shop cell per 09.04: "name (code)"; the code comes with task 37's backend and may be null. */
function shopText(order: OrderVO): string {
  if (!order.shopName && !order.shopCode) return "-";
  return order.shopCode
    ? formatNameWithCode(order.shopName ?? "-", order.shopCode)
    : (order.shopName ?? "-");
}

const zhWidth = computed(() => useLocaleStore().locale === "zh-CN");
const px = (zh: number, en: number): number => (zhWidth.value ? zh : en);

const columns = computed<DataColumn[]>(() => [
  { key: "product", label: t("order.line.itemName"), minWidth: px(300, 320) },
  { key: "quantity", label: t("order.line.quantity"), width: px(90, 100) },
  { key: "orderStatus", label: t("order.orderStatus"), width: px(180, 200) },
  { key: "payAmount", label: t("order.payAmount"), width: px(140, 160), amount: true },
  { key: "actions", label: t("common.actions"), width: px(104, 128) }
]);

const statusOptions = computed(() => [
  { value: "UNPAID", label: t("order.listStatus.UNPAID") },
  { value: "PAID", label: t("order.listStatus.PAID") },
  { value: "SHIPPED", label: t("order.listStatus.SHIPPED") },
  { value: "RECEIVED", label: t("order.listStatus.RECEIVED") },
  { value: "COMPLETED", label: t("order.listStatus.COMPLETED") },
  { value: "CLOSED", label: t("order.listStatus.CLOSED") },
  { value: "REFUNDING", label: t("order.listStatus.REFUNDING") },
  { value: "REFUND_SUCCESS", label: t("order.listStatus.REFUND_SUCCESS") },
  { value: "ABNORMAL", label: t("order.listStatus.ABNORMAL") }
]);

const tableRows = computed<GroupedOrderRow[]>(() =>
  rows.value.flatMap(order => {
    const items = order.items ?? [];
    const itemCount = Math.max(items.length, 1);
    return [
      {
        id: `header:${order.id}`,
        kind: "header" as const,
        order,
        item: null,
        itemIndex: -1,
        itemCount
      },
      ...(items.length
        ? items.map((item, itemIndex) => ({
            id: `item:${order.id}:${item.id}`,
            kind: "item" as const,
            order,
            item,
            itemIndex,
            itemCount
          }))
        : [
            {
              id: `item:${order.id}:empty`,
              kind: "item" as const,
              order,
              item: null,
              itemIndex: 0,
              itemCount
            }
          ])
    ];
  })
);

function orderSpanMethod({
  row,
  column,
  columnIndex,
  visibleColumnCount
}: {
  row: object;
  column: { columnKey?: string; property?: string };
  columnIndex: number;
  visibleColumnCount: number;
}): number[] | undefined {
  const grouped = row as GroupedOrderRow;
  if (grouped.kind === "header") return columnIndex === 0 ? [1, visibleColumnCount] : [0, 0];
  const key = column.columnKey ?? column.property;
  if (!["orderStatus", "payAmount", "actions"].includes(key ?? "")) return undefined;
  return grouped.itemIndex === 0 ? [grouped.itemCount, 1] : [0, 0];
}

function tableRowClass({ row }: { row: object }): string {
  return (row as GroupedOrderRow).kind === "header" ? "order-group-header" : "order-group-item";
}

function orderFilterStatusText(status: OrderFilterStatus): string {
  const option = statusOptions.value.find(item => item.value === status);
  return option?.label ?? status;
}

function orderListStatus(order: OrderVO): string {
  if (
    order.orderStatus === "UNKNOWN" ||
    order.attributionStatus === "UNMATCHED" ||
    order.attributionStatus === "CONFLICT"
  ) {
    return t("order.listStatus.ABNORMAL");
  }
  return t(`order.listStatus.${order.orderStatus}`);
}

const applied = ref(snapshot());

function snapshot() {
  return {
    orderNo: query.orderNo.trim(),
    shopId: query.shopId,
    kolKeyword: query.kolKeyword.trim(),
    status: query.status,
    orderTimeRange: query.orderTimeRange
  };
}

/**
 * Task 70 (B01-B04): the six standard statuses stay on `orderStatus`; the refunding and
 * refund-success options map onto `refundState` (REFUND_SUCCESS becomes REFUNDED and covers
 * partial and whole refunds) and the abnormal option maps onto `abnormal=true`.
 */
function appliedQuery(): OrderQuery {
  const status = applied.value.status;
  const params: OrderQuery = {
    orderNo: applied.value.orderNo || undefined,
    shopId: applied.value.shopId || undefined,
    kolKeyword: applied.value.kolKeyword || undefined,
    ...orderTimeRangeToQuery(applied.value.orderTimeRange)
  };
  if (status === "REFUNDING" || status === "REFUND_SUCCESS") {
    params.refundState = status === "REFUNDING" ? "REFUNDING" : "REFUNDED";
  } else if (status === "ABNORMAL") {
    params.abnormal = true;
  } else if (status) {
    params.orderStatus = status;
  }
  return params;
}

const chips = computed<SearchChip[]>(() => {
  const list: SearchChip[] = [];
  if (applied.value.orderNo) {
    list.push({ key: "orderNo", label: t("order.orderId"), text: applied.value.orderNo });
  }
  if (applied.value.shopId) {
    const shop = shops.value.find(item => item.shopId === applied.value.shopId);
    list.push({
      key: "shop",
      label: t("order.shop"),
      text: shop?.shopName ?? applied.value.shopId
    });
  }
  if (applied.value.kolKeyword) {
    list.push({
      key: "kolKeyword",
      label: t("order.kolKeyword"),
      text: applied.value.kolKeyword
    });
  }
  if (applied.value.status) {
    list.push({
      key: "status",
      label: t("order.orderStatus"),
      text: orderFilterStatusText(applied.value.status)
    });
  }
  if (applied.value.orderTimeRange) {
    const [from, to] = applied.value.orderTimeRange;
    list.push({
      key: "orderTimeRange",
      label: t("order.orderTime"),
      text: `${formatInstant(from.toISOString())} ~ ${formatInstant(to.toISOString())}`
    });
  }
  return list;
});

const syncVisible = ref(false);
const orderImportVisible = ref(false);
const refundImportVisible = ref(false);
const detailVisible = ref(false);
const detailOrderId = ref<string | null>(null);

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
    rows.value = [];
    total.value = 0;
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
  query.shopId = undefined;
  query.kolKeyword = "";
  query.status = undefined;
  query.orderTimeRange = null;
  search();
}

function removeChip(key: string): void {
  if (key === "orderNo") query.orderNo = "";
  if (key === "shop") query.shopId = undefined;
  if (key === "kolKeyword") query.kolKeyword = "";
  if (key === "status") query.status = undefined;
  if (key === "orderTimeRange") query.orderTimeRange = null;
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
        <el-form-item :label="t('order.kolKeyword')">
          <el-input
            v-model="query.kolKeyword"
            clearable
            maxlength="128"
            :placeholder="t('order.kolKeyword')"
            data-test="filter-kol-keyword"
          />
        </el-form-item>
      </template>
      <template #more>
        <el-form-item :label="t('order.orderStatus')">
          <el-select
            v-model="query.status"
            clearable
            :placeholder="t('common.all')"
            class="filter-w-sm"
            data-test="filter-order-status"
          >
            <el-option
              v-for="status in statusOptions"
              :key="status.value"
              :label="status.label"
              :value="status.value"
            />
          </el-select>
        </el-form-item>
        <el-form-item :label="t('order.orderTime')">
          <el-date-picker
            v-model="query.orderTimeRange"
            type="datetimerange"
            :start-placeholder="t('order.orderTime')"
            :end-placeholder="t('order.orderTime')"
            class="filter-w-lg"
            data-test="filter-order-time"
          />
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
      :rows="tableRows"
      :columns="columns"
      :loading="loading"
      :total="total"
      :page="query.page"
      :size="query.size"
      :filter-count="chips.length"
      row-key="id"
      :fixed-columns="false"
      :span-method="orderSpanMethod"
      :row-class-name="tableRowClass"
      @update:page="onPage"
      @update:size="onSize"
      @refresh="load"
      @clear-filters="reset"
    >
      <template #cell-product="{ row }">
        <div v-if="(row as GroupedOrderRow).kind === 'header'" class="order-group-band">
          <span class="order-group-band__field">
            <strong>{{ t("order.orderId") }}</strong>
            <CopyText :value="(row as GroupedOrderRow).order.weidianOrderId" />
          </span>
          <span class="order-group-band__field">
            <strong>{{ t("order.detail.orderTime") }}</strong>
            {{ formatInstant((row as GroupedOrderRow).order.orderTime) }}
          </span>
          <span class="order-group-band__field">
            <strong>{{ t("order.shop") }}</strong>
            {{ shopText((row as GroupedOrderRow).order) }}
          </span>
        </div>
        <div v-else class="order-product-row">
          <div class="order-image-placeholder" :aria-label="t('order.itemImageDeveloping')">
            <span class="order-image-placeholder__icon" aria-hidden="true">▧</span>
            <span>{{ t("common.developing") }}</span>
          </div>
          <div class="order-product-row__text">
            <strong>{{ (row as GroupedOrderRow).item?.itemName ?? "-" }}</strong>
            <span
              >{{ t("order.line.itemId") }}:
              {{ (row as GroupedOrderRow).item?.weidianItemId ?? "-" }}</span
            >
            <span v-if="(row as GroupedOrderRow).item?.skuName"
              >{{ t("order.line.sku") }}: {{ (row as GroupedOrderRow).item?.skuName }}</span
            >
          </div>
        </div>
      </template>
      <template #cell-quantity="{ row }">
        {{
          (row as GroupedOrderRow).kind === "header"
            ? ""
            : ((row as GroupedOrderRow).item?.quantity ?? "-")
        }}
      </template>
      <template #cell-orderStatus="{ row }">
        <template v-if="(row as GroupedOrderRow).kind === 'item'">
          <el-tag
            :type="
              (row as GroupedOrderRow).order.orderStatus === 'UNKNOWN' ||
              ['UNMATCHED', 'CONFLICT'].includes((row as GroupedOrderRow).order.attributionStatus)
                ? 'danger'
                : 'info'
            "
          >
            {{ orderListStatus((row as GroupedOrderRow).order) }}
          </el-tag>
          <el-tag
            v-if="
              (row as GroupedOrderRow).order.refundStatus &&
              (row as GroupedOrderRow).order.refundStatus !== 'NONE'
            "
            :type="orderRefundTagType((row as GroupedOrderRow).order.refundStatus)"
            data-test="refund-status"
          >
            {{ orderRefundStatusText((row as GroupedOrderRow).order.refundStatus) }}
          </el-tag>
        </template>
      </template>
      <template #cell-payAmount="{ row }">
        {{
          (row as GroupedOrderRow).kind === "header"
            ? ""
            : (row as GroupedOrderRow).order.payAmount || "-"
        }}
      </template>
      <template #actions="{ row }">
        <RowActions
          v-if="(row as GroupedOrderRow).kind === 'item'"
          :actions="rowActions((row as GroupedOrderRow).order)"
        />
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
  </div>
</template>

<style scoped>
:deep(.el-table .order-group-header > td) {
  background: var(--el-color-primary-light-9) !important;
}

:deep(.el-table .order-group-header .cell) {
  padding-top: 10px;
  padding-bottom: 10px;
}

.order-group-band {
  display: flex;
  flex-wrap: wrap;
  gap: var(--tcrm-space-5);
  align-items: center;
  min-width: 0;
}

.order-group-band__field {
  display: inline-flex;
  flex-wrap: wrap;
  gap: var(--tcrm-space-2);
  align-items: center;
  min-width: 0;
}

.order-group-band__field strong {
  color: var(--tcrm-color-text-secondary);
  font-weight: 500;
}

.order-product-row {
  display: flex;
  gap: var(--tcrm-space-3);
  align-items: center;
  min-width: 0;
}

.order-image-placeholder {
  display: flex;
  flex: 0 0 80px;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  width: 80px;
  height: 64px;
  overflow: hidden;
  border-radius: var(--tcrm-radius-tag);
  background: var(--el-fill-color-light);
  color: var(--el-text-color-secondary);
  font-size: var(--tcrm-font-size-help);
  line-height: var(--tcrm-line-height-help);
  text-align: center;
}

.order-image-placeholder__icon {
  font-size: var(--tcrm-font-size-title);
  line-height: 20px;
}

.order-product-row__text {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
  overflow-wrap: anywhere;
}

.order-product-row__text strong {
  font-weight: 500;
}

.order-product-row__text > span {
  color: var(--tcrm-color-text-secondary);
  font-size: var(--tcrm-font-size-help);
}

.order-group-item :deep(.el-tag) + :deep(.el-tag) {
  margin-left: 6px;
}
</style>
