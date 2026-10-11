<script setup lang="ts">
import { ElMessage } from "element-plus";
import { ref, watch } from "vue";
import { useI18n } from "vue-i18n";
import { changeKolBd } from "@/api/kol/kol";
import type { BdUserVO, KolVO } from "@/api/types";
import { notifyError } from "@/utils/notify";

defineOptions({ name: "ChangeBdDialog" });

const { t } = useI18n();

const props = defineProps<{ modelValue: boolean; kol: KolVO | null; bdUsers: BdUserVO[] }>();
const emit = defineEmits<{
  (event: "update:modelValue", value: boolean): void;
  (event: "saved"): void;
}>();

const bdUserId = ref<string | null>(null);
const saving = ref(false);

watch(
  () => props.modelValue,
  open => {
    // Preselect only a BD that is still selectable; otherwise start empty.
    const current = props.kol?.bdUserId ?? null;
    if (open) bdUserId.value = props.bdUsers.some(bd => bd.id === current) ? current : null;
  }
);

function close() {
  emit("update:modelValue", false);
}

async function submit() {
  if (!props.kol) return;
  saving.value = true;
  try {
    await changeKolBd(props.kol.id, bdUserId.value || null);
    ElMessage.success(t("kol.changeBdDone"));
    emit("saved");
    close();
  } catch (error) {
    notifyError(error);
  } finally {
    saving.value = false;
  }
}
</script>

<template>
  <el-dialog
    :model-value="modelValue"
    :title="t('kol.changeBdTitle')"
    width="440px"
    @update:model-value="emit('update:modelValue', $event)"
  >
    <p class="hint">
      {{
        t("kol.changeBdSummary", {
          code: kol?.kolCode,
          name: kol?.name,
          bd: kol?.bdUserName ?? t("common.unassigned")
        })
      }}
    </p>
    <el-select
      v-model="bdUserId"
      clearable
      filterable
      :placeholder="t('kol.changeBdPlaceholder')"
      style="width: 100%"
    >
      <el-option
        v-for="bd in bdUsers"
        :key="bd.id"
        :label="t('kol.bdOption', { nickname: bd.nickname, username: bd.username })"
        :value="bd.id"
      />
    </el-select>
    <template #footer>
      <el-button @click="close">{{ t("common.cancel") }}</el-button>
      <el-button type="primary" :loading="saving" @click="submit">{{
        t("common.confirm")
      }}</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.hint {
  margin: 0 0 12px;
  color: var(--el-text-color-secondary);
}
</style>
