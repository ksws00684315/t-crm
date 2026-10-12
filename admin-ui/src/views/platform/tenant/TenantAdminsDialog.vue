<script setup lang="ts">
import { ElMessage } from "element-plus";
import type { FormInstance, FormRules } from "element-plus";
import { computed, reactive, ref, watch } from "vue";
import { useI18n } from "vue-i18n";
import {
  createTenantAdmin,
  listTenantAdmins,
  resetTenantAdminPassword,
  type TenantAdminVO,
  type TenantVO
} from "@/api/platform/tenant";
import { formatInstant } from "@/utils/format";
import { notifyError } from "@/utils/notify";
import {
  rule,
  serverFieldErrors,
  validateEmail,
  validateNewPassword,
  validateNickname,
  validateUsername
} from "@/utils/validation";

defineOptions({ name: "TenantAdminsDialog" });

const { t } = useI18n();

const props = defineProps<{ modelValue: boolean; tenant: TenantVO | null }>();
const emit = defineEmits<{ (event: "update:modelValue", value: boolean): void }>();

const title = computed(() => t("tenantAdmin.title", { name: props.tenant?.name ?? "" }));
const loading = ref(false);
const rows = ref<TenantAdminVO[]>([]);
const createVisible = ref(false);
const resetVisible = ref(false);
const resettingAdmin = ref<TenantAdminVO | null>(null);
const resetTitle = computed(() =>
  t("tenantAdmin.resetTitle", { username: resettingAdmin.value?.username ?? "" })
);
const createFormRef = ref<FormInstance>();
const resetFormRef = ref<FormInstance>();
const creating = ref(false);
const resetting = ref(false);
const createErrors = ref<Record<string, string>>({});
const resetErrors = ref<Record<string, string>>({});
const createForm = reactive({ username: "", nickname: "", email: "", password: "" });
const resetForm = reactive({ password: "" });
const createRules: FormRules = {
  username: [rule(validateUsername)],
  nickname: [rule(validateNickname)],
  email: [rule(validateEmail)],
  password: [rule(validateNewPassword)]
};
const resetRules: FormRules = { password: [rule(validateNewPassword)] };

async function load() {
  if (!props.tenant) return;
  loading.value = true;
  try {
    rows.value = await listTenantAdmins(props.tenant.id);
  } catch (error) {
    notifyError(error);
  } finally {
    loading.value = false;
  }
}

watch(
  () => [props.modelValue, props.tenant?.id] as const,
  ([open]) => {
    if (open) void load();
  },
  { immediate: true }
);

function close() {
  emit("update:modelValue", false);
}

function openCreate() {
  createErrors.value = {};
  createForm.username = "";
  createForm.nickname = "";
  createForm.email = "";
  createForm.password = "";
  createVisible.value = true;
}

async function submitCreate() {
  createErrors.value = {};
  if (
    !props.tenant ||
    !createFormRef.value ||
    !(await createFormRef.value.validate().catch(() => false))
  ) {
    return;
  }
  creating.value = true;
  try {
    await createTenantAdmin(props.tenant.id, {
      username: createForm.username,
      nickname: createForm.nickname.trim(),
      email: createForm.email.trim() || null,
      password: createForm.password
    });
    ElMessage.success(t("tenantAdmin.created"));
    createVisible.value = false;
    await load();
  } catch (error) {
    createErrors.value = serverFieldErrors(error, ["username", "nickname", "email", "password"]);
    notifyError(error);
  } finally {
    creating.value = false;
  }
}

function openReset(row: TenantAdminVO) {
  resettingAdmin.value = row;
  resetForm.password = "";
  resetErrors.value = {};
  resetVisible.value = true;
}

