<script setup lang="ts">
import { ElMessage, ElMessageBox } from "element-plus";
import { ref, watch } from "vue";
import { useI18n } from "vue-i18n";
import { getRegisterCode, rotateRegisterCode, type RegisterCodeVO } from "@/api/kol/kol";
import CopyText from "@/components/CopyText.vue";
import { notifyError } from "@/utils/notify";

defineOptions({ name: "RegisterLinkDialog" });

const props = defineProps<{ modelValue: boolean }>();
const emit = defineEmits<{ (event: "update:modelValue", value: boolean): void }>();

const { t } = useI18n();
const loading = ref(false);
const rotating = ref(false);
const link = ref<RegisterCodeVO | null>(null);

watch(
  () => props.modelValue,
  open => {
    if (!open) return;
    link.value = null;
    void load();
  }
);

async function load() {
  loading.value = true;
  try {
    link.value = await getRegisterCode();
  } catch (error) {
    notifyError(error);
  } finally {
    loading.value = false;
  }
}

function close() {
  emit("update:modelValue", false);
}

async function rotate() {
  try {
    await ElMessageBox.confirm(t("kol.registerLinkConfirm"), t("kol.registerLinkTitle"), {
      type: "warning"
    });
  } catch {
    return;
  }
  rotating.value = true;
  try {
    link.value = await rotateRegisterCode();
    ElMessage.success(t("kol.registerLinkRotated"));
  } catch (error) {
    notifyError(error);
  } finally {
    rotating.value = false;
  }
}
</script>

<template>
  <el-dialog
    :model-value="modelValue"
    :title="t('kol.registerLinkTitle')"
    width="560px"
    @update:model-value="emit('update:modelValue', $event)"
  >
    <p class="hint">{{ t("kol.registerLinkHint") }}</p>
    <div v-loading="loading" data-test="register-url">
      <CopyText v-if="link" :value="link.url" />
    </div>
    <template #footer>
      <el-button @click="close">{{ t("common.close") }}</el-button>
      <el-button type="warning" data-test="rotate-link" :loading="rotating" @click="rotate">
        {{ t("kol.registerLinkRotate") }}
      </el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.hint {
  margin: 0 0 12px;
}
</style>
