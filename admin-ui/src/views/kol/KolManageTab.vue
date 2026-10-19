<script setup lang="ts">
import { ElMessage } from "element-plus";
import { computed, onMounted, reactive, ref } from "vue";
import { useI18n } from "vue-i18n";
import { createBdTransfer, type BdTransferResultVO } from "@/api/kol/bd-transfer";
import { listKols } from "@/api/kol/kol";
import { listBdUsers } from "@/api/system/bd-user";
import type { BdUserVO, KolVO } from "@/api/types";
import { ApiError } from "@/utils/http/error";
import { notifyError } from "@/utils/notify";

defineOptions({ name: "KolManageTab" });

const UNASSIGNED = "__unassigned__";
const MAX_KOLS = 500;

/**
 * The creator-management tab, super administrator only (task 32 §2.2): transfer creators to another BD
 * (or to unassigned), now or at a scheduled time, with a reason. The transfer history itself is
 * queried in System > Audit Log.
 */
const emit = defineEmits<{ (event: "changed"): void }>();

const { t } = useI18n();

const bdUsers = ref<BdUserVO[]>([]);
const optionsLoading = ref(false);
const saving = ref(false);
const conflictCodes = ref<string[]>([]);
const searching = ref(false);
const foundKols = ref<KolVO[]>([]);

const form = reactive({
  scope: "specific" as "bdAll" | "specific",
  fromBdUserId: null as string | null,
  kolIds: [] as string[],
  toBdUserId: null as string | null,
  scheduled: false,
  effectiveLocal: null as Date | null,
  reason: ""
});

const bdOptions = computed(() => bdUsers.value);

const timezoneNote = computed(() => {
  const zone = Intl.DateTimeFormat().resolvedOptions().timeZone;
  const offset = -new Date().getTimezoneOffset();
  const sign = offset >= 0 ? "+" : "-";
  const minutes = Math.abs(offset);
  const hourPart = Math.floor(minutes / 60);
  const minutePart = minutes % 60;
  const text =
    minutePart === 0 ? `${hourPart}` : `${hourPart}:${String(minutePart).padStart(2, "0")}`;
  return t("bdTransfer.timezoneHint", { timezone: zone, offset: `${sign}${text}` });
});

const startOfToday = () => {
  const day = new Date();
  day.setHours(0, 0, 0, 0);
  return day.getTime();
};

function disabledDate(day: Date): boolean {
  return day.getTime() < startOfToday();
}

async function loadBdUsers() {
  optionsLoading.value = true;
  try {
    bdUsers.value = await listBdUsers();
  } catch (error) {
    notifyError(error);
  } finally {
    optionsLoading.value = false;
  }
}

/** Remote search over the whole creator list: creator ID, name or email all match `keyword`. */
async function searchKols(keyword: string): Promise<void> {
  const text = keyword.trim();
  if (!text) {
    foundKols.value = [];
    return;
  }
  searching.value = true;
  try {
    const page = await listKols({ page: 1, size: 20, keyword: text });
    foundKols.value = page.items;
  } catch (error) {
    notifyError(error);
  } finally {
    searching.value = false;
  }
}

function kolLabel(kol: KolVO): string {
  return `${kol.name} (${kol.kolCode})`;
}

function effectiveAtOrNull(): { value?: string; error?: string } {
  if (!form.scheduled) return {};
  const picked = form.effectiveLocal;
  if (!picked) return { error: t("bdTransfer.pickTime") };
  const now = Date.now();
  if (picked.getTime() <= now) return { error: t("bdTransfer.timeFuture") };
  const limit = new Date(now);
  limit.setFullYear(limit.getFullYear() + 1);
  if (picked.getTime() > limit.getTime()) return { error: t("bdTransfer.timeRange") };
  return { value: picked.toISOString() };
}

async function submit() {
  if (saving.value) return;
  if (form.scope === "bdAll" && !form.fromBdUserId) {
    ElMessage.warning(t("bdTransfer.bdAllLabel"));
    return;
  }
  if (form.scope === "specific" && form.kolIds.length === 0) {
    ElMessage.warning(t("bdTransfer.specificPlaceholder"));
    return;
  }
  if (!form.toBdUserId) {
    ElMessage.warning(t("bdTransfer.targetBdRequired"));
    return;
  }
  if (form.kolIds.length > MAX_KOLS) {
    ElMessage.warning(t("bdTransfer.tooMany", { max: MAX_KOLS }));
    return;
  }
  const effective = effectiveAtOrNull();
  if (effective.error) {
    ElMessage.warning(effective.error);
    return;
  }
  saving.value = true;
  conflictCodes.value = [];
  try {
    const result: BdTransferResultVO = await createBdTransfer({
      ...(form.scope === "specific"
        ? { kolIds: form.kolIds }
        : { fromBdUserId: form.fromBdUserId ?? undefined }),
      toBdUserId: form.toBdUserId === UNASSIGNED ? null : form.toBdUserId,
      ...(effective.value ? { effectiveAt: effective.value } : {}),
      ...(form.reason.trim() ? { reason: form.reason.trim() } : {})
    });
    ElMessage.success(
      t("bdTransfer.success", {
        immediate: result.immediate,
        scheduled: result.scheduled,
        skipped: result.skipped
      })
    );
    form.kolIds = [];
    form.fromBdUserId = null;
    form.toBdUserId = null;
    form.scheduled = false;
    form.effectiveLocal = null;
    form.reason = "";
    emit("changed");
  } catch (error) {
    if (error instanceof ApiError && error.errorCode === "KOL_TRANSFER_EXISTS") {
      conflictCodes.value = Array.isArray(error.payload)
        ? error.payload.filter((code): code is string => typeof code === "string")
        : [];
      return;
    }
    notifyError(error);
  } finally {
    saving.value = false;
  }
}