async function submitReset() {
  if (
    !props.tenant ||
    !resettingAdmin.value ||
    !resetFormRef.value ||
    !(await resetFormRef.value.validate().catch(() => false))
  ) {
    return;
  }
  resetting.value = true;
  resetErrors.value = {};
  try {
    await resetTenantAdminPassword(props.tenant.id, resettingAdmin.value.id, resetForm.password);
    ElMessage.success(t("tenantAdmin.signedOut"));
    resetVisible.value = false;
  } catch (error) {
    resetErrors.value = serverFieldErrors(error, ["password"]);
    notifyError(error);
  } finally {
    resetting.value = false;
  }
}
</script>

<template>
  <el-dialog
    :model-value="modelValue"
    :title="title"
    width="900px"
    @update:model-value="emit('update:modelValue', $event)"
  >
    <div class="actions">
      <el-button type="primary" @click="openCreate">{{ t("tenantAdmin.create") }}</el-button>
    </div>
    <el-table v-loading="loading" :data="rows" row-key="id" border>
      <el-table-column prop="username" :label="t('common.username')" min-width="130" />
      <el-table-column prop="nickname" :label="t('common.nickname')" min-width="120" />
      <el-table-column prop="email" :label="t('common.email')" min-width="180" />
      <el-table-column :label="t('common.status')" width="90">
        <template #default="{ row }">
          <el-tag :type="(row as TenantAdminVO).status === 1 ? 'success' : 'info'">
            {{ (row as TenantAdminVO).status === 1 ? t("common.enabled") : t("common.disabled") }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column :label="t('tenantAdmin.lastLogin')" width="180">
        <template #default="{ row }">
          {{ formatInstant((row as TenantAdminVO).lastLoginTime) }}
        </template>
      </el-table-column>
      <el-table-column :label="t('common.actions')" width="110" fixed="right">
        <template #default="{ row }">
          <el-button link type="warning" @click="openReset(row as TenantAdminVO)">
            {{ t("tenantAdmin.resetPassword") }}
          </el-button>
        </template>
      </el-table-column>
    </el-table>
    <template #footer>
      <el-button @click="close">{{ t("common.close") }}</el-button>
    </template>
  </el-dialog>

  <el-dialog v-model="createVisible" :title="t('tenantAdmin.createTitle')" width="500px">
    <el-form
      ref="createFormRef"
      :model="createForm"
      :rules="createRules"
      label-width="100px"
      @submit.prevent
    >
      <el-form-item :label="t('common.username')" prop="username" :error="createErrors.username">
        <el-input v-model="createForm.username" maxlength="64" />
      </el-form-item>
      <el-form-item :label="t('common.nickname')" prop="nickname" :error="createErrors.nickname">
        <el-input v-model="createForm.nickname" maxlength="64" />
      </el-form-item>
      <el-form-item :label="t('common.email')" prop="email" :error="createErrors.email">
        <el-input v-model="createForm.email" maxlength="254" />
      </el-form-item>
      <el-form-item
        :label="t('tenantAdmin.initialPassword')"
        prop="password"
        :error="createErrors.password"
      >
        <el-input
          v-model="createForm.password"
          type="password"
          show-password
          autocomplete="new-password"
          maxlength="72"
        />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="createVisible = false">{{ t("common.cancel") }}</el-button>
      <el-button type="primary" :loading="creating" @click="submitCreate">{{
        t("common.create")
      }}</el-button>
    </template>
  </el-dialog>

  <el-dialog v-model="resetVisible" :title="resetTitle" width="460px">
    <el-form
      ref="resetFormRef"
      :model="resetForm"
      :rules="resetRules"
      label-width="90px"
      @submit.prevent
    >
      <el-form-item
        :label="t('tenantAdmin.newPassword')"
        prop="password"
        :error="resetErrors.password"
      >
        <el-input
          v-model="resetForm.password"
          type="password"
          show-password
          autocomplete="new-password"
          maxlength="72"
        />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="resetVisible = false">{{ t("common.cancel") }}</el-button>
      <el-button type="primary" :loading="resetting" @click="submitReset">{{
        t("common.confirm")
      }}</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.actions {
  margin-bottom: 12px;
}
</style>
