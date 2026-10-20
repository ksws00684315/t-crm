<script setup lang="ts">
import { ElMessage, ElMessageBox } from "element-plus";
import { onMounted, ref } from "vue";
import { useI18n } from "vue-i18n";
import {
  getSystemConfigs,
  resetSystemConfig,
  updateSystemConfig,
  type SystemConfigGroup
} from "@/api/system/site-config";
import { notifyError } from "@/utils/notify";
import { loadSiteConfig } from "@/stores/site-config";

defineOptions({ name: "PlatformSystemConfigPage" });

const { t } = useI18n();
const groups = ref<SystemConfigGroup[]>([]);
const loading = ref(false);
const saving = ref<string | null>(null);

async function load() {
  loading.value = true;
  try {
    groups.value = await getSystemConfigs();
  } catch (error) {
    notifyError(error);
  } finally {
    loading.value = false;
  }
}

async function edit(item: SystemConfigGroup["items"][number]) {
  if (item.key === "url.admin") {
    try {
      await ElMessageBox.confirm(
        t("systemConfig.adminUrlWarning"),
        t("systemConfig.adminUrlTitle"),
        {
          type: "warning"
        }
      );
    } catch {
      return;
    }
  }
  let value: string;
  try {
    const result = await ElMessageBox.prompt(t("systemConfig.editPrompt"), t(item.nameKey), {
      inputValue: item.value,
      inputType: "text",
      inputPattern:
        item.type === "integer" ||
        item.type === "minutes" ||
        item.type === "hours" ||
        item.type === "days"
          ? new RegExp(`^\\d+$`)
          : undefined,
      inputErrorMessage: t("systemConfig.integerRequired")
    });
    value = result.value;
  } catch {
    return;
  }
  saving.value = item.key;
  try {
    await updateSystemConfig(item.key, value);
    await Promise.all([load(), loadSiteConfig()]);
    ElMessage.success(t("systemConfig.saved"));
  } catch (error) {
    notifyError(error);
  } finally {
    saving.value = null;
  }
}

async function restore(item: SystemConfigGroup["items"][number]) {
  try {
    await ElMessageBox.confirm(
      t("systemConfig.resetConfirm", { name: t(item.nameKey) }),
      t("systemConfig.resetTitle"),
      {
        type: "warning"
      }
    );
  } catch {
    return;
  }
  saving.value = item.key;
  try {
    await resetSystemConfig(item.key);
    await Promise.all([load(), loadSiteConfig()]);
    ElMessage.success(t("systemConfig.restored"));
  } catch (error) {
    notifyError(error);
  } finally {
    saving.value = null;
  }
}

onMounted(load);
</script>

<template>
  <div class="config-page" v-loading="loading">
    <el-alert :title="t('systemConfig.notice')" type="info" :closable="false" show-icon />
    <section v-for="group in groups" :key="group.group" class="config-group">
      <h2>{{ t(`systemConfig.groups.${group.group}`) }}</h2>
      <el-card v-for="item in group.items" :key="item.key" shadow="never" class="config-card">
        <div class="config-card__heading">
          <strong>{{ t(item.nameKey) }}</strong>
          <el-tag v-if="item.overridden" type="warning" size="small">{{
            t("systemConfig.overridden")
          }}</el-tag>
        </div>
        <div class="config-card__values">
          <span
            >{{ t("systemConfig.currentValue") }}：<code>{{ item.value || "—" }}</code></span
          >
          <span
            >{{ t("systemConfig.defaultValue") }}：<code>{{ item.defaultValue || "—" }}</code></span
          >
          <span
            v-if="
              item.type !== 'string' && item.type !== 'url' && item.min != null && item.max != null
            "
            >{{ t("systemConfig.range") }}：{{ item.min }}–{{ item.max }}</span
          >
        </div>
        <div class="config-card__actions">
          <el-button size="small" :loading="saving === item.key" @click="edit(item)">{{
            t("common.edit")
          }}</el-button>
          <el-button
            size="small"
            :disabled="!item.overridden || saving === item.key"
            @click="restore(item)"
            >{{ t("systemConfig.restoreDefault") }}</el-button
          >
        </div>
      </el-card>
    </section>
  </div>
</template>

<style scoped>
.config-page {
  display: grid;
  gap: 20px;
}
.config-group {
  display: grid;
  gap: 10px;
}
.config-group h2 {
  margin: 0;
  font-size: 18px;
}
.config-card__heading,
.config-card__values,
.config-card__actions {
  display: flex;
  align-items: center;
  gap: 14px;
}
.config-card__heading {
  justify-content: space-between;
}
.config-card__values {
  flex-wrap: wrap;
  margin-top: 12px;
  color: var(--el-text-color-secondary);
}
.config-card__actions {
  justify-content: flex-end;
  margin-top: 12px;
}
code {
  overflow-wrap: anywhere;
}
</style>