onMounted(loadBdUsers);
</script>

<template>
  <div class="kol-manage" data-test="kol-manage-tab">
    <el-form class="kol-manage__form" label-width="110px" @submit.prevent>
      <el-form-item :label="t('bdTransfer.scope')">
        <el-radio-group v-model="form.scope" data-test="transfer-scope">
          <el-radio-button value="bdAll">{{ t("bdTransfer.scopeBdAll") }}</el-radio-button>
          <el-radio-button value="specific">{{ t("bdTransfer.scopeSpecific") }}</el-radio-button>
        </el-radio-group>
      </el-form-item>
      <el-form-item v-if="form.scope === 'bdAll'" :label="t('bdTransfer.bdAllLabel')">
        <el-select
          v-model="form.fromBdUserId"
          filterable
          :loading="optionsLoading"
          :placeholder="t('bdTransfer.bdAllLabel')"
          style="width: 360px"
        >
          <el-option
            v-for="bd in bdOptions"
            :key="bd.id"
            :label="t('kol.bdOption', { nickname: bd.nickname, username: bd.username })"
            :value="bd.id"
          />
        </el-select>
      </el-form-item>
      <el-form-item v-else :label="t('bdTransfer.scopeSpecific')">
        <el-select
          v-model="form.kolIds"
          multiple
          filterable
          remote
          :remote-method="searchKols"
          :loading="searching"
          :placeholder="t('bdTransfer.specificPlaceholder')"
          style="width: 100%"
          data-test="transfer-kols"
        >
          <el-option
            v-for="kol in foundKols"
            :key="kol.id"
            :label="kolLabel(kol)"
            :value="kol.id"
          />
        </el-select>
      </el-form-item>
      <el-form-item :label="t('bdTransfer.targetBd')">
        <el-select
          v-model="form.toBdUserId"
          filterable
          :loading="optionsLoading"
          :placeholder="t('bdTransfer.targetBdPlaceholder')"
          style="width: 360px"
          data-test="transfer-target-bd"
        >
          <el-option
            v-for="bd in bdOptions"
            :key="bd.id"
            :label="t('kol.bdOption', { nickname: bd.nickname, username: bd.username })"
            :value="bd.id"
          />
          <el-option :label="t('bdTransfer.unassigned')" :value="UNASSIGNED" />
        </el-select>
      </el-form-item>
      <el-form-item :label="t('bdTransfer.effectiveTime')">
        <div class="effective">
          <el-radio-group v-model="form.scheduled" data-test="effective-mode">
            <el-radio-button :value="false">{{ t("bdTransfer.now") }}</el-radio-button>
            <el-radio-button :value="true">{{ t("bdTransfer.at") }}</el-radio-button>
          </el-radio-group>
          <template v-if="form.scheduled">
            <el-date-picker
              v-model="form.effectiveLocal"
              type="datetime"
              :disabled-date="disabledDate"
              :placeholder="t('bdTransfer.pickTimePlaceholder')"
              data-test="effective-at"
            />
            <span class="effective__tz">{{ timezoneNote }}</span>
          </template>
        </div>
      </el-form-item>
      <el-form-item :label="t('bdTransfer.reason')">
        <el-input
          v-model="form.reason"
          type="textarea"
          :rows="2"
          maxlength="255"
          show-word-limit
          :placeholder="t('bdTransfer.reasonPlaceholder')"
          style="width: 100%"
        />
      </el-form-item>
      <el-form-item>
        <el-button type="primary" :loading="saving" data-test="transfer-submit" @click="submit">
          {{ t("bdTransfer.submit") }}
        </el-button>
      </el-form-item>
    </el-form>
    <el-alert
      v-if="conflictCodes.length"
      type="error"
      :closable="false"
      data-test="transfer-conflicts"
      :title="t('bdTransfer.conflicts')"
    >
      <span class="conflicts">{{ conflictCodes.join(t("link.separator")) }}</span>
    </el-alert>
    <p class="kol-manage__hint" data-test="transfer-records-hint">
      {{ t("kol.transferRecordsHint") }}
    </p>
  </div>
</template>

<style scoped>
.kol-manage {
  max-width: 720px;
  padding: var(--tcrm-space-3);
  background: var(--tcrm-color-card);
  border: 1px solid var(--tcrm-color-border);
  border-radius: var(--tcrm-radius-card);
}
.kol-manage__hint {
  margin: 12px 0 0;
  color: var(--tcrm-color-text-secondary);
}
.effective {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: var(--tcrm-space-2);
  width: 100%;
}
.effective__tz {
  font-size: 12px;
  color: var(--tcrm-color-text-secondary);
}
.conflicts {
  word-break: break-all;
}
</style>
