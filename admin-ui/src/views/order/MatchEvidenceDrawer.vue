<script setup lang="ts">
import NameCode from "@/components/NameCode.vue";
import { computed, ref, watch } from "vue";
import { useI18n } from "vue-i18n";
import { getMatchEvidence } from "@/api/order/order";
import type { EvidenceMappingVO, MatchEvidenceVO, OrderItemVO, OrderVO } from "@/api/types";
import DataTable, { type DataColumn } from "@/components/DataTable.vue";
import { useDescriptionColumns } from "@/composables/useDescriptionColumns";
import { formatInstant, formatNameWithCode } from "@/utils/format";
import { notifyError } from "@/utils/notify";
import { attributionTypeText } from "@/utils/order";

defineOptions({ name: "MatchEvidenceDrawer" });

const props = defineProps<{ modelValue: boolean; order: OrderVO | null }>();
const emit = defineEmits<{
  (event: "update:modelValue", value: boolean): void;
  /** The viewer asked for the existing "assign creator" flow on this order (task 40 §6). */
  (event: "assign", order: OrderVO): void;
}>();

const { t } = useI18n();
const detailRegion = ref<HTMLElement | null>(null);
const detailColumns = useDescriptionColumns(detailRegion);

/**
 * Matching-pool evidence (requirement 10.01, task 40 §6). Orders can carry several unmatched
 * lines; the viewer picks the line, the drawer shows that line's Weidian item, the creator-item
 * mappings that share it (pay-time coverage highlighted), the source product and the manual
 * history, and offers the existing assign-creator action.
 */
const loading = ref(false);
const evidence = ref<MatchEvidenceVO | null>(null);
const selectedItemId = ref<string | null>(null);

const lines = computed<OrderItemVO[]>(() => props.order?.items ?? []);

const selectedLine = computed(
  () => lines.value.find(line => line.id === selectedItemId.value) ?? null
);

watch(
  () => [props.modelValue, props.order] as const,
  ([open]) => {
    if (!open) return;
    evidence.value = null;
    selectedItemId.value = lines.value[0]?.id ?? null;
    if (selectedItemId.value) void loadEvidence(selectedItemId.value);
  },
  { immediate: true }
);

watch(selectedItemId, id => {
  if (props.modelValue && id) void loadEvidence(id);
});

async function loadEvidence(orderItemId: string) {
  loading.value = true;
  evidence.value = null;
  try {
    evidence.value = await getMatchEvidence(orderItemId);
  } catch (error) {
    notifyError(error);
  } finally {
    loading.value = false;
  }
}

function asMapping(row: object): EvidenceMappingVO {
  return row as EvidenceMappingVO;
}

function reasonText(reason: MatchEvidenceVO["unmatchedReason"] | undefined): string {
  return reason ? t(`order.pool.reasonOption.${reason}`) : "-";
}

const mappingColumns = computed<DataColumn[]>(() => [
  { key: "kol", align: "left" as const, label: t("order.kol"), minWidth: 150 },
  { key: "linkCode", align: "left" as const, label: t("order.chain.linkCode"), width: 120 },
  { key: "valid", label: t("order.pool.effectiveRange"), width: 300 },
  { key: "covers", label: t("order.pool.coversPayTime"), width: 130 }
]);
</script>

