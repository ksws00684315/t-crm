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
    <span class="">{{ value }}</span>
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
}
</style>
