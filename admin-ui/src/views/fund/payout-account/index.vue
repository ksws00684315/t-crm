<script setup lang="ts">
import NameCode from "@/components/NameCode.vue";
import { computed, onMounted, reactive, ref, watch } from "vue";
import { useI18n } from "vue-i18n";
import { useRoute, useRouter } from "vue-router";
import { listPayoutAccounts, type PayoutAccountRowVO } from "@/api/commission/payout-account";
import CopyText from "@/components/CopyText.vue";
import DataTable, { type DataColumn } from "@/components/DataTable.vue";
import ListSearch, { type SearchChip } from "@/components/ListSearch.vue";
import RowActions, { type RowAction } from "@/components/RowActions.vue";
import { usePayoutPendingStore } from "@/stores/payout-pending";
import { useUserStore } from "@/stores/user";
import { formatInstant } from "@/utils/format";
import { notifyError } from "@/utils/notify";
import { accessOf } from "@/utils/permission";
import { readPageSize } from "@/utils/table-prefs";
import ReviewDrawer from "./ReviewDrawer.vue";

defineOptions({ name: "PayoutAccountReviewPage" });

const { t } = useI18n();
const route = useRoute();
const router = useRouter();
const me = useUserStore();
const payoutPending = usePayoutPendingStore();

/** The detail drawer with the full account needs the review permission (task 15b §4.2.3). */
const canReview = computed(() => accessOf(me.user).can("payout-account:review"));

const loading = ref(false);
const rows = ref<PayoutAccountRowVO[]>([]);
const total = ref(0);
const query = reactive<{
  page: number;
  size: number;
  status: "PENDING" | "ALL";
  type?: "PAYPAL" | "USDT";
  kolCode: string;
}>({ page: 1, size: readPageSize("payout-accounts", 20), status: "PENDING", kolCode: "" });

function asRow(row: object): PayoutAccountRowVO {
  return row as PayoutAccountRowVO;
}

const columns = computed<DataColumn[]>(() => [
  { key: "kolCode", align: "left" as const, label: t("payout.kol"), width: 170 },
  { key: "type", label: t("payout.type"), width: 120 },
  {
    key: "accountMasked",
    align: "left" as const,
    label: t("payout.account"),
    prop: "accountMasked",
    minWidth: 150
  },
  {
    key: "holderName",
    align: "left" as const,
    label: t("payout.holder"),
    prop: "holderName",
    minWidth: 110
  },
  { key: "status", label: t("common.status"), width: 100 },
  { key: "defaultAccount", label: t("payout.defaultColumn"), width: 80 },
  { key: "lastSubmittedAt", label: t("payout.submittedAt"), width: 170 },
  { key: "reviewedTime", label: t("payout.reviewedAt"), width: 170 },
  ...(canReview.value
    ? [
        {
          key: "actions",
          label: t("common.actions"),
          // Single Detail action via RowActions (R-11): en canvas 63px + 24px headroom.
          width: 90
        }
      ]
    : [])
]);

const applied = ref<{
  status: "PENDING" | "ALL";
  type?: "PAYPAL" | "USDT";
  kolCode: string;
}>({ status: "PENDING", kolCode: "" });

const chips = computed<SearchChip[]>(() => {
  const list: SearchChip[] = [];
  if (applied.value.status === "ALL") {
    list.push({ key: "status", label: t("common.status"), text: t("common.all") });
  }
  if (applied.value.type) {
    list.push({
      key: "type",
      label: t("payout.type"),
      text: t(`payout.typeOption.${applied.value.type}`)
    });
  }
  if (applied.value.kolCode) {
    list.push({ key: "kolCode", label: t("payout.kolCode"), text: applied.value.kolCode });
  }
  return list;
});

const drawerVisible = ref(false);
const detailId = ref<string | null>(null);

function statusType(
  status: PayoutAccountRowVO["status"]
): "success" | "danger" | "warning" | "info" {
  if (status === "APPROVED") return "success";
  if (status === "REJECTED") return "danger";
  if (status === "DISABLED") return "info";
  return "warning";
}

