<script setup lang="ts">
import { CopyDocument } from "@element-plus/icons-vue";
import { ElMessage } from "element-plus";
import { useI18n } from "vue-i18n";
import { copyText, truncateCopy } from "@/utils/copy";

defineOptions({ name: "CopyText" });

const props = defineProps<{ value?: string | null }>();
const { t } = useI18n();

async function onCopy(): Promise<void> {
  const value = props.value ?? "";
  if (!value) return;
  const copied = await copyText(value);
  if (copied) ElMessage.success(t("copy.done", { text: truncateCopy(value) }));
  else ElMessage.error(t("copy.failed"));
}
</script>

<template>
  <span class="copy-text">
    <!-- The value never wraps (review R-05): it ellipsizes when the cell is too narrow and the
         full text stays available through the native tooltip. -->
    <span class="copy-text__value" data-test="value" :title="value || undefined">{{ value }}</span>
    <el-button
      v-if="value"
      link
      type="primary"
      data-test="copy"
      :aria-label="t('common.copy')"
      @click="onCopy"
    >
      <el-icon><CopyDocument /></el-icon>
    </el-button>
  </span>
</template>

<style scoped>
.copy-text {
  display: inline-flex;
  align-items: center;
  gap: var(--tcrm-space-1);
  min-width: 0;
  max-width: 100%;
}
.copy-text__value {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
</style>
