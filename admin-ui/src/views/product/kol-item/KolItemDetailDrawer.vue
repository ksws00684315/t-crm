<script setup lang="ts">
import { Picture } from "@element-plus/icons-vue";
import { ref, watch } from "vue";
import { useI18n } from "vue-i18n";
import { getKolItem } from "@/api/product/kol-item";
import type { KolItemDetailVO } from "@/api/types";
import CopyText from "@/components/CopyText.vue";
import { formatInstant, formatNameWithCode } from "@/utils/format";
import { notifyError } from "@/utils/notify";
import { formatRate } from "@/utils/rate";

const props = defineProps<{ modelValue: boolean; itemId: string | null }>();
const emit = defineEmits<{ "update:modelValue": [value: boolean] }>();

const { t } = useI18n();
const loading = ref(false);
const detail = ref<KolItemDetailVO | null>(null);

watch(
  () => props.itemId,
  id => {
    if (props.modelValue && id) {
      void load(id);
    }
  },
  { immediate: true }
);

async function load(id: string): Promise<void> {
  loading.value = true;
  try {
    detail.value = await getKolItem(id);
  } catch (error) {
    notifyError(error);
    emit("update:modelValue", false);
  } finally {
    loading.value = false;
  }
}

function openLink(url: string | null): void {
  if (!url) return;
  window.open(url, "_blank", "noopener");
}

function text(value: string | null | undefined): string {
  return value ? value : "-";
}

function shopText(row: KolItemDetailVO): string {
  if (row.targetShopName || row.targetShopCode) {
    return formatNameWithCode(row.targetShopName, row.targetShopCode);
  }
  return "-";
}

function label(group: "channels" | "statuses" | "cleanups", value: string | null): string {
  return value ? t(`mapping.${group}.${value}`) : "-";
}
</script>

<template>
  <el-drawer
    :model-value="modelValue"
    :title="t('mapping.detailTitle')"
    size="min(720px, calc(100vw - 24px))"
    class="kol-item-detail-drawer"
    data-test="detail-drawer"
    @update:model-value="emit('update:modelValue', $event)"
  >
    <div v-loading="loading">
      <el-descriptions v-if="detail" :column="1" border>
        <el-descriptions-item :label="t('mapping.linkCode')">
          <CopyText :value="detail.linkCode" />
        </el-descriptions-item>
        <el-descriptions-item :label="t('mapping.kol')">
          {{ formatNameWithCode(text(detail.kolName), text(detail.kolCode)) }}
        </el-descriptions-item>
        <el-descriptions-item :label="t('mapping.targetShop')">
          {{ shopText(detail) }}
        </el-descriptions-item>
        <el-descriptions-item :label="t('mapping.targetChannel')">
          {{ label("channels", detail.targetChannel) }}
        </el-descriptions-item>
        <el-descriptions-item :label="t('mapping.targetItemId')">
          <CopyText :value="detail.targetItemId" />
        </el-descriptions-item>
        <el-descriptions-item :label="t('mapping.targetItemUrl')">
          <el-button
            v-if="detail.targetItemUrl"
            link
            type="primary"
            data-test="open-target-detail"
            @click="openLink(detail.targetItemUrl)"
          >
            {{ t("mapping.open") }}
          </el-button>
          <CopyText v-if="detail.targetItemUrl" class="link-copy" :value="detail.targetItemUrl" />
          <span v-else>-</span>
        </el-descriptions-item>
        <el-descriptions-item :label="t('mapping.detailProductName')">
          {{ text(detail.itemName) }}
        </el-descriptions-item>
        <el-descriptions-item :label="t('mapping.detailProductImage')">
          <el-image
            v-if="detail.imageUrl"
            :src="detail.imageUrl"
            fit="contain"
            class="product-thumb"
            data-test="detail-image"
          />
          <span v-else class="product-thumb product-thumb--empty" data-test="detail-image">
            <el-icon><Picture /></el-icon>
          </span>
        </el-descriptions-item>
        <el-descriptions-item :label="t('mapping.sourceChannel')">
          {{ label("channels", detail.sourceChannel) }}
        </el-descriptions-item>
        <el-descriptions-item :label="t('mapping.sourceItemId')">
          <CopyText v-if="detail.sourceItemId" :value="detail.sourceItemId" />
          <span v-else>-</span>
        </el-descriptions-item>
        <el-descriptions-item :label="t('mapping.sourceItemUrl')">
          <el-button
            v-if="detail.sourceItemUrl"
            link
            type="primary"
            data-test="open-source"
            @click="openLink(detail.sourceItemUrl)"
          >
            {{ t("mapping.open") }}
          </el-button>
          <CopyText v-if="detail.sourceItemUrl" class="link-copy" :value="detail.sourceItemUrl" />
          <span v-else>-</span>
        </el-descriptions-item>
        <el-descriptions-item :label="t('mapping.currentRate')">
          {{ formatRate(detail.currentRate) }}
        </el-descriptions-item>
        <el-descriptions-item :label="t('mapping.visitCount')">
          {{ detail.visitCount }}
        </el-descriptions-item>
        <el-descriptions-item :label="t('mapping.salesQuantity')">
          {{ detail.salesQuantity }}
        </el-descriptions-item>
        <el-descriptions-item :label="t('mapping.totalPayAmount')">
          {{ detail.totalPayAmount }}
        </el-descriptions-item>
        <el-descriptions-item :label="t('mapping.status')">
          <el-tag :type="detail.status === 'ENABLED' ? 'success' : 'info'">{{
            label("statuses", detail.status)
          }}</el-tag>
        </el-descriptions-item>
        <el-descriptions-item :label="t('mapping.cleanupStatus')">
          <el-tag
            :type="
              detail.cleanupStatus === 'FAILED'
                ? 'danger'
                : detail.cleanupStatus === 'DONE'
                  ? 'success'
                  : 'info'
            "
            >{{ label("cleanups", detail.cleanupStatus) }}</el-tag
          >
        </el-descriptions-item>
        <el-descriptions-item :label="t('mapping.cleanupTime')">
          {{ detail.cleanupTime ? formatInstant(detail.cleanupTime) : "-" }}
        </el-descriptions-item>
        <el-descriptions-item :label="t('mapping.cleanupReason')">
          {{ text(detail.cleanupReason) }}
        </el-descriptions-item>
        <el-descriptions-item :label="t('mapping.cleanupFailReason')">
          {{ text(detail.cleanupFailReason) }}
        </el-descriptions-item>
        <el-descriptions-item :label="t('mapping.createTime')">
          {{ formatInstant(detail.createTime) }}
        </el-descriptions-item>
      </el-descriptions>
    </div>
  </el-drawer>
</template>

<style scoped>
.product-thumb {
  display: inline-flex;
  width: 120px;
  height: 120px;
  max-width: 100%;
  border-radius: var(--tcrm-radius-tag);
  vertical-align: middle;
}
.product-thumb--empty {
  align-items: center;
  justify-content: center;
  background: var(--tcrm-color-muted);
  color: var(--tcrm-color-text-secondary);
}
.link-copy :deep(.copy-text__value) {
  position: absolute;
  width: 1px;
  height: 1px;
  overflow: hidden;
  clip-path: inset(50%);
}
:deep(.el-descriptions__label) {
  width: 34%;
}
:deep(.el-descriptions__content) {
  overflow-wrap: anywhere;
}
</style>