async function load() {
  loading.value = true;
  try {
    const page = await listPayoutAccounts({
      page: query.page,
      size: query.size,
      status: applied.value.status,
      type: applied.value.type,
      kolCode: applied.value.kolCode || undefined
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
  applied.value = {
    status: query.status,
    type: query.type,
    kolCode: query.kolCode.trim()
  };
  void load();
}

function reset() {
  query.status = "PENDING";
  query.type = undefined;
  query.kolCode = "";
  search();
}

function removeChip(key: string): void {
  if (key === "status") query.status = "PENDING";
  if (key === "type") query.type = undefined;
  if (key === "kolCode") query.kolCode = "";
  search();
}

function onPage(page: number): void {
  query.page = page;
  void load();
}

function onSize(size: number): void {
  query.size = size;
}

/** Row actions via RowActions (norm 11 §2); the column only renders with the review permission. */
function rowActions(row: PayoutAccountRowVO): RowAction[] {
  return [
    {
      key: "detail",
      label: t("common.detail"),
      testId: "detail",
      onClick: () => openDetail(row)
    }
  ];
}

function openDetail(row: PayoutAccountRowVO) {
  detailId.value = row.id;
  drawerVisible.value = true;
}

/**
 * Closing the drawer also drops `?id=` from the URL. A push to an identical URL is not a
 * navigation, so without this the same notification link could never reopen the drawer.
 */
function onDrawerVisible(visible: boolean): void {
  drawerVisible.value = visible;
  if (visible) return;
  detailId.value = null;
  if (route.query.id !== undefined) {
    const rest = { ...route.query };
    delete rest.id;
    void router.replace({ query: rest });
  }
}

/** Entering the page refreshes the pending count that feeds the menu badge. */
function afterReview(): void {
  void load();
  void payoutPending.refresh();
}

onMounted(() => {
  void load();
  void payoutPending.refresh();
});

// Task 20 links here with ?id={accountId}; open that account's detail right away, also when
// the query changes while the page is already open (or kept alive in a background tab).
watch(
  () => route.query.id,
  id => {
    const wanted = typeof id === "string" ? id : "";
    if (wanted && canReview.value && wanted !== detailId.value) {
      detailId.value = wanted;
      drawerVisible.value = true;
    }
  },
  { immediate: true }
);
</script>

<template>
  <div class="list-page">
    <ListSearch :chips="chips" @search="search" @reset="reset" @remove="removeChip">
      <template #primary>
        <el-form-item :label="t('common.status')">
          <el-select v-model="query.status" style="width: 130px">
            <el-option :label="t('payout.status.PENDING')" value="PENDING" />
            <el-option :label="t('common.all')" value="ALL" />
          </el-select>
        </el-form-item>
        <el-form-item :label="t('payout.kolCode')">
          <el-input
            v-model="query.kolCode"
            clearable
            maxlength="16"
            data-test="kol-code"
            style="width: 160px"
          />
        </el-form-item>
        <el-form-item :label="t('payout.type')">
          <el-select
            v-model="query.type"
            clearable
            :placeholder="t('common.all')"
            style="width: 130px"
          >
            <el-option :label="t('payout.typeOption.PAYPAL')" value="PAYPAL" />
            <el-option :label="t('payout.typeOption.USDT')" value="USDT" />
          </el-select>
        </el-form-item>
      </template>
    </ListSearch>

    <DataTable
      table-id="payout-accounts"
      fill-available-height
      row-key="id"
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
      <template #cell-kolCode="{ row }">
        <NameCode :name="asRow(row).kolName" :code="asRow(row).kolCode"
          ><template #code><CopyText :value="asRow(row).kolCode" /></template
        ></NameCode>
      </template>
      <template #cell-type="{ row }">
        {{ t(`payout.typeOption.${asRow(row).accountType}`) }}
        <span v-if="asRow(row).network" class="kol-name">· {{ asRow(row).network }}</span>
      </template>
      <template #cell-status="{ row }">
        <el-tag :type="statusType(asRow(row).status)">
          {{ t(`payout.status.${asRow(row).status}`) }}
        </el-tag>
      </template>
      <template #cell-defaultAccount="{ row }">
        {{ asRow(row).defaultAccount ? t("payout.defaultColumn") : "-" }}
      </template>
      <template #cell-lastSubmittedAt="{ row }">{{
        formatInstant(asRow(row).lastSubmittedAt)
      }}</template>
      <template #cell-reviewedTime="{ row }">{{ formatInstant(asRow(row).reviewedTime) }}</template>
      <!-- The actions column itself only exists with the review permission. -->
      <template #actions="{ row }">
        <RowActions :actions="rowActions(asRow(row))" />
      </template>
    </DataTable>

    <ReviewDrawer
      v-if="canReview"
      :model-value="drawerVisible"
      :account-id="detailId"
      @update:model-value="onDrawerVisible"
      @reviewed="afterReview"
    />
  </div>
</template>

<style scoped>
.kol-name {
  color: var(--tcrm-color-text-secondary);
}
</style>
