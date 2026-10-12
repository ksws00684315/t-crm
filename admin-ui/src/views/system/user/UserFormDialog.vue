<script setup lang="ts">
import { ElMessage } from "element-plus";
import type { FormInstance, FormRules } from "element-plus";
import { computed, reactive, ref, watch } from "vue";
import { useI18n } from "vue-i18n";
import { createUser, editUser } from "@/api/system/user";
import type { CompanyRole, DataScope, UserVO } from "@/api/types";
import { notifyError } from "@/utils/notify";
import { roleLabel, SELECTABLE_ROLES } from "@/utils/permission";
import {
  rule,
  serverFieldErrors,
  validateEmail,
  validateNewPassword,
  validateNickname,
  validateUsername
} from "@/utils/validation";

defineOptions({ name: "UserFormDialog" });

const { t } = useI18n();

const props = defineProps<{
  modelValue: boolean;
  /** The user being edited; null creates a new one. */
  user: UserVO | null;
}>();
const emit = defineEmits<{
  (event: "update:modelValue", value: boolean): void;
  (event: "saved"): void;
}>();

const dataScopes = computed<{ value: DataScope; label: string }[]>(() => [
  { value: "ALL", label: t("scope.ALL") },
  { value: "OWN", label: t("scope.OWN") }
]);

const editing = computed(() => props.user !== null);
const formRef = ref<FormInstance>();
const saving = ref(false);
const serverErrors = ref<Record<string, string>>({});
const form = reactive({
  username: "",
  password: "",
  nickname: "",
  email: "",
  role: "OPERATOR" as CompanyRole,
  dataScope: "ALL" as DataScope
});

const rules = computed<FormRules>(() => ({
  username: editing.value ? [] : [rule(validateUsername)],
  password: editing.value ? [] : [rule(validateNewPassword)],
  nickname: [rule(validateNickname)],
  email: [rule(validateEmail)]
}));

watch(
  () => props.modelValue,
  open => {
    if (!open) return;
    serverErrors.value = {};
    const current = props.user;
    form.username = current?.username ?? "";
    form.password = "";
    form.nickname = current?.nickname ?? "";
    form.email = current?.email ?? "";
    form.role = current?.role === "PLATFORM_ADMIN" ? "OPERATOR" : (current?.role ?? "OPERATOR");
    form.dataScope = current?.dataScope ?? "ALL";
    formRef.value?.clearValidate();
  }
);

function close() {
  emit("update:modelValue", false);
}

async function submit() {
  serverErrors.value = {};
  if (!formRef.value || !(await formRef.value.validate().catch(() => false))) return;
  saving.value = true;
  try {
    const email = form.email.trim() === "" ? null : form.email.trim();
    if (props.user) {
      await editUser(props.user.id, {
        nickname: form.nickname,
        email,
        role: form.role,
        dataScope: form.dataScope
      });
    } else {
      await createUser({
        username: form.username,
        password: form.password,
        nickname: form.nickname,
        email,
        role: form.role,
        dataScope: form.dataScope
      });
    }
    ElMessage.success(props.user ? t("user.saved") : t("user.created"));
    emit("saved");
    close();
  } catch (error) {
    serverErrors.value = serverFieldErrors(error, [
      "username",
      "password",
      "nickname",
      "email",
      "role",
      "dataScope"
    ]);
    notifyError(error);
  } finally {
    saving.value = false;
  }
}
</script>

<template>
  <el-dialog
    :model-value="modelValue"
    :title="editing ? t('user.editTitle') : t('user.createTitle')"
    width="480px"
    @update:model-value="emit('update:modelValue', $event)"
  >
    <el-form ref="formRef" :model="form" :rules="rules" label-width="90px" @submit.prevent>
      <el-form-item :label="t('common.username')" prop="username" :error="serverErrors.username">
        <el-input v-model="form.username" :disabled="editing" maxlength="64" />
      </el-form-item>
      <el-form-item
        v-if="!editing"
        :label="t('user.initialPassword')"
        prop="password"
        :error="serverErrors.password"
      >
        <el-input
          v-model="form.password"
          type="password"
          show-password
          autocomplete="new-password"
          maxlength="72"
        />
      </el-form-item>
      <el-form-item :label="t('common.nickname')" prop="nickname" :error="serverErrors.nickname">
        <el-input v-model="form.nickname" maxlength="64" />
      </el-form-item>
      <el-form-item :label="t('common.email')" prop="email" :error="serverErrors.email">
        <el-input v-model="form.email" maxlength="254" />
      </el-form-item>
      <el-form-item :label="t('user.role')" prop="role" :error="serverErrors.role">
        <el-select v-model="form.role">
          <el-option
            v-for="role in SELECTABLE_ROLES"
            :key="role"
            :label="roleLabel(role)"
            :value="role"
          />
        </el-select>
      </el-form-item>
      <el-form-item :label="t('user.dataScope')" prop="dataScope" :error="serverErrors.dataScope">
        <el-select v-model="form.dataScope">
          <el-option
            v-for="scope in dataScopes"
            :key="scope.value"
            :label="scope.label"
            :value="scope.value"
          />
        </el-select>
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="close">{{ t("common.cancel") }}</el-button>
      <el-button type="primary" :loading="saving" @click="submit">{{ t("common.save") }}</el-button>
    </template>
  </el-dialog>
</template>
