<script setup lang="ts">
import { ElMessage } from "element-plus";
import { computed, reactive, ref, watch } from "vue";
import { useI18n } from "vue-i18n";
import { listLinks } from "@/api/link/link";
import type { LinkVO } from "@/api/types";
import { notifyError } from "@/utils/notify";

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
const query = reactive({ page: 1, size: 20, productCode: "" });

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

async function copy(row: LinkVO) {
  try {
    await navigator.clipboard.writeText(row.linkUrl);
    ElMessage.success(t("link.copied"));
  } catch {
    ElMessage.error(t("link.copyFailed"));
  }
}
</script>

<template>
  <el-drawer
    :model-value="modelValue"
    :title="title"
    size="960px"
    @update:model-value="emit('update:modelValue', $event)"
  >
    <el-form :inline="true" :model="query" @submit.prevent>
      <el-form-item :label="t('link.productCode')">
        <el-input
          v-model="query.productCode"
          clearable
          maxlength="32"
          :placeholder="t('link.exact')"
          data-test="link-product-code"
          @keyup.enter="search"
        />
      </el-form-item>
      <el-form-item>
        <el-button type="primary" data-test="link-search" @click="search">{{
          t("common.search")
        }}</el-button>
        <el-button @click="reset">{{ t("common.reset") }}</el-button>
      </el-form-item>
    </el-form>

    <el-table v-loading="loading" :data="rows" row-key="kolItemId" border>
      <el-table-column prop="productCode" :label="t('link.productCode')" width="120" />
      <el-table-column prop="productName" :label="t('link.productName')" min-width="120" />
      <el-table-column prop="weidianItemId" :label="t('link.exclusiveId')" width="130" />
      <el-table-column :label="t('link.trackingLink')" min-width="260">
        <template #default="{ row }">
          <span class="link">{{ (row as LinkVO).linkUrl }}</span>
          <el-button link type="primary" data-test="copy" @click="copy(row as LinkVO)">
            {{ t("common.copy") }}
          </el-button>
        </template>
      </el-table-column>
      <el-table-column :label="t('common.status')" width="150">
        <template #default="{ row }">
          <el-tag v-if="(row as LinkVO).available" type="success" data-test="status">{{
            t("link.available")
          }}</el-tag>
          <el-tooltip v-else :content="reasonText(row as LinkVO)" placement="top">
            <el-tag type="danger" data-test="status">{{
              t("link.unavailable", { reason: reasonText(row as LinkVO) })
            }}</el-tag>
          </el-tooltip>
        </template>
      </el-table-column>
      <el-table-column :label="t('link.clicks')" width="130">
        <template #default="{ row }">
          {{ (row as LinkVO).clicks30d }} / {{ (row as LinkVO).validClicks30d }}
        </template>
      </el-table-column>
    </el-table>

    <el-pagination
      v-model:current-page="query.page"
      v-model:page-size="query.size"
      class="pager"
      background
      layout="total, sizes, prev, pager, next"
      :page-sizes="[10, 20, 50, 100]"
      :total="total"
      @current-change="load"
      @size-change="search"
    />
  </el-drawer>
</template>

<style scoped>
.link {
  margin-right: 8px;
  word-break: break-all;
}
.pager {
  margin-top: 16px;
  justify-content: flex-end;
}
</style>
