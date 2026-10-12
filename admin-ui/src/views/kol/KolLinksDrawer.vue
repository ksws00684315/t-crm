<script setup lang="ts">
import { computed, reactive, ref, watch } from "vue";
import { useI18n } from "vue-i18n";
import { listLinks } from "@/api/link/link";
import type { LinkVO } from "@/api/types";
import CopyText from "@/components/CopyText.vue";
import DataTable, { type DataColumn } from "@/components/DataTable.vue";
import { notifyError } from "@/utils/notify";
import { readPageSize } from "@/utils/table-prefs";

defineOptions({ name: "KolLinksDrawer" });

const { t } = useI18n();

const props = defineProps<{ modelValue: boolean; kolCode: string | null; kolName?: string }>();
const emit = defineEmits<{ (event: "update:modelValue", value: boolean): void }>();

const title = computed(() => {
  const code = props.kolCode ?? "";
  if (props.kolName) return t("link.titleWithName", { code, name: props.kolName });
  return t("link.title", { code });
});

const loading = ref(false);
const rows = ref<LinkVO[]>([]);
const total = ref(0);
const query = reactive({ page: 1, size: readPageSize("kol-links", 20), productCode: "" });

function asLink(row: object): LinkVO {
  return row as LinkVO;
}

const columns = computed<DataColumn[]>(() => [
  { key: "productCode", label: t("link.productCode"), width: 140, mono: true },
  { key: "productName", label: t("link.productName"), prop: "productName", minWidth: 120 },
  { key: "weidianItemId", label: t("link.exclusiveId"), width: 150, mono: true },
  { key: "linkUrl", label: t("link.trackingLink"), minWidth: 260, mono: true },
  { key: "status", label: t("common.status"), width: 150 },
  { key: "clicks", label: t("link.clicks"), width: 130 }
]);

async function load() {
  if (!props.kolCode) return;
  loading.value = true;
  try {
    const page = await listLinks({
      kolCode: props.kolCode,
      productCode: query.productCode.trim() || undefined,
      page: query.page,
      size: query.size
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
  void load();
}

function reset() {
  query.productCode = "";
  search();
}

function onPage(page: number): void {
  query.page = page;
  void load();
}

function onSize(size: number): void {
  query.size = size;
}

watch(
  () => [props.modelValue, props.kolCode] as const,
  ([open, code]) => {
    if (!open || !code) return;
    rows.value = [];
    total.value = 0;
    query.productCode = "";
    search();
  }
);

function reasonText(row: LinkVO): string {
  return row.unavailableReasons
    .map(reason => {
      if (reason === "KOL_DISABLED") return t("link.reasonKolDisabled");
      if (reason === "PRODUCT_OFF_SHELF") return t("link.reasonOffShelf");
      if (reason === "SHOP_DISABLED") return t("link.reasonShopDisabled");
      return reason;
    })
    .join(t("link.separator"));
}
</script>

<template>
  <el-drawer
    :model-value="modelValue"
    :title="title"
    size="960px"
    @update:model-value="emit('update:modelValue', $event)"
  >
    <el-form :inline="true" :model="query" @submit.prevent="search">
      <el-form-item :label="t('link.productCode')">
        <el-input
          v-model="query.productCode"
          clearable
          maxlength="32"
          :placeholder="t('link.exact')"
          data-test="link-product-code"
        />
      </el-form-item>
      <el-form-item>
        <el-button type="primary" data-test="link-search" native-type="submit">{{
          t("common.search")
        }}</el-button>
        <el-button native-type="button" @click="reset">{{ t("common.reset") }}</el-button>
      </el-form-item>
    </el-form>

    <DataTable
      plain
      table-id="kol-links"
      row-key="kolItemId"
      :rows="rows"
      :columns="columns"
      :loading="loading"
      :total="total"
      :page="query.page"
      :size="query.size"
      @update:page="onPage"
      @update:size="onSize"
      @refresh="load"
    >
      <template #cell-productCode="{ row }">
        <CopyText :value="asLink(row).productCode" />
      </template>
      <template #cell-weidianItemId="{ row }">
        <CopyText :value="asLink(row).weidianItemId" />
      </template>
      <template #cell-linkUrl="{ row }">
        <CopyText :value="asLink(row).linkUrl" />
      </template>
      <template #cell-status="{ row }">
        <el-tag v-if="asLink(row).available" type="success" data-test="status">{{
          t("link.available")
        }}</el-tag>
        <el-tooltip v-else :content="reasonText(asLink(row))" placement="top">
          <el-tag type="danger" data-test="status">{{
            t("link.unavailable", { reason: reasonText(asLink(row)) })
          }}</el-tag>
        </el-tooltip>
      </template>
      <template #cell-clicks="{ row }">
        {{ asLink(row).clicks30d }} / {{ asLink(row).validClicks30d }}
      </template>
    </DataTable>
  </el-drawer>
</template>
