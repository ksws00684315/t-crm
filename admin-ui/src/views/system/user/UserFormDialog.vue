<script setup lang="ts">
import { ElMessage } from "element-plus";
import type { FormInstance, FormRules } from "element-plus";
import { computed, reactive, ref, watch } from "vue";
import { useI18n } from "vue-i18n";
import {
  createUser,
  editUser,
  getPermissionCatalog,
  type PermissionCatalogVO
} from "@/api/system/user";
import type { CompanyRole, DataScope, PostTemplate, UserVO } from "@/api/types";
import { isMultiCompany } from "@/utils/company";
import { ApiError } from "@/utils/http/error";
import { notifyError } from "@/utils/notify";
import { POST_TEMPLATES, postLabel, roleLabel, SELECTABLE_ROLES } from "@/utils/permission";
import {
  rule,
  serverFieldErrors,
  validateEmail,
  validateNewPassword,
  validateNickname,
  validateUsername
} from "@/utils/validation";
import PermissionPicker from "./PermissionPicker.vue";
import PermissionsDialog from "./PermissionsDialog.vue";

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
/** Name and email belong to the person; another company also uses them (ADR-022). */
const personalLocked = computed(() => isMultiCompany(props.user));
const formRef = ref<FormInstance>();
const saving = ref(false);
const permissionsVisible = ref(false);
const serverErrors = ref<Record<string, string>>({});
const catalog = ref<PermissionCatalogVO | null>(null);
const form = reactive({
  username: "",
  password: "",
  nickname: "",
  email: "",
  role: "STAFF" as CompanyRole,
  postTemplate: "" as PostTemplate | "",
  dataScope: "ALL" as DataScope,
  permissions: [] as string[]
});
let catalogSeq = 0;

const rules = computed<FormRules>(() => ({
  username: editing.value ? [] : [rule(validateUsername)],
  password: editing.value ? [] : [rule(validateNewPassword)],
  nickname: personalLocked.value ? [] : [rule(validateNickname)],
  email: personalLocked.value ? [] : [rule(validateEmail)],
  postTemplate: [
    {
      validator: (_rule, value: string, callback: (error?: Error) => void) => {
        if (form.role === "STAFF" && !value) callback(new Error(t("user.templateRequired")));
        else callback();
      },
      trigger: "change"
    }
  ]
}));

const showPermissions = computed(
  () => !editing.value && form.role === "STAFF" && form.postTemplate !== "" && catalog.value != null
);

watch(
  () => props.modelValue,
  open => {
    if (!open) {
      catalogSeq += 1;
      return;
    }
    serverErrors.value = {};
    const current = props.user;
    form.username = current?.username ?? "";
    form.password = "";
    form.nickname = current?.nickname ?? "";
    form.email = current?.email ?? "";
    form.role = current?.role === "SUPER_ADMIN" ? "SUPER_ADMIN" : "STAFF";
    form.postTemplate = current?.role === "STAFF" ? (current.postTemplate ?? "") : "";
    form.dataScope = current?.dataScope ?? "ALL";
    form.permissions = [];
    catalog.value = null;
    formRef.value?.clearValidate();
    if (!current) void loadCatalog();
  }
);

watch(
  () => form.postTemplate,
  template => {
    if (editing.value) return;
    if (!template) {
      form.permissions = [];
      return;
    }
    applyTemplate(template);
  }
);

watch(catalog, () => {
  if (editing.value || !form.postTemplate || form.permissions.length > 0) return;
  applyTemplate(form.postTemplate);
});

async function loadCatalog(): Promise<void> {
  const seq = ++catalogSeq;
  try {
    const result = await getPermissionCatalog();
    if (seq !== catalogSeq || !props.modelValue) return;
    catalog.value = result;
  } catch (error) {
    if (seq !== catalogSeq) return;
    notifyError(error);
  }
}

function applyTemplate(code: string): void {
  const template = catalog.value?.templates.find(item => item.code === code);
  if (!template) return;
  form.permissions = [...template.permissions];
  form.dataScope = template.dataScope;
}

function close(): void {
  emit("update:modelValue", false);
}

