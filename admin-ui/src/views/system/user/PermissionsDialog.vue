<script setup lang="ts">
import { ElMessage } from "element-plus";
import { computed, ref, watch } from "vue";
import { useI18n } from "vue-i18n";
import {
  getPermissionCatalog,
  getUserPermissions,
  updateUserPermissions,
  type PermissionCatalogVO
} from "@/api/system/user";
import type { DataScope, UserVO } from "@/api/types";
import { notifyError } from "@/utils/notify";
import { postLabel } from "@/utils/permission";
import PermissionPicker from "./PermissionPicker.vue";

defineOptions({ name: "PermissionsDialog" });

const props = defineProps<{
  modelValue: boolean;
  user: UserVO | null;
}>();

const emit = defineEmits<{
  (event: "update:modelValue", value: boolean): void;
  (event: "saved"): void;
}>();

const { t } = useI18n();
const loading = ref(false);
const saving = ref(false);
const catalog = ref<PermissionCatalogVO | null>(null);
const permissions = ref<string[]>([]);
const dataScope = ref<DataScope>("ALL");
let requestSeq = 0;

const dataScopes = computed<{ value: DataScope; label: string }[]>(() => [
  { value: "ALL", label: t("scope.ALL") },
  { value: "OWN", label: t("scope.OWN") }
]);

const canReset = computed(
  () =>
    !!props.user?.postTemplate &&
    !!catalog.value?.templates.some(item => item.code === props.user?.postTemplate)
);

watch(
  () => props.modelValue,
  open => {
    if (!open || !props.user) return;
    void load(props.user.id);
  }
);

async function load(userId: string): Promise<void> {
  const seq = ++requestSeq;
  loading.value = true;
  catalog.value = null;
  try {
    const [nextCatalog, current] = await Promise.all([
      getPermissionCatalog(),
      getUserPermissions(userId)
    ]);
    if (seq !== requestSeq || !props.modelValue) return;
    catalog.value = nextCatalog;
    permissions.value = [...current.permissions];
    dataScope.value = current.dataScope;
  } catch (error) {
    if (seq !== requestSeq) return;
    notifyError(error);
  } finally {
    if (seq === requestSeq) loading.value = false;
  }
}

function close(): void {
  requestSeq += 1;
  emit("update:modelValue", false);
}

/** Copies the saved post template onto the checkboxes. Does not run when the post merely changes. */
function resetFromTemplate(): void {
  const code = props.user?.postTemplate;
  const template = catalog.value?.templates.find(item => item.code === code);
  if (!template) return;
  permissions.value = [...template.permissions];
  dataScope.value = template.dataScope;
}

async function save(): Promise<void> {
  const current = props.user;
  if (!current) return;
  saving.value = true;
  try {
    await updateUserPermissions(current.id, {
      permissions: [...permissions.value],
      dataScope: dataScope.value
    });
    ElMessage.success(t("user.permissionsSaved"));
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
    :title="t('user.permissionsTitle')"
    width="640px"
    @update:model-value="emit('update:modelValue', $event)"
  >
    <div v-loading="loading">
      <p class="perm-post" data-test="current-post">
        {{ t("user.currentPost", { post: postLabel(user?.postTemplate) }) }}
      </p>
      <PermissionPicker v-if="catalog" v-model="permissions" :items="catalog.permissions" />
      <el-form label-position="top">
        <el-form-item :label="t('user.dataScope')">
          <el-radio-group v-model="dataScope" data-test="data-scope">
            <el-radio v-for="scope in dataScopes" :key="scope.value" :value="scope.value">
              {{ scope.label }}
            </el-radio>
          </el-radio-group>
        </el-form-item>
      </el-form>
    </div>
    <template #footer>
      <el-button data-test="reset-template" :disabled="!canReset" @click="resetFromTemplate">
        {{ t("user.resetTemplate") }}
      </el-button>
      <el-button @click="close">{{ t("common.cancel") }}</el-button>
      <el-button type="primary" data-test="save-permissions" :loading="saving" @click="save">
        {{ t("common.save") }}
      </el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.perm-post {
  margin: 0 0 12px;
}
</style>
