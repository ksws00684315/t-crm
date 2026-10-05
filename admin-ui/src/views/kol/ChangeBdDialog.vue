<script setup lang="ts">
import { ElMessage } from "element-plus";
import { ref, watch } from "vue";
import { changeKolBd } from "@/api/kol/kol";
import type { BdUserVO, KolVO } from "@/api/types";
import { notifyError } from "@/utils/notify";

defineOptions({ name: "ChangeBdDialog" });

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
    ElMessage.success("负责商务已更新");
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
    title="更换商务"
    width="440px"
    @update:model-value="emit('update:modelValue', $event)"
  >
    <p class="hint">
      达人 {{ kol?.kolCode }}（{{ kol?.name }}），当前商务：{{ kol?.bdUserName ?? "未分配" }}
    </p>
    <el-select
      v-model="bdUserId"
      clearable
      filterable
      placeholder="不选择则改为未分配"
      style="width: 100%"
    >
      <el-option
        v-for="bd in bdUsers"
        :key="bd.id"
        :label="`${bd.nickname}（${bd.username}）`"
        :value="bd.id"
      />
    </el-select>
    <template #footer>
      <el-button @click="close">取消</el-button>
      <el-button type="primary" :loading="saving" @click="submit">确定</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.hint {
  margin: 0 0 12px;
  color: var(--el-text-color-secondary);
}
</style>