async function submit(): Promise<void> {
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
        postTemplate: form.role === "STAFF" ? form.postTemplate || null : null
      });
    } else if (form.role === "SUPER_ADMIN") {
      await createUser({
        username: form.username,
        password: form.password,
        nickname: form.nickname,
        email,
        role: "SUPER_ADMIN"
      });
    } else {
      await createUser({
        username: form.username,
        password: form.password,
        nickname: form.nickname,
        email,
        role: "STAFF",
        postTemplate: form.postTemplate || null,
        dataScope: form.dataScope,
        permissions: [...form.permissions]
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
      "postTemplate",
      "dataScope",
      "permissions"
    ]);
    if (!props.user && error instanceof ApiError && error.errorCode === "USER_USERNAME_EXISTS") {
      ElMessage.error(t("user.usernameExistsHint", { message: error.displayMessage }));
    } else {
      notifyError(error);
    }
  } finally {
    saving.value = false;
  }
}
</script>

<template>
  <el-dialog
    :model-value="modelValue"
    :title="editing ? t('user.editTitle') : t('user.createTitle')"
    :width="editing ? '480px' : '640px'"
    @update:model-value="emit('update:modelValue', $event)"
  >
    <el-form ref="formRef" :model="form" :rules="rules" label-width="110px" @submit.prevent>
      <el-form-item :label="t('common.username')" prop="username" :error="serverErrors.username">
        <el-input v-model="form.username" :disabled="editing" maxlength="64" data-test="username" />
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
          data-test="password"
        />
      </el-form-item>
      <el-form-item :label="t('common.nickname')" prop="nickname" :error="serverErrors.nickname">
        <el-input
          v-model="form.nickname"
          maxlength="64"
          :disabled="personalLocked"
          data-test="nickname"
        />
      </el-form-item>
      <el-form-item :label="t('common.email')" prop="email" :error="serverErrors.email">
        <el-input
          v-model="form.email"
          maxlength="254"
          :disabled="personalLocked"
          data-test="email"
        />
      </el-form-item>
      <p v-if="personalLocked" class="perm-note" data-test="personal-locked">
        {{ t("user.multiCompanyHint") }}
      </p>
      <el-form-item :label="t('user.role')" prop="role" :error="serverErrors.role">
        <el-select v-model="form.role" data-test="account-type">
          <el-option
            v-for="role in SELECTABLE_ROLES"
            :key="role"
            :label="roleLabel(role)"
            :value="role"
          />
        </el-select>
      </el-form-item>
      <el-form-item
        v-if="form.role === 'STAFF'"
        :label="t('user.postTemplate')"
        prop="postTemplate"
        :error="serverErrors.postTemplate"
      >
        <el-select v-model="form.postTemplate" data-test="post-template">
          <el-option
            v-for="post in POST_TEMPLATES"
            :key="post"
            :label="postLabel(post)"
            :value="post"
          />
        </el-select>
      </el-form-item>
      <p v-if="form.role === 'SUPER_ADMIN'" class="all-permissions" data-test="all-permissions">
        {{ t("user.allPermissions") }}
      </p>
      <div
        v-else-if="editing && props.user?.role === 'STAFF'"
        class="edit-permissions"
        data-test="permissions-in-edit"
      >
        <p class="perm-note">{{ t("user.permissionsSeparate") }}</p>
        <el-button
          link
          type="primary"
          data-test="open-user-permissions"
          @click="permissionsVisible = true"
        >
          {{ t("user.permissionsTitle") }}
        </el-button>
      </div>
      <template v-if="showPermissions">
        <div class="perm-scroll" data-test="permission-area">
          <PermissionPicker v-model="form.permissions" :items="catalog?.permissions ?? []" />
        </div>
        <el-form-item :label="t('user.dataScope')" prop="dataScope" :error="serverErrors.dataScope">
          <el-radio-group v-model="form.dataScope" data-test="data-scope">
            <el-radio v-for="scope in dataScopes" :key="scope.value" :value="scope.value">
              {{ scope.label }}
            </el-radio>
          </el-radio-group>
        </el-form-item>
      </template>
    </el-form>
    <template #footer>
      <el-button @click="close">{{ t("common.cancel") }}</el-button>
      <el-button type="primary" data-test="save-user" :loading="saving" @click="submit">
        {{ t("common.save") }}
      </el-button>
    </template>
  </el-dialog>
  <PermissionsDialog v-model="permissionsVisible" :user="props.user" @saved="emit('saved')" />
</template>

<style scoped>
.all-permissions,
.perm-note {
  margin: 0 0 12px;
  color: var(--el-text-color-secondary);
}

.perm-scroll {
  max-height: 360px;
  overflow: auto;
  margin-bottom: 12px;
}

.edit-permissions {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}
</style>
