<script setup lang="ts">
import { ElMessage, ElMessageBox } from "element-plus";
import { computed, onMounted, ref } from "vue";
import { useI18n } from "vue-i18n";
import { cancelResignation, getOwnResignation, submitResignation } from "@/api/auth";
import type { DeparturePlanVO } from "@/api/types";
import { useUserStore } from "@/stores/user";
import { formatInstantToMinute } from "@/utils/format";
import { notifyError } from "@/utils/notify";
import { accessOf } from "@/utils/permission";

defineOptions({ name: "AccountResignationPage" });

const { t } = useI18n();
const user = useUserStore();

/** Only a BD (staff with own scope) may resign; the backend rejects anyone else too. */
const canResign = computed(() => accessOf(user.user).isOwnScope);
const loading = ref(false);
const saving = ref(false);
const plan = ref<DeparturePlanVO | null>(null);
const plannedLeaveAt = ref<Date | null>(null);
const reason = ref("");
const error = ref("");

const timezoneNote = computed(() => {
  const zone = Intl.DateTimeFormat().resolvedOptions().timeZone;
  const offset = -new Date().getTimezoneOffset();
  const sign = offset >= 0 ? "+" : "-";
  const minutes = Math.abs(offset);
  const hourPart = Math.floor(minutes / 60);
  const minutePart = minutes % 60;
  const text =
    minutePart === 0 ? `${hourPart}` : `${hourPart}:${String(minutePart).padStart(2, "0")}`;
  return t("departure.timezoneHint", { timezone: zone, offset: `${sign}${text}` });
});

async function load() {
  if (!canResign.value) return;
  loading.value = true;
  try {
    plan.value = await getOwnResignation();
  } catch (failure) {
    notifyError(failure);
  } finally {
    loading.value = false;
  }
}

async function submit() {
  if (saving.value) return;
  const picked = plannedLeaveAt.value;
  if (!picked) {
    error.value = t("departure.pickTime");
    return;
  }
  if (picked.getTime() <= Date.now()) {
    error.value = t("departure.timeFuture");
    return;
  }
  error.value = "";
  saving.value = true;
  try {
    plan.value = await submitResignation({
      plannedLeaveAt: picked.toISOString(),
      reason: reason.value.trim() ? reason.value.trim() : null
    });
    ElMessage.success(t("departure.submitted"));
  } catch (failure) {
    notifyError(failure);
  } finally {
    saving.value = false;
  }
}

async function cancel() {
  try {
    await ElMessageBox.confirm(t("departure.cancelOwnConfirm"), t("departure.cancelTitle"), {
      type: "warning"
    });
  } catch {
    return; // cancelled
  }
  try {
    await cancelResignation();
    ElMessage.success(t("departure.cancelled"));
    plan.value = null;
    plannedLeaveAt.value = null;
    reason.value = "";
  } catch (failure) {
    notifyError(failure);
  }
}

onMounted(load);
</script>

<template>
  <el-card v-loading="loading" shadow="never" class="resignation">
    <el-alert
      v-if="!canResign"
      type="info"
      :closable="false"
      :title="t('departure.bdOnly')"
      data-test="resignation-bd-only"
    />
    <template v-else>
      <el-descriptions v-if="plan" :column="1" border data-test="resignation-plan">
        <el-descriptions-item :label="t('departure.type')">
          {{ plan.type === "RESIGN" ? t("departure.typeResign") : t("departure.typeDisable") }}
        </el-descriptions-item>
        <el-descriptions-item :label="t('departure.effectiveTime')">
          {{ formatInstantToMinute(plan.effectiveAt) }}
        </el-descriptions-item>
        <el-descriptions-item :label="t('departure.heir')">
          {{ plan.heirName ?? t("departure.awaitingHeir") }}
        </el-descriptions-item>
        <el-descriptions-item v-if="plan.reason" :label="t('departure.reason')">
          {{ plan.reason }}
        </el-descriptions-item>
        <el-descriptions-item :label="t('departure.status')">
          {{ t("departure.statusScheduled") }}
        </el-descriptions-item>
      </el-descriptions>
      <el-alert
        v-if="plan"
        type="info"
        :closable="false"
        :title="t('departure.submittedHint')"
        class="hint"
        data-test="resignation-hint"
      />
      <div v-if="plan" class="footer">
        <el-button type="danger" plain data-test="resignation-cancel" @click="cancel">{{
          t("departure.cancelPlan")
        }}</el-button>
      </div>

      <el-form v-else label-width="110px" @submit.prevent>
        <el-form-item :label="t('departure.plannedLeaveAt')">
          <div class="field">
            <el-date-picker
              v-model="plannedLeaveAt"
              type="datetime"
              :placeholder="t('departure.pickTimePlaceholder')"
              data-test="resignation-time"
            />
            <span class="field__tz">{{ timezoneNote }}</span>
          </div>
        </el-form-item>
        <el-form-item :label="t('departure.reason')">
          <el-input
            v-model="reason"
            type="textarea"
            :rows="3"
            maxlength="255"
            show-word-limit
            data-test="resignation-reason"
            :placeholder="t('departure.reasonPlaceholder')"
          />
        </el-form-item>
        <el-alert
          v-if="error"
          type="warning"
          :closable="false"
          :title="error"
          class="hint"
          data-test="resignation-error"
        />
        <el-form-item>
          <el-button
            type="primary"
            :loading="saving"
            data-test="resignation-submit"
            @click="submit"
            >{{ t("departure.submit") }}</el-button
          >
        </el-form-item>
      </el-form>
    </template>
  </el-card>
</template>

<style scoped>
.resignation {
  max-width: 640px;
}
.field {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: var(--tcrm-space-2);
}
.field__tz {
  font-size: var(--tcrm-font-size-help);
  color: var(--tcrm-color-text-secondary);
}
.hint {
  margin-top: var(--tcrm-space-2);
}
.footer {
  margin-top: var(--tcrm-space-3);
}
</style>
