<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { useI18n } from "vue-i18n";
import DataTable, { type DataColumn } from "@/components/DataTable.vue";
import ListSearch from "@/components/ListSearch.vue";
import CopyText from "@/components/CopyText.vue";
import {
  listSettlementLedger,
  type SettlementLedgerRow,
  type SettlementLedger
} from "@/api/commission/finance";
import { listShops } from "@/api/product/shop";
import type { ShopOptionVO } from "@/api/types";
import { dates, validDates } from "@/views/analytics/board";
import { formatAmountWithCurrency, formatInstant, formatNameWithCode } from "@/utils/format";
import { notifyError } from "@/utils/notify";
import { useUserStore } from "@/stores/user";

defineOptions({ name: "SettlementDetailsPage" });
const { t } = useI18n();
const user = useUserStore();
const canFilterShop = computed(() => user.can("product:view"));
const text = (key: string) => t(`finance.settlementLedger.${key}`);
const rangeOf = (days: number): [string, string] => {
  const { startDate, endDate } = dates(days);
  return [startDate, endDate];
};
const range = ref<[string, string] | null>(rangeOf(7));
const shopId = ref<string>();
const shops = ref<ShopOptionVO[]>([]);
const applied = ref({ ...dates(7), shopId: undefined as string | undefined });
const ledger = ref<SettlementLedger>();
const page = ref(1);
const size = ref(20);
const loading = ref(false);
const failed = ref(false);
const invalidRange = ref(false);
let request = 0;

const columns = computed<DataColumn[]>(() => [
  { key: "occurredAt", label: text("time"), minWidth: 155, align: "left" },
  { key: "tradeType", label: text("tradeType"), minWidth: 100 },
  { key: "amount", label: text("amount"), minWidth: 115, amount: true },
  { key: "billType", label: text("billType"), minWidth: 135, align: "left" },
  { key: "balance", label: text("balance"), minWidth: 110, amount: true },
  { key: "orderNo", label: t("finance.orderNo"), minWidth: 160, align: "left" },
  { key: "creator", label: text("creator"), minWidth: 155, align: "left" },
  { key: "commissionAmount", label: text("commission"), minWidth: 115, amount: true }
]);
const presets = [7, 30, 90] as const;
const selectedPreset = computed(() =>
  presets.find(days => {
    const [startDate, endDate] = rangeOf(days);
    return startDate === range.value?.[0] && endDate === range.value?.[1];
  })
);
function amount(value: string | number | null | undefined): string {
  if (value == null) return "—";
  const [whole, decimals = ""] = String(value).split(".");
  return formatAmountWithCurrency(`${whole}.${decimals.padEnd(2, "0")}`);
}
function signedAmount(value: SettlementLedgerRow["amount"]): string {
  return (Number(value) > 0 ? "+" : "") + amount(value);
}
function tradeType(row: SettlementLedgerRow): string {
  if (row.tradeType === "收入") return text("income");
  if (row.tradeType === "支出") return text("expense");
  return row.tradeType || "—";
}
async function load() {
  const current = ++request;
  loading.value = true;
  failed.value = false;
  try {
    const result = await listSettlementLedger({
      ...applied.value,
      page: page.value,
      size: size.value
    });
    if (current === request) ledger.value = result;
  } catch (error) {
    if (current !== request) return;
    failed.value = true;
    ledger.value = undefined;
    notifyError(error);
  } finally {
    if (current === request) loading.value = false;
  }
}
function search() {
  const [startDate, endDate] = range.value ?? ["", ""];
  invalidRange.value = !validDates({ startDate, endDate }) || endDate > dates(1).endDate;
  if (invalidRange.value) return;
  applied.value = { startDate, endDate, shopId: shopId.value || undefined };
  page.value = 1;
  void load();
}
function selectPeriod(days: number) {
  range.value = rangeOf(days);
  search();
}
function reset() {
  shopId.value = undefined;
  selectPeriod(7);
}
function onPage(value: number) {
  page.value = value;
  void load();
}
function onSize(value: number) {
  size.value = value;
  page.value = 1;
  void load();
}
onMounted(() => {
  void load();
  if (canFilterShop.value)
    void listShops()
      .then(result => {
        shops.value = result;
      })
      .catch(notifyError);
});
</script>

