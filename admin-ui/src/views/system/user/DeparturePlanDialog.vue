<script setup lang="ts">
import { ElMessage } from "element-plus";
import { computed, ref, watch } from "vue";
import { useI18n } from "vue-i18n";
import {
  createDeparturePlan,
  listDepartureHeirs,
  updateDeparturePlan,
  type DepartureHeirVO
} from "@/api/system/departure-plan";
import type { DeparturePlanType, UserVO } from "@/api/types";
import { notifyError } from "@/utils/notify";

defineOptions({ name: "DeparturePlanDialog" });

const props = defineProps<{ modelValue: boolean; user: UserVO | null }>();
const emit = defineEmits<{
  (event: "update:modelValue", value: boolean): void;
  (event: "saved"): void;
}>();

const { t } = useI18n();

/** Select value for "no heir"; submitted as `heirUserId: null`. */
const NO_HEIR = "__none__";

const heirs = ref<DepartureHeirVO[]>([]);
const heirsLoading = ref(false);
const saving = ref(false);
const type = ref<DeparturePlanType>("RESIGN");
const scheduled = ref(false);
const effectiveLocal = ref<Date | null>(null);
const heir = ref<string>(NO_HEIR);
const reason = ref("");
const error = ref("");

/** Editing an existing pending plan keeps its holder informed; the account is not changed here. */
const editing = computed(() => props.user?.departurePlan?.status === "SCHEDULED");

/** Only a pending plan seeds the form: a withdrawn one is "no plan" in the list and must not
 *  come back as a pre-filled form the administrator would resubmit by accident. */
const pendingPlan = computed(() => {
  const plan = props.user?.departurePlan;
  return plan?.status === "SCHEDULED" ? plan : null;
});

const title = computed(() =>
  editing.value
    ? t("departure.editTitle", { username: props.user?.username ?? "" })
    : t("departure.markTitle", { username: props.user?.username ?? "" })
);

watch(
  () => props.modelValue,
  open => {
    if (!open) return;
    const plan = pendingPlan.value;
    error.value = "";
    type.value = plan?.type ?? "RESIGN";
    // An existing plan is always shown with its time, even when that time is already very close.
    scheduled.value = plan ? true : false;
    effectiveLocal.value = plan ? new Date(plan.effectiveAt) : null;
    heir.value = plan?.heirUserId ?? NO_HEIR;
    reason.value = plan?.reason ?? "";
    void loadHeirs();
  }
);

async function loadHeirs() {
  heirsLoading.value = true;
  try {
    heirs.value = await listDepartureHeirs(props.user?.id);
  } catch (failure) {
    // A failed option list blocks the picker only; the plan can still be saved without an heir.
    notifyError(failure);
  } finally {
    heirsLoading.value = false;
  }
}

/** ISO instant of the picked time, or an error message. */
function effectiveAtOrError(): { value?: string; error?: string } {
  if (!scheduled.value) return {};
  const picked = effectiveLocal.value;
  if (!picked) return { error: t("departure.pickTime") };
  if (picked.getTime() <= Date.now()) return { error: t("departure.timeFuture") };
  return { value: picked.toISOString() };
}

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

function close() {
  emit("update:modelValue", false);
}

async function submit() {
  const user = props.user;
  if (!user || saving.value) return;
  const effective = effectiveAtOrError();
  if (effective.error) {
    error.value = effective.error;
    return;
  }
  error.value = "";
  saving.value = true;
  const request = {
    type: type.value,
    ...(effective.value ? { effectiveAt: effective.value } : {}),
    heirUserId: heir.value === NO_HEIR ? null : heir.value,
    reason: reason.value.trim() ? reason.value.trim() : null
  };
  try {
    if (editing.value) {
      await updateDeparturePlan(user.id, request);
      ElMessage.success(t("departure.updated"));
    } else {
      await createDeparturePlan(user.id, request);
      ElMessage.success(t("departure.created"));
    }
    emit("saved");
    close();
  } catch (failure) {
    notifyError(failure);
  } finally {
    saving.value = false;
  }
}
</script>

<template>
  <el-dialog
    :model-value="modelValue"
    :title="title"
    width="480px"
    @update:model-value="emit('update:modelValue', $event)"
  >
    <el-form label-width="110px" label-position="left" @submit.prevent>
      <el-form-item :label="t('departure.type')">
        <el-radio-group v-model="type" data-test="departure-type">
          <el-radio-button value="RESIGN">{{ t("departure.typeResign") }}</el-radio-button>
          <el-radio-button value="DISABLE">{{ t("departure.typeDisable") }}</el-radio-button>
        </el-radio-group>
      </el-form-item>
      <el-form-item :label="t('departure.effectiveTime')">
        <div class="effective">
          <el-radio-group v-model="scheduled" data-test="departure-mode">
            <el-radio-button :value="false">{{ t("departure.now") }}</el-radio-button>
            <el-radio-button :value="true">{{ t("departure.at") }}</el-radio-button>
          </el-radio-group>
          <template v-if="scheduled">
            <el-date-picker
              v-model="effectiveLocal"
              type="datetime"
              :placeholder="t('departure.pickTimePlaceholder')"
              data-test="departure-time"
            />
            <span class="effective__tz">{{ timezoneNote }}</span>
          </template>
        </div>
      </el-form-item>
      <el-form-item :label="t('departure.heir')">
        <el-select
          v-model="heir"
          filterable
          :loading="heirsLoading"
          :placeholder="t('departure.heirPlaceholder')"
          style="width: 100%"
          data-test="departure-heir"
        >
          <el-option :label="t('departure.noHeir')" :value="NO_HEIR" />
          <el-option
            v-for="item in heirs"
            :key="item.id"
            :label="t('kol.bdOption', { nickname: item.nickname, username: item.username })"
            :value="item.id"
          />
        </el-select>
      </el-form-item>
      <el-form-item :label="t('departure.reason')">
        <el-input
          v-model="reason"
          type="textarea"
          :rows="2"
          maxlength="255"
          show-word-limit
          data-test="departure-reason"
          :placeholder="t('departure.reasonPlaceholder')"
        />
      </el-form-item>
    </el-form>
    <el-alert
      v-if="error"
      type="warning"
      :closable="false"
      :title="error"
      data-test="departure-error"
    />
    <el-alert
      type="info"
      :closable="false"
      class="hint"
      data-test="departure-hint"
      :title="heir === NO_HEIR ? t('departure.noHeirHint') : t('departure.withHeirHint')"
    />
    <template #footer>
      <el-button @click="close">{{ t("common.cancel") }}</el-button>
      <el-button type="primary" :loading="saving" data-test="departure-submit" @click="submit">{{
        t("common.confirm")
      }}</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.effective {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: var(--tcrm-space-2);
  width: 100%;
}
.effective__tz {
  font-size: var(--tcrm-font-size-help);
  color: var(--tcrm-color-text-secondary);
}
.hint {
  margin-top: var(--tcrm-space-2);
}
</style>
