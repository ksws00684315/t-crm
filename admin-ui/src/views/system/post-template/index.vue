<script setup lang="ts">
import { ElMessage, ElMessageBox } from "element-plus";
import { computed, onMounted, reactive, ref } from "vue";
import { useI18n } from "vue-i18n";
import { listPostTemplates, resetPostTemplate, savePostTemplate } from "@/api/system/post-template";
import { getPermissionCatalog, type PermissionItemVO } from "@/api/system/user";
import type { DataScope, PostTemplate } from "@/api/types";
import { notifyError } from "@/utils/notify";
import PermissionPicker from "@/views/system/user/PermissionPicker.vue";

defineOptions({ name: "SystemPostTemplatePage" });

/**
 * Permissions (task 32 §2.3, contract 33 §3.4): the two post templates of this company. Saving
 * only changes what newly created accounts start with; existing accounts keep their grants.
 */
const { t } = useI18n();

const TEMPLATES: PostTemplate[] = ["BD", "OPERATOR"];

const loading = ref(false);
const catalog = ref<PermissionItemVO[]>([]);
/** Local working copies; saving writes one card at a time. */
const drafts = reactive<Record<string, { dataScope: DataScope; permissions: string[] }>>({});
const savingFor = ref<PostTemplate | null>(null);
const isDefault = reactive<Record<string, boolean>>({});

const scopeOptions = computed(() => [
  { label: t("postTemplate.scopeAll"), value: "ALL" },
  { label: t("postTemplate.scopeOwn"), value: "OWN" }
]);

async function load() {
  loading.value = true;
  try {
    const [templates, permissionCatalog] = await Promise.all([
      listPostTemplates(),
      getPermissionCatalog()
    ]);
    catalog.value = permissionCatalog.permissions;
    for (const template of templates) {
      drafts[template.postTemplate] = {
        dataScope: template.dataScope,
        permissions: [...template.permissions]
      };
      isDefault[template.postTemplate] = template.isDefault;
    }
  } catch (error) {
    notifyError(error);
  } finally {
    loading.value = false;
  }
}

function postName(post: PostTemplate): string {
  return t(`post.${post}`);
}

async function save(post: PostTemplate) {
  const draft = drafts[post];
  if (!draft || savingFor.value) return;
  if (draft.permissions.length === 0) {
    ElMessage.warning(t("user.permissionRequired"));
    return;
  }
  savingFor.value = post;
  try {
    await savePostTemplate(post, { dataScope: draft.dataScope, permissions: draft.permissions });
    ElMessage.success(t("postTemplate.saved"));
    await load();
  } catch (error) {
    notifyError(error);
  } finally {
    savingFor.value = null;
  }
}

async function reset(post: PostTemplate) {
  try {
    await ElMessageBox.confirm(t("postTemplate.resetConfirm"), t("postTemplate.reset"), {
      type: "warning"
    });
  } catch {
    return;
  }
  try {
    await resetPostTemplate(post);
    ElMessage.success(t("postTemplate.resetDone"));
    await load();
  } catch (error) {
    notifyError(error);
  }
}

onMounted(load);
</script>

<template>
  <div class="list-page post-template-page" v-loading="loading">
    <el-alert
      class="post-template-page__hint"
      type="info"
      :closable="false"
      :title="t('postTemplate.hint')"
      data-test="post-template-hint"
    />
    <div class="post-template-page__cards">
      <template v-for="post in TEMPLATES" :key="post">
        <el-card v-if="drafts[post]" class="post-template-page__card" shadow="never">
          <template #header>
            <div class="post-template-page__head">
              <span>{{ t("postTemplate.cardTitle", { post: postName(post) }) }}</span>
              <el-tag :type="isDefault[post] ? 'info' : 'warning'" data-test="template-state">
                {{ isDefault[post] ? t("postTemplate.isDefault") : t("postTemplate.customized") }}
              </el-tag>
            </div>
          </template>
          <el-form label-width="110px" @submit.prevent>
            <el-form-item :label="t('postTemplate.dataScope')">
              <el-radio-group v-model="drafts[post]!.dataScope" :data-test="`scope-${post}`">
                <el-radio-button
                  v-for="option in scopeOptions"
                  :key="option.value"
                  :value="option.value"
                >
                  {{ option.label }}
                </el-radio-button>
              </el-radio-group>
            </el-form-item>
            <el-form-item :label="t('postTemplate.permissions')">
              <PermissionPicker
                :items="catalog"
                :model-value="drafts[post]!.permissions"
                @update:model-value="drafts[post]!.permissions = $event"
              />
            </el-form-item>
          </el-form>
          <div class="post-template-page__actions">
            <el-button @click="reset(post)" data-test="template-reset">{{
              t("postTemplate.reset")
            }}</el-button>
            <el-button
              type="primary"
              :loading="savingFor === post"
              :data-test="`template-save-${post}`"
              @click="save(post)"
              >{{ t("postTemplate.save") }}</el-button
            >
          </div>
        </el-card>
      </template>
    </div>
  </div>
</template>

<style scoped>
.post-template-page__hint {
  margin-bottom: var(--tcrm-space-3);
}
.post-template-page__cards {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(420px, 1fr));
  gap: var(--tcrm-space-3);
  align-items: start;
}
.post-template-page__card {
  min-width: 0;
}
.post-template-page__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.post-template-page__actions {
  display: flex;
  justify-content: flex-end;
  gap: var(--tcrm-space-2);
}
</style>