<template>
  <el-drawer
    :model-value="modelValue"
    :title="t('order.pool.evidenceTitle')"
    size="720px"
    @update:model-value="emit('update:modelValue', $event)"
  >
    <div ref="detailRegion" v-loading="loading">
      <template v-if="order">
        <el-form label-position="top" @submit.prevent>
          <el-form-item :label="t('order.pool.evidenceLine')">
            <el-select v-model="selectedItemId" data-test="evidence-line" style="width: 100%">
              <el-option
                v-for="line in lines"
                :key="line.id"
                :label="`${line.itemName ?? t('order.detail.itemName')} · ${line.weidianItemId}`"
                :value="line.id"
              />
            </el-select>
          </el-form-item>
        </el-form>

        <template v-if="evidence">
          <el-descriptions :column="detailColumns" border data-test="evidence-basic">
            <el-descriptions-item :label="t('order.line.itemId')" :span="detailColumns">
              {{ evidence.weidianItemId }}
            </el-descriptions-item>
            <el-descriptions-item :label="t('order.shop')" :span="detailColumns">
              {{ evidence.shopName ?? "-" }}
            </el-descriptions-item>
            <el-descriptions-item :label="t('order.detail.merchantNo')" :span="detailColumns">
              {{ evidence.merchantNo ?? "-" }}
            </el-descriptions-item>
            <el-descriptions-item :label="t('order.detail.itemName')" :span="detailColumns">
              {{ evidence.itemName ?? "-" }}{{ evidence.skuName ? ` · ${evidence.skuName}` : "" }}
            </el-descriptions-item>
            <el-descriptions-item :label="t('order.payTime')">
              {{ formatInstant(evidence.payTime) }}
            </el-descriptions-item>
            <el-descriptions-item :label="t('order.pool.unmatchedReason')">
              {{ reasonText(evidence.unmatchedReason) }}
            </el-descriptions-item>
            <el-descriptions-item :label="t('order.pool.sourceProduct')" :span="detailColumns">
              <span v-if="evidence.product">
                {{
                  formatNameWithCode(
                    evidence.product.productName ?? "-",
                    evidence.product.productId
                  )
                }}
              </span>
              <span v-else>-</span>
            </el-descriptions-item>
          </el-descriptions>

          <h4 class="title">{{ t("order.pool.mappings") }}</h4>
          <DataTable
            plain
            table-id="evidence-mappings"
            :rows="evidence.mappings"
            :columns="mappingColumns"
            :total="evidence.mappings.length"
            :paged="false"
          >
            <template #cell-kol="{ row }">
              <span v-if="asMapping(row).kolCode"
                ><NameCode :name="asMapping(row).kolName" :code="asMapping(row).kolCode"
              /></span>
              <span v-else>-</span>
            </template>
            <template #cell-linkCode="{ row }">{{ asMapping(row).linkCode ?? "-" }}</template>
            <template #cell-valid="{ row }">
              {{ formatInstant(asMapping(row).validFrom) }} ~
              {{ formatInstant(asMapping(row).validTo) }}
            </template>
            <template #cell-covers="{ row }">
              <el-tag
                :type="asMapping(row).coversPayTime ? 'warning' : 'info'"
                :class="{ 'evidence-mapping--covers': asMapping(row).coversPayTime }"
                data-test="covers-pay-time"
              >
                {{
                  asMapping(row).coversPayTime
                    ? t("order.pool.coversYes")
                    : t("order.pool.coversNo")
                }}
              </el-tag>
            </template>
          </DataTable>

          <h4 class="title">{{ t("order.pool.manualRecords") }}</h4>
          <el-descriptions
            v-if="evidence.manual"
            :column="detailColumns"
            border
            data-test="evidence-manual"
          >
            <el-descriptions-item :label="t('order.line.attribution')" :span="detailColumns">
              {{ attributionTypeText(evidence.manual.attributionType) }}
            </el-descriptions-item>
            <el-descriptions-item :label="t('order.pool.reason')" :span="detailColumns">
              {{ evidence.manual.manualReason ?? "-" }}
            </el-descriptions-item>
            <el-descriptions-item :label="t('audit.time')">
              {{ formatInstant(evidence.manual.attributionTime) }}
            </el-descriptions-item>
          </el-descriptions>
          <div v-else class="empty">-</div>
        </template>
      </template>
    </div>
    <template #footer>
      <el-button
        v-if="selectedLine"
        type="primary"
        data-test="evidence-assign"
        @click="order && emit('assign', order)"
      >
        {{ t("order.pool.assign") }}
      </el-button>
      <el-button @click="emit('update:modelValue', false)">{{ t("common.close") }}</el-button>
    </template>
  </el-drawer>
</template>

<style scoped>
.title {
  margin: 20px 0 8px;
}
.empty {
  color: var(--tcrm-color-text-secondary);
}
.evidence-mapping--covers {
  outline: 2px solid var(--el-color-warning-light-5);
}
</style>
