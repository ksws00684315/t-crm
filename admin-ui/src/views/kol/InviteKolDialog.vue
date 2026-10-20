<script setup lang="ts">
import { ElMessage, ElMessageBox } from "element-plus";
import { ref, watch } from "vue";
import { useI18n } from "vue-i18n";
import { getRegisterCode, rotateRegisterCode, type RegisterCodeVO } from "@/api/kol/kol";
import CopyText from "@/components/CopyText.vue";
import { useUserStore } from "@/stores/user";
import { accessOf } from "@/utils/permission";
import { notifyError } from "@/utils/notify";

defineOptions({ name: "InviteKolDialog" });

/**
 * The "+ invite creator" dialog (01.01, task 32 §2.2): the exclusive invitation code, the registration link,
 * a QR-code placeholder (under development) and the regenerate action. The super administrator
 * gets the extra hint that registrations through this link stay unassigned.
 */
const props = defineProps<{ modelValue: boolean }>();
const emit = defineEmits<{ (event: "update:modelValue", value: boolean): void }>();

const { t } = useI18n();
const me = useUserStore();
const isSuperAdmin = accessOf(me.user).isSuperAdmin;
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
    <!-- The auto-assign hint would contradict the super-administrator note below it, so a
         super administrator sees only that note (2026-10-20 review item 3). -->
    <p v-if="!isSuperAdmin" class="hint">{{ t("kol.registerLinkHint") }}</p>
    <el-alert
      v-if="isSuperAdmin"
      class="hint"
      type="info"
      :closable="false"
      :title="t('kol.registerSuperHint')"
      data-test="super-invite-hint"
    />
    <div v-loading="loading" class="fields">
      <div v-if="link" class="field" data-test="register-code">
        <div class="label">{{ t("kol.registerInviteCode") }}</div>
        <CopyText :value="link.code" />
      </div>
      <div v-if="link" class="field" data-test="register-url">
        <div class="label">{{ t("kol.registerLinkUrl") }}</div>
        <CopyText :value="link.url" />
      </div>
      <div class="field" data-test="register-qr">
        <div class="label">{{ t("kol.registerQr") }}</div>
        <div class="qr">{{ t("common.developing") }}</div>
      </div>
    </div>
    <template #footer>
      <el-button @click="emit('update:modelValue', false)">{{ t("common.close") }}</el-button>
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
.fields {
  display: flex;
  flex-direction: column;
  gap: 16px;
}
.field .label {
  margin-bottom: 6px;
  font-weight: 500;
}
.qr {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 120px;
  height: 120px;
  border: 1px dashed var(--tcrm-color-border);
  border-radius: var(--tcrm-radius-control);
  color: var(--tcrm-color-text-secondary);
}
</style>
