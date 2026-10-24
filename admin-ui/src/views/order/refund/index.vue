<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from "vue";
import { useI18n } from "vue-i18n";
import { useRoute } from "vue-router";
import { listRefunds, type RefundQuery } from "@/api/order/order";
import type { Channel, PageResult, RefundStatus, RefundType, RefundVO } from "@/api/types";
import CopyText from "@/components/CopyText.vue";
import DataTable, { type DataColumn } from "@/components/DataTable.vue";
import ListSearch, { type SearchChip } from "@/components/ListSearch.vue";
import RowActions, { type RowAction } from "@/components/RowActions.vue";
import { useLocaleStore } from "@/stores/locale";
import { formatInstant, formatNameWithCode } from "@/utils/format";
import { notifyError } from "@/utils/notify";
import {
  CHANNELS,
  REFUND_STATUSES,
  REFUND_TYPES,
  channelText,
  refundStatusText,
  refundTagType,
  refundTypeText
} from "@/utils/order";
import { readPageSize } from "@/utils/table-prefs";
import OrderDetailDrawer from "@/views/order/OrderDetailDrawer.vue";
import RefundDetailDrawer from "./RefundDetailDrawer.vue";

defineOptions({ name: "OrderRefundPage" });

const route = useRoute();

/**
 * Refund query (requirement 09.04, task 40 §3): type and status are two separate columns and
 * "refunding" never reads as a completed refund (08.02). The original order number opens the
 * shared order detail drawer.
 */
const { t } = useI18n();

const loading = ref(false);
const rows = ref<RefundVO[]>([]);
const total = ref(0);
const query = reactive<{
  page: number;
  size: number;
  refundNo: string;
  orderNo: string;
  channel?: Channel;
  refundStatus?: RefundStatus;
  refundType?: RefundType;
}>({ page: 1, size: readPageSize("order-refunds", 20), refundNo: "", orderNo: "" });

function asRefund(row: object): RefundVO {
  return row as RefundVO;
}

function shopText(refund: RefundVO): string {
  if (!refund.shopName && !refund.shopCode) return "-";
  return refund.shopCode
    ? formatNameWithCode(refund.shopName ?? "-", refund.shopCode)
    : (refund.shopName ?? "-");
}

function kolText(refund: RefundVO): string {
  if (!refund.kolCode && !refund.kolName) return "-";
  return refund.kolCode
    ? formatNameWithCode(refund.kolName ?? refund.kolCode, refund.kolCode)
    : (refund.kolName ?? "-");
}

const zhWidth = computed(() => useLocaleStore().locale === "zh-CN");
const px = (zh: number, en: number): number => (zhWidth.value ? zh : en);

const columns = computed<DataColumn[]>(() => [
  { key: "refundNo", label: t("order.refund.no"), width: 150 },
  { key: "orderNo", label: t("order.refund.orderNo"), width: 150 },
  // Owner decision 2026-10-24 (task 59 review): the 08.02 columns all show by default; the table
  // scrolls horizontally inside the card when it overflows (pinned action column, scrollbar on).
  { key: "channel", label: t("order.channel"), width: 80 },
  { key: "shop", label: t("order.shop"), minWidth: 170 },
  { key: "kol", label: t("order.kol"), minWidth: 170 },
  { key: "refundType", label: t("order.refund.typeColumn"), width: px(100, 130) },
  { key: "refundStatus", label: t("order.refund.statusColumn"), width: px(90, 110) },
  { key: "amount", label: t("order.refund.amount"), width: 100, amount: true },
  { key: "applyTime", label: t("order.refund.applyTime"), width: 170 },
  { key: "finishTime", label: t("order.refund.finishTime"), width: 170 },
  // View: zh canvas 28px / en 57px + 24px headroom (task 59 R-11 measurements).
  { key: "actions", label: t("common.actions"), width: px(70, 84) }
]);

const applied = ref(snapshot());

function snapshot() {
  return {
    refundNo: query.refundNo.trim(),
    orderNo: query.orderNo.trim(),
    channel: query.channel,
    refundStatus: query.refundStatus,
    refundType: query.refundType
  };
}

function appliedQuery(): RefundQuery {
  return {
    refundNo: applied.value.refundNo || undefined,
    orderNo: applied.value.orderNo || undefined,
    channel: applied.value.channel,
    refundStatus: applied.value.refundStatus,
    refundType: applied.value.refundType
  };
}

const chips = computed<SearchChip[]>(() => {
  const list: SearchChip[] = [];
  if (applied.value.refundNo) {
    list.push({ key: "refundNo", label: t("order.refund.no"), text: applied.value.refundNo });
  }
  if (applied.value.orderNo) {
    list.push({ key: "orderNo", label: t("order.refund.orderNo"), text: applied.value.orderNo });
  }
  if (applied.value.channel) {
    list.push({
      key: "channel",
      label: t("order.channel"),
      text: channelText(applied.value.channel)
    });
  }
  if (applied.value.refundStatus) {
    list.push({
      key: "refundStatus",
      label: t("order.refund.statusColumn"),
      text: refundStatusText(applied.value.refundStatus)
    });
  }
  if (applied.value.refundType) {
    list.push({
      key: "refundType",
      label: t("order.refund.typeColumn"),
      text: refundTypeText(applied.value.refundType)
    });
  }
  return list;
});

const detailVisible = ref(false);
const detailRefundId = ref<string | null>(null);
const orderVisible = ref(false);
const orderDetailId = ref<string | null>(null);

