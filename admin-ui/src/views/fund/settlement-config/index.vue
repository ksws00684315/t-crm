<script setup lang="ts">
import { computed, onMounted, ref, watch } from "vue";
import { useI18n } from "vue-i18n";
import FinancePreviewNotice from "@/components/FinancePreviewNotice.vue";
import FinanceUnavailable from "@/components/FinanceUnavailable.vue";
import { ElMessage, type FormInstance, type FormRules } from "element-plus";
import { getConfig, saveConfig } from "@/api/commission/finance";
import type { SettlementConfig } from "@/api/commission/finance-types";
import DataTable, { type DataColumn } from "@/components/DataTable.vue";
import { useUserStore } from "@/stores/user";
import { financeAbilities, financeApiReady, previewContext, validMoney } from "@/utils/finance";
import { notifyError } from "@/utils/notify";
defineOptions({ name: "SettlementConfigPage" });
const { t } = useI18n();
const user = useUserStore();
const abilities = computed(() => financeAbilities(user.user));
const config = ref<SettlementConfig>();
const loading = ref(false);
const busy = ref(false);
const failed = ref(false);
const form = ref<FormInstance>();
const rules = computed<FormRules>(() => ({
  minimum: [
    {
      validator: (_rule, value, callback) =>
        validMoney(value, false) ? callback() : callback(new Error(t("finance.validAmount"))),
      trigger: "blur"
    }
  ]
}));
const columns = computed<DataColumn[]>(() =>
  ["channelCode", "channelName", "releaseBasis", "freezeDays"].map(key => ({
    key,
    label: t(`finance.${key}`),
    minWidth: key === "releaseBasis" ? 420 : 180
  }))
);
async function load() {
  if (!financeApiReady) return;
  loading.value = true;
  failed.value = false;
  try {
    config.value = await getConfig();
  } catch (error) {
    failed.value = true;
    config.value = undefined;
    notifyError(error);
  } finally {
    loading.value = false;
  }
}
async function save() {
  if (
    busy.value ||
    !abilities.value.configure ||
    !config.value ||
    !(await form.value?.validate().catch(() => false))
  )
    return;
  busy.value = true;
  try {
    config.value = await saveConfig(
      structuredClone({ ...config.value, channels: config.value.channels.map(row => ({ ...row })) })
    );
    ElMessage.success(t("finance.saved"));
  } catch (error) {
    notifyError(error);
  } finally {
    busy.value = false;
  }
}
watch(() => [previewContext.role, previewContext.scenario], load);
onMounted(load);
</script>
<template>
  <FinanceUnavailable v-if="!financeApiReady" />
  <div v-else class="config-page" v-loading="loading" data-test="config-page">
    <FinancePreviewNotice />
    <el-result v-if="failed" icon="error" :title="t('finance.loadFailed')"
      ><template #extra
        ><el-button @click="load">{{ t("finance.retry") }}</el-button></template
      ></el-result
    >
    <template v-if="config">
      <el-card shadow="never"
        ><template #header
          ><h2>{{ t("finance.configBase") }}</h2></template
        >
        <el-form ref="form" :model="config" :rules="rules" label-position="top" class="config-form">
          <el-form-item :label="t('finance.accountingCurrency')"
            ><el-input model-value="CNY" disabled
          /></el-form-item>
          <el-form-item :label="t('finance.minimum')" prop="minimum"
            ><el-input
              v-model="config.minimum"
              :disabled="!abilities.configure"
              data-test="config-minimum"
              ><template #append>CNY</template></el-input
            ></el-form-item
          >
          <el-button
            v-if="abilities.configure"
            type="primary"
            :loading="busy"
            data-test="config-save"
            @click="save"
            >{{ t("finance.save") }}</el-button
          >
        </el-form>
      </el-card>
      <DataTable
        table-id="commission-freeze-rules"
        :rows="config.channels"
        :columns="columns"
        :total="config.channels.length"
        :paged="false"
        @refresh="load"
      >
        <template #cell-channelCode="{ row }">{{ row.code }}</template
        ><template #cell-channelName="{ row }">{{ t(`finance.${row.name ?? "weidian"}`) }}</template
        ><template #cell-releaseBasis="{ row }">{{
          t(`finance.${row.basis ?? "freezeBasis"}`)
        }}</template>
        <template #cell-freezeDays="{ row }"
          ><el-input-number
            v-model="row.days"
            :min="0"
            :max="365"
            :precision="0"
            :disabled="!abilities.configure"
            data-test="freeze-days"
        /></template>
      </DataTable>
      <el-alert :title="t('finance.configNote')" type="warning" :closable="false" />
      <p>{{ t("finance.versionNote") }}</p>
      <p>{{ t("finance.ruleVersion") }}: {{ config.version }}</p>
    </template>
  </div>
</template>
<style scoped>
.config-page {
  display: flex;
  flex-direction: column;
  gap: var(--tcrm-space-4);
  min-width: 0;
}
h2 {
  font-size: var(--tcrm-font-size-section);
  line-height: var(--tcrm-line-height-section);
  margin: 0;
}
.config-form {
  display: flex;
  align-items: end;
  flex-wrap: wrap;
  gap: var(--tcrm-space-4);
}
.config-form :deep(.el-form-item) {
  margin-bottom: 0;
  max-width: 320px;
}
p {
  margin: 0;
  color: var(--tcrm-color-text-secondary);
}
</style>
