<script setup lang="ts">
import { computed, ref, watch } from "vue";
import { useI18n } from "vue-i18n";
import { getRefund } from "@/api/order/order";
import type { RefundDetailVO, RefundItemVO } from "@/api/types";
import CopyText from "@/components/CopyText.vue";
import DataTable, { type DataColumn } from "@/components/DataTable.vue";
import { formatInstant, formatNameWithCode } from "@/utils/format";
import { notifyError } from "@/utils/notify";
import { channelText, refundStatusText, refundTagType, refundTypeText } from "@/utils/order";

defineOptions({ name: "RefundDetailDrawer" });

const props = defineProps<{ modelValue: boolean; refundId: string | null }>();
const emit = defineEmits<{ (event: "update:modelValue", value: boolean): void }>();

const { t } = useI18n();

function shopLabel(name: string | null | undefined, code: string | null | undefined): string {
  if (!name && !code) return "-";
  return code ? formatNameWithCode(name ?? "-", code) : (name ?? "-");
}

function kolLabel(name: string | null | undefined, code: string | null | undefined): string {
  if (!name && !code) return "-";
  return code ? formatNameWithCode(name ?? code, code) : (name ?? "-");
}
const loading = ref(false);
const detail = ref<RefundDetailVO | null>(null);

watch(
  () => [props.modelValue, props.refundId] as const,
  async ([open, id]) => {
    if (!open || !id) return;
    loading.value = true;
    detail.value = null;
    try {
      detail.value = await getRefund(id);
    } catch (error) {
      notifyError(error);
      emit("update:modelValue", false);
    } finally {
      loading.value = false;
    }
  }
);

function asLine(row: object): RefundItemVO {
  return row as RefundItemVO;
}

const columns = computed<DataColumn[]>(() => [
  { key: "image", label: t("order.detail.image"), width: 70 },
  {
    key: "itemName",
    label: t("order.detail.itemName"),
    prop: "itemName",
    minWidth: 160,
    tooltip: true
  },
  { key: "itemId", label: t("order.line.itemId"), width: 120 },
  { key: "skuName", label: t("order.line.sku"), prop: "skuName", minWidth: 100 },
  { key: "kol", label: t("order.kol"), minWidth: 140 },
  { key: "quantity", label: t("order.line.quantity"), width: 70 },
  { key: "refundAmount", label: t("order.refund.amount"), width: 100, amount: true }
]);
</script>

<template>
  <el-drawer
    :model-value="modelValue"
    :title="t('order.refund.detailTitle')"
    size="60%"
    @update:model-value="emit('update:modelValue', $event)"
  >
    <div v-loading="loading">
      <template v-if="detail">
        <el-descriptions :column="2" border data-test="refund-detail">
          <el-descriptions-item :label="t('order.refund.no')">
            <CopyText :value="detail.refundNo" />
          </el-descriptions-item>
          <el-descriptions-item :label="t('order.refund.orderNo')">
            <CopyText :value="detail.orderNo" />
          </el-descriptions-item>
          <el-descriptions-item :label="t('order.channel')">
            {{ channelText(detail.channel) }}
          </el-descriptions-item>
          <el-descriptions-item :label="t('order.shop')">
            {{ shopLabel(detail.shopName, detail.shopCode) }}
          </el-descriptions-item>
          <el-descriptions-item :label="t('order.kol')">
            <span v-if="detail.kolCode">{{ kolLabel(detail.kolName, detail.kolCode) }}</span>
            <span v-else>-</span>
          </el-descriptions-item>
          <el-descriptions-item :label="t('order.detail.merchantNo')">
            {{ detail.merchantNo ?? "-" }}
          </el-descriptions-item>
          <el-descriptions-item :label="t('order.refund.typeColumn')">
            {{ refundTypeText(detail.refundType) }}
          </el-descriptions-item>
          <el-descriptions-item :label="t('order.refund.statusColumn')">
            <el-tag :type="refundTagType(detail.refundStatus)">
              {{ refundStatusText(detail.refundStatus) }}
            </el-tag>
          </el-descriptions-item>
          <el-descriptions-item :label="t('order.refund.amount')">
            {{ detail.amount }}
          </el-descriptions-item>
          <el-descriptions-item :label="t('order.refund.applyTime')">
            {{ formatInstant(detail.applyTime) }}
          </el-descriptions-item>
          <el-descriptions-item :label="t('order.refund.finishTime')">
            {{ formatInstant(detail.finishTime) }}
          </el-descriptions-item>
        </el-descriptions>
        <h4 class="title">{{ t("order.refund.items") }}</h4>
        <DataTable
          plain
          table-id="refund-detail-items"
          :rows="detail.lines"
          :columns="columns"
          :total="detail.lines.length"
          :paged="false"
        >
          <template #cell-image="{ row }">
            <el-image
              v-if="asLine(row).imageUrl"
              class="item-image"
              :src="asLine(row).imageUrl ?? ''"
              :preview-src-list="[asLine(row).imageUrl ?? '']"
              fit="cover"
              preview-teleported
              alt=""
              data-test="refund-item-image"
            />
            <span v-else>-</span>
          </template>
          <template #cell-itemId="{ row }">{{ asLine(row).weidianItemId }}</template>
          <template #cell-skuName="{ row }">{{ asLine(row).skuName ?? "-" }}</template>
          <template #cell-kol="{ row }">
            <span v-if="asLine(row).kolCode">{{
              kolLabel(asLine(row).kolName, asLine(row).kolCode)
            }}</span>
            <span v-else>-</span>
          </template>
          <template #cell-quantity="{ row }">{{ asLine(row).quantity ?? "-" }}</template>
          <template #cell-refundAmount="{ row }">{{ asLine(row).refundAmount ?? "-" }}</template>
        </DataTable>
      </template>
    </div>
  </el-drawer>
</template>

<style scoped>
.title {
  margin: 20px 0 8px;
}
.item-image {
  width: 40px;
  height: 40px;
  border-radius: var(--tcrm-radius-tag);
}
</style>