async function load() {
  loading.value = true;
  try {
    const page: PageResult<RefundVO> = await listRefunds({
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

function search() {
  query.page = 1;
  applied.value = snapshot();
  void load();
}

function reset() {
  query.refundNo = "";
  query.orderNo = "";
  query.channel = undefined;
  query.refundStatus = undefined;
  query.refundType = undefined;
  search();
}

function removeChip(key: string): void {
  if (key === "refundNo") query.refundNo = "";
  if (key === "orderNo") query.orderNo = "";
  if (key === "channel") query.channel = undefined;
  if (key === "refundStatus") query.refundStatus = undefined;
  if (key === "refundType") query.refundType = undefined;
  search();
}

function onPage(page: number): void {
  query.page = page;
  void load();
}

function onSize(size: number): void {
  query.size = size;
}

function openDetail(refund: RefundVO) {
  detailRefundId.value = refund.id;
  detailVisible.value = true;
}

function openOrder(refund: RefundVO) {
  if (!refund.orderId) return;
  orderDetailId.value = refund.orderId;
  orderVisible.value = true;
}

function rowActions(refund: RefundVO): RowAction[] {
  return [
    {
      key: "view",
      label: t("order.actionView"),
      testId: "refund-view",
      onClick: () => openDetail(refund)
    }
  ];
}

/**
 * The order chain pushes ?refundNo=… here (task 59); the tab may already be open and kept alive,
 * so watch the route instead of reading it once on mount.
 */
watch(
  () => route.query.refundNo,
  refundNo => {
    const wanted = typeof refundNo === "string" ? refundNo.trim() : "";
    if (!wanted || wanted === query.refundNo) return;
    query.refundNo = wanted;
    search();
  },
  { immediate: true }
);

onMounted(() => {
  // The immediate route watch above already searched when a refundNo came with the URL.
  if (!query.refundNo) void load();
});
</script>

<template>
  <div class="list-page">
    <ListSearch :chips="chips" has-more @search="search" @reset="reset" @remove="removeChip">
      <template #primary>
        <el-form-item :label="t('order.refund.no')">
          <el-input
            v-model="query.refundNo"
            clearable
            maxlength="32"
            :placeholder="t('order.refund.no')"
            data-test="filter-refund-no"
          />
        </el-form-item>
        <el-form-item :label="t('order.refund.orderNo')">
          <el-input
            v-model="query.orderNo"
            clearable
            maxlength="32"
            :placeholder="t('order.refund.orderNo')"
            data-test="filter-refund-order-no"
          />
        </el-form-item>
        <el-form-item :label="t('order.channel')">
          <el-select
            v-model="query.channel"
            clearable
            :placeholder="t('common.all')"
            data-test="filter-refund-channel"
          >
            <el-option
              v-for="channel in CHANNELS"
              :key="channel"
              :label="channelText(channel)"
              :value="channel"
            />
          </el-select>
        </el-form-item>
      </template>
      <template #more>
        <el-form-item :label="t('order.refund.statusColumn')">
          <el-select
            v-model="query.refundStatus"
            clearable
            :placeholder="t('common.all')"
            data-test="filter-refund-status"
          >
            <el-option
              v-for="status in REFUND_STATUSES"
              :key="status"
              :label="refundStatusText(status)"
              :value="status"
            />
          </el-select>
        </el-form-item>
        <el-form-item :label="t('order.refund.typeColumn')">
          <el-select
            v-model="query.refundType"
            clearable
            :placeholder="t('common.all')"
            data-test="filter-refund-type"
          >
            <el-option
              v-for="type in REFUND_TYPES"
              :key="type"
              :label="refundTypeText(type)"
              :value="type"
            />
          </el-select>
        </el-form-item>
      </template>
    </ListSearch>

    <DataTable
      table-id="order-refunds"
      :rows="rows"
      :columns="columns"
      :loading="loading"
      :total="total"
      :page="query.page"
      :size="query.size"
      :filter-count="chips.length"
      @update:page="onPage"
      @update:size="onSize"
      @refresh="load"
      @clear-filters="reset"
    >
      <template #cell-refundNo="{ row }">
        <CopyText :value="asRefund(row).refundNo" />
      </template>
      <template #cell-orderNo="{ row }">
        <el-button
          link
          type="primary"
          data-test="refund-order-link"
          @click="openOrder(asRefund(row))"
        >
          {{ asRefund(row).orderNo }}
        </el-button>
      </template>
      <template #cell-channel="{ row }">{{ channelText(asRefund(row).channel) }}</template>
      <template #cell-shop="{ row }">{{ shopText(asRefund(row)) }}</template>
      <template #cell-kol="{ row }">{{ kolText(asRefund(row)) }}</template>
      <template #cell-refundType="{ row }">{{ refundTypeText(asRefund(row).refundType) }}</template>
      <template #cell-refundStatus="{ row }">
        <el-tag :type="refundTagType(asRefund(row).refundStatus)" data-test="refund-status-tag">
          {{ refundStatusText(asRefund(row).refundStatus) }}
        </el-tag>
      </template>
      <template #cell-amount="{ row }">{{ asRefund(row).amount }}</template>
      <template #cell-applyTime="{ row }">{{ formatInstant(asRefund(row).applyTime) }}</template>
      <template #cell-finishTime="{ row }">{{ formatInstant(asRefund(row).finishTime) }}</template>
      <template #actions="{ row }">
        <RowActions :actions="rowActions(asRefund(row))" />
      </template>
    </DataTable>

    <RefundDetailDrawer v-model="detailVisible" :refund-id="detailRefundId" />
    <OrderDetailDrawer v-model="orderVisible" :order-id="orderDetailId" />
  </div>
</template>
