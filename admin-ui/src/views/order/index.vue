<script setup lang="ts">
import { Picture } from "@element-plus/icons-vue";
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
import { orderRefundStatusText } from "@/utils/order";
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
  kolCode: string;
  status?: OrderFilterStatus;
  createRange: [Date, Date] | null;
}>({
  page: 1,
  size: readPageSize("orders", 20),
  orderNo: "",
  kolCode: "",
  createRange: null
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
  { key: "product", label: t("order.productInfo"), minWidth: px(400, 420) },
  { key: "quantity", label: t("order.line.quantity"), width: px(72, 80) },
  { key: "orderStatus", label: t("order.orderStatus"), width: px(160, 190) },
  { key: "payAmount", label: t("order.actualPaidAmount"), width: px(120, 140), amount: true },
  { key: "actions", label: t("common.actions"), width: px(104, 128) }
]);

const statusOptions = computed(() => [
  { value: "UNPAID", label: t("order.listStatus.UNPAID"), developing: false },
  { value: "PAID", label: t("order.listStatus.PAID"), developing: false },
  { value: "SHIPPED", label: t("order.listStatus.SHIPPED"), developing: false },
  { value: "RECEIVED", label: t("order.listStatus.RECEIVED"), developing: false },
  { value: "COMPLETED", label: t("order.listStatus.COMPLETED"), developing: false },
  { value: "CLOSED", label: t("order.listStatus.CLOSED"), developing: false },
  { value: "REFUNDING", label: t("order.listStatus.REFUNDING"), developing: true },
  { value: "REFUND_SUCCESS", label: t("order.listStatus.REFUND_SUCCESS"), developing: true },
  { value: "ABNORMAL", label: t("order.listStatus.ABNORMAL"), developing: true }
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

function orderStatusTone(order: OrderVO): string {
  if (
    order.orderStatus === "UNKNOWN" ||
    order.attributionStatus === "UNMATCHED" ||
    order.attributionStatus === "CONFLICT"
  ) {
    return "danger";
  }
  if (["RECEIVED", "COMPLETED"].includes(order.orderStatus)) return "success";
  if (["PAID", "SHIPPED"].includes(order.orderStatus)) return "primary";
  return "muted";
}

const applied = ref(snapshot());

function snapshot() {
  return {
    orderNo: query.orderNo.trim(),
    shopId: query.shopId,
    kolCode: query.kolCode.trim(),
    status: query.status,
    createRange: query.createRange
  };
}

function appliedQuery(): OrderQuery {
  const status = applied.value.status;
  return {
    orderNo: applied.value.orderNo || undefined,
    shopId: applied.value.shopId || undefined,
    kolCode: applied.value.kolCode || undefined,
    orderStatus:
      status && ["UNPAID", "PAID", "SHIPPED", "RECEIVED", "COMPLETED", "CLOSED"].includes(status)
        ? (status as OrderStatus)
        : undefined
  };
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
  if (applied.value.kolCode) {
    list.push({ key: "kolCode", label: t("order.kolCode"), text: applied.value.kolCode });
  }
  if (applied.value.status) {
    list.push({
      key: "status",
      label: t("order.orderStatus"),
      text: orderFilterStatusText(applied.value.status)
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
  query.kolCode = "";
  query.status = undefined;
  query.createRange = null;
  search();
}

function removeChip(key: string): void {
  if (key === "orderNo") query.orderNo = "";
  if (key === "shop") query.shopId = undefined;
  if (key === "kolCode") query.kolCode = "";
  if (key === "status") query.status = undefined;
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
  <div class="list-page order-list-page">
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
        <el-form-item :label="t('order.kolCode')">
          <el-tooltip :content="t('order.kolNameFilterDeveloping')" placement="top">
            <el-input
              v-model="query.kolCode"
              clearable
              maxlength="16"
              :placeholder="t('order.kolCode')"
              data-test="filter-kol-code"
            />
          </el-tooltip>
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
              :disabled="status.developing"
            >
              <span>{{ status.label }}</span>
              <span v-if="status.developing" class="order-filter-developing">{{
                t("common.developing")
              }}</span>
            </el-option>
          </el-select>
        </el-form-item>
        <el-tooltip :content="t('order.createTimeFilterDeveloping')" placement="top">
          <el-form-item :label="t('order.createTimeFilterLabel')">
            <el-date-picker
              v-model="query.createRange"
              type="datetimerange"
              disabled
              :start-placeholder="t('order.detail.orderTime')"
              :end-placeholder="t('order.detail.orderTime')"
              class="filter-w-lg"
              data-test="filter-create-time"
            />
          </el-form-item>
        </el-tooltip>
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
      class="order-list-table"
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
            <span>{{ t("order.orderId") }}</span>
            <CopyText :value="(row as GroupedOrderRow).order.weidianOrderId" />
          </span>
          <span class="order-group-band__field">
            <span>{{ t("order.detail.orderTime") }}</span>
            {{ formatInstant((row as GroupedOrderRow).order.orderTime) }}
          </span>
          <span class="order-group-band__field">
            <span>{{ t("order.shop") }}</span>
            {{ shopText((row as GroupedOrderRow).order) }}
          </span>
        </div>
        <div v-else class="order-product-row">
          <div class="order-image-placeholder" :aria-label="t('order.itemImageDeveloping')">
            <el-icon class="order-image-placeholder__icon" aria-hidden="true"><Picture /></el-icon>
            <span>{{ t("common.developing") }}</span>
          </div>
          <div class="order-product-row__text">
            <span class="order-product-row__name">{{
              (row as GroupedOrderRow).item?.itemName ?? "-"
            }}</span>
            <div class="order-product-row__meta">
              <span
                >{{ t("order.line.itemId") }}:
                {{ (row as GroupedOrderRow).item?.weidianItemId ?? "-" }}</span
              >
              <span v-if="(row as GroupedOrderRow).item?.skuName"
                >{{ t("order.line.sku") }}: {{ (row as GroupedOrderRow).item?.skuName }}</span
              >
            </div>
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
        <div v-if="(row as GroupedOrderRow).kind === 'item'" class="order-statuses">
          <span
            class="order-status"
            :class="`order-status--${orderStatusTone((row as GroupedOrderRow).order)}`"
          >
            {{ orderListStatus((row as GroupedOrderRow).order) }}
          </span>
          <span
            v-if="
              (row as GroupedOrderRow).order.refundStatus &&
              (row as GroupedOrderRow).order.refundStatus !== 'NONE'
            "
            class="order-refund-status"
            data-test="refund-status"
          >
            {{ orderRefundStatusText((row as GroupedOrderRow).order.refundStatus) }}
          </span>
        </div>
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
.order-filter-developing {
  margin-left: var(--tcrm-space-2);
  color: var(--el-text-color-secondary);
  font-size: var(--tcrm-font-size-help);
}

:deep(.el-table .order-group-header > td) {
  /* The approved order groups replace shared stripe/hover fills on this page only. */
  background: color-mix(in srgb, var(--tcrm-color-primary) 2%, var(--tcrm-color-card)) !important;
  border-top: var(--tcrm-space-4) solid var(--tcrm-color-card);
  border-bottom: none;
  background-clip: padding-box;
}

:deep(.order-list-table .el-table .order-group-header > td.el-table__cell) {
  /* Metadata bands use inner cell spacing rather than stacking shared density padding. */
  padding-block: 0;
}

:deep(.el-table .order-group-header .cell) {
  padding-block: var(--tcrm-space-2);
}

:deep(.order-list-table .el-table th.el-table__cell) {
  /* Owner-approved 1027 mockup: regular headers, scoped exception to global 600. */
  font-weight: 400 !important;
  color: var(--tcrm-color-text-secondary);
}

:deep(.el-table .order-group-item > td.el-table__cell) {
  /* Keep merged order cells on the same white surface as every product row. */
  background: var(--tcrm-color-card) !important;
  padding-block: var(--tcrm-space-4);
}

:deep(.data-table.density-small .order-group-item > td.el-table__cell) {
  padding-block: var(--tcrm-space-2);
}

:deep(.data-table.density-large .order-group-item > td.el-table__cell) {
  padding-block: var(--tcrm-space-5);
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

.order-group-band__field > span:first-child {
  color: var(--tcrm-color-text-secondary);
}

.order-group-band__field,
.order-product-row__name {
  font-weight: 400;
}

.order-product-row {
  display: flex;
  gap: var(--tcrm-space-4);
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
  height: 80px;
  overflow: hidden;
  border-radius: var(--tcrm-radius-tag);
  background: var(--el-fill-color-light);
  color: var(--el-text-color-secondary);
  font-size: var(--tcrm-font-size-help);
  line-height: var(--tcrm-line-height-help);
  text-align: center;
}

.order-image-placeholder__icon {
  width: var(--tcrm-space-5);
  height: var(--tcrm-space-5);
}

.order-image-placeholder__icon svg {
  width: 100%;
  height: 100%;
}

.order-product-row__text {
  display: flex;
  flex-direction: column;
  gap: var(--tcrm-space-2);
  min-width: 0;
  overflow-wrap: anywhere;
}

.order-product-row__meta {
  display: flex;
  flex-wrap: wrap;
  gap: var(--tcrm-space-1) var(--tcrm-space-4);
  color: var(--tcrm-color-text-secondary);
}

.order-statuses {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: var(--tcrm-space-2);
  font-weight: 400;
}

.order-status--primary {
  color: var(--tcrm-color-primary-text);
}

.order-status--success {
  color: var(--tcrm-color-success);
}

.order-status--muted {
  color: var(--tcrm-color-text-secondary);
}

.order-refund-status {
  color: var(--tcrm-color-warning);
}

.order-status--danger {
  color: var(--tcrm-color-danger);
}

@media (max-width: 1439px) {
  :deep(.order-list-table .order-group-item > td[rowspan]:not([rowspan="1"])) {
    /* Keep shared order information readable when only the first product fits above the pager. */
    vertical-align: top;
  }
}
</style>