<template>
  <div class="settlement-ledger" data-test="settlement-page">
    <h1>{{ t("route.settlementDetails") }}</h1>
    <section class="settlement-ledger__wallet" :aria-label="text('accountOverview')">
      <div class="settlement-ledger__heading">
        <span>{{ text("settledGoods") }}</span>
        <span class="settlement-ledger__muted">{{ text("asOfNow") }}</span>
      </div>
      <strong data-test="wallet-settled">{{ amount(ledger?.settledGoodsAmount) }}</strong>
      <div class="settlement-ledger__secondary">
        <div>
          <span>{{ text("pending") }}</span>
          <b data-test="wallet-pending">{{ amount(ledger?.pendingAmount) }}</b>
        </div>
        <div>
          <span>{{ text("platformFee") }}</span>
          <b data-test="wallet-platform-fee">{{ amount(ledger?.platformFeeAmount) }}</b>
        </div>
      </div>
    </section>

    <ListSearch hide-title @search="search" @reset="reset">
      <template #primary>
        <el-form-item :label="text('period')">
          <el-date-picker
            v-model="range"
            type="daterange"
            value-format="YYYY-MM-DD"
            :start-placeholder="text('startDate')"
            :end-placeholder="text('endDate')"
            :clearable="false"
          />
        </el-form-item>
        <el-form-item :label="text('recent')">
          <el-button-group>
            <el-button
              v-for="days in presets"
              :key="days"
              :data-test="`period-${days}`"
              :type="selectedPreset === days ? 'primary' : 'default'"
              plain
              :aria-pressed="selectedPreset === days"
              @click="selectPeriod(days)"
            >
              {{ text(`days${days}`) }}
            </el-button>
          </el-button-group>
        </el-form-item>
        <el-form-item v-if="canFilterShop" :label="t('finance.shopId')">
          <el-select
            v-model="shopId"
            clearable
            filterable
            class="filter-w-md"
            data-test="settlement-shop-id"
            :placeholder="t('common.all')"
          >
            <el-option
              v-for="shop in shops"
              :key="shop.id"
              :label="formatNameWithCode(shop.shopName, shop.shopCode)"
              :value="shop.id"
            />
          </el-select>
        </el-form-item>
      </template>
    </ListSearch>
    <el-alert v-if="invalidRange" :title="text('invalidRange')" type="warning" :closable="false" />

    <DataTable
      table-id="shop-settlement-ledger"
      scroll-with-page
      :rows="ledger?.activity.items ?? []"
      :columns="columns"
      :total="Number(ledger?.activity.total ?? 0)"
      :page="page"
      :size="size"
      :loading="loading"
      :failed="failed"
      @refresh="load"
      @retry="load"
      @update:page="onPage"
      @update:size="onSize"
    >
      <template #title
        ><h2>{{ text("transactions") }}</h2></template
      >
      <template #cell-occurredAt="{ row }">{{ formatInstant(row.occurredAt) }}</template>
      <template #cell-tradeType="{ row }">{{ tradeType(row) }}</template>
      <template #cell-amount="{ row }">
        <span
          :class="{
            'settlement-ledger__credit': Number(row.amount) > 0,
            'settlement-ledger__debit': Number(row.amount) < 0
          }"
          >{{ signedAmount(row.amount) }}</span
        >
      </template>
      <template #cell-billType="{ row }">{{ row.billType || "—" }}</template>
      <template #cell-balance="{ row }">{{ amount(row.balance) }}</template>
      <template #cell-orderNo="{ row }">
        <CopyText v-if="row.orderNo" :value="row.orderNo" />
        <span v-else>—</span>
      </template>
      <template #cell-creator="{ row }">
        {{
          row.creatorName
            ? formatNameWithCode(row.creatorName, row.creatorCode)
            : row.creatorCode || "—"
        }}
      </template>
      <template #cell-commissionAmount="{ row }">{{ amount(row.commissionAmount) }}</template>
    </DataTable>
  </div>
</template>

<style scoped>
.settlement-ledger {
  display: grid;
  gap: var(--tcrm-space-4);
}
.settlement-ledger h1,
.settlement-ledger h2,
.settlement-ledger p {
  margin: 0;
}
.settlement-ledger__wallet {
  display: grid;
  gap: var(--tcrm-space-2);
  padding: var(--tcrm-space-5);
  background: var(--tcrm-color-card);
  border: 1px solid var(--tcrm-color-border);
  border-radius: var(--tcrm-radius-card);
}
.settlement-ledger__heading,
.settlement-ledger__secondary > div {
  display: flex;
  align-items: baseline;
  flex-wrap: wrap;
  gap: var(--tcrm-space-2);
}
.settlement-ledger__heading {
  justify-content: space-between;
}
.settlement-ledger__wallet strong {
  font-size: var(--tcrm-font-size-metric, 28px);
  font-variant-numeric: tabular-nums;
}
.settlement-ledger__secondary {
  display: flex;
  flex-wrap: wrap;
  column-gap: var(--tcrm-space-6, 24px);
  row-gap: var(--tcrm-space-2);
  padding-top: var(--tcrm-space-3);
  border-top: 1px solid var(--tcrm-color-border);
}
.settlement-ledger__muted {
  color: var(--tcrm-color-text-secondary);
}
.settlement-ledger__credit {
  color: var(--tcrm-color-success);
}
.settlement-ledger__debit {
  color: var(--tcrm-color-danger);
}
</style>
