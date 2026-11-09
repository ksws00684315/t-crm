<script setup lang="ts">
import { ElMessage } from "element-plus";
import type { FormInstance, FormRules } from "element-plus";
import { computed, nextTick, onMounted, reactive, ref, watch } from "vue";
import { useI18n } from "vue-i18n";
import { useRoute, useRouter } from "vue-router";
import {
  createLibraryKol,
  getLibraryKol,
  updateLibraryKol,
  type LibraryDuplicate,
  type LibraryKolRequest,
  type LibraryKolDetailVO
} from "@/api/kol-library/kol";
import { useTabsStore } from "@/stores/tabs";
import { ApiError } from "@/utils/http/error";
import {
  LIBRARY_AUDIENCE_AGES,
  LIBRARY_AUDIENCE_GENDERS,
  LIBRARY_CATEGORIES,
  LIBRARY_COOPERATION_STAGES,
  LIBRARY_COUNTRIES,
  LIBRARY_GRADES,
  LIBRARY_LEVELS,
  LIBRARY_OUTREACH_STATUSES,
  LIBRARY_PLATFORMS,
  LIBRARY_SAMPLE_STATUSES,
  libraryDefaultValue,
  libraryOptionLabel
} from "@/utils/kol-library";
import { notifyError } from "@/utils/notify";

defineOptions({ name: "LibraryKolFormPage" });

const { t } = useI18n();
const route = useRoute();
const router = useRouter();
const tabs = useTabsStore();

const editingId = computed(() => (typeof route.params.id === "string" ? route.params.id : null));
const editing = computed(() => editingId.value !== null);
const title = computed(() => (editing.value ? t("library.editTitle") : t("library.createTitle")));

const detailTabPath = computed(() =>
  editingId.value ? `/kol-library/kols/${editingId.value}` : null
);

const formRef = ref<FormInstance>();
const loading = ref(false);
const loadFailed = ref(false);
const saving = ref(false);
const serverErrors = ref<Record<string, string>>({});
/** Set when the creator ID is already in the library: the form offers a link to it. */
const duplicate = ref<LibraryDuplicate | null>(null);

function emptyForm(): LibraryKolRequest {
  return {
    platform: libraryDefaultValue(LIBRARY_PLATFORMS),
    kolUsername: "",
    displayName: "",
    followers: null,
    countryCode: libraryDefaultValue(LIBRARY_COUNTRIES),
    profileUrl: null,
    categories: [],
    avgViews: null,
    engagementRate: null,
    audienceCountry: null,
    audienceAgeRange: null,
    audienceGender: null,
    contact: null,
    contentQuality: null,
    salesPotential: null,
    cooperationAttitude: null,
    grade: null,
    cooperationStage: null,
    tags: [],
    remark: null,
    outreachStatus: libraryDefaultValue(LIBRARY_OUTREACH_STATUSES),
    followUpOwner: "",
    lastContactDate: null,
    sampleStatus: libraryDefaultValue(LIBRARY_SAMPLE_STATUSES),
    trackingNo: null,
    sampleDate: null
  };
}

const form = reactive<LibraryKolRequest>(emptyForm());

/**
 * The edit tab names the creator being edited: the route's title key alone would show as
 * "route.libraryKolEdit" in the tab strip.
 */
watch(
  () => form.displayName,
  name => {
    const tab = tabs.tabs.find(item => item.path === route.path);
    if (!editing.value || !tab || !name.trim()) return;
    tabs.open({
      ...tab,
      titleKey: `${t("route.libraryKolEdit")}: ${name.trim()}`,
      literalTitle: true
    });
  },
  { flush: "post" }
);
/** The free tag typed in the input, added with Enter or the Add button. */
const tagInput = ref("");

/** Only the fields a blank submit would miss are checked here; the rest is length and format. */
const rules = computed<FormRules>(() => ({
  kolUsername: [
    {
      validator: (_rule, value: string, callback: (error?: Error) => void) => {
        if (!value.trim()) callback(new Error(t("library.validation.usernameRequired")));
        else callback();
      },
      trigger: "blur"
    }
  ],
  displayName: [
    {
      validator: (_rule, value: string, callback: (error?: Error) => void) => {
        if (!value.trim()) callback(new Error(t("library.validation.nameRequired")));
        else callback();
      },
      trigger: "blur"
    }
  ],
  categories: [
    {
      validator: (_rule, value: string[], callback: (error?: Error) => void) => {
        if (!value.length) callback(new Error(t("library.validation.categoriesRequired")));
        else callback();
      },
      trigger: "change"
    }
  ],
  followUpOwner: [
    {
      validator: (_rule, value: string, callback: (error?: Error) => void) => {
        if (!value.trim()) callback(new Error(t("library.validation.ownerRequired")));
        else callback();
      },
      trigger: "blur"
    }
  ]
}));

function fill(detail: LibraryKolDetailVO): void {
  Object.assign(form, {
    platform: detail.platform,
    kolUsername: detail.kolUsername,
    displayName: detail.displayName,
    followers: detail.followers,
    countryCode: detail.countryCode,
    profileUrl: detail.profileUrl,
    categories: [...detail.categories],
    avgViews: detail.avgViews,
    engagementRate: detail.engagementRate,
    audienceCountry: detail.audienceCountry,
    audienceAgeRange: detail.audienceAgeRange,
    audienceGender: detail.audienceGender,
    contact: detail.contact,
    contentQuality: detail.contentQuality,
    salesPotential: detail.salesPotential,
    cooperationAttitude: detail.cooperationAttitude,
    grade: detail.grade,
    cooperationStage: detail.cooperationStage,
    tags: [...detail.tags],
    remark: detail.remark,
    outreachStatus: detail.outreachStatus,
    followUpOwner: detail.followUpOwner,
    lastContactDate: detail.lastContactDate,
    sampleStatus: detail.sampleStatus,
    trackingNo: detail.trackingNo,
    sampleDate: detail.sampleDate
  });
}

async function load(): Promise<void> {
  if (!editingId.value) {
    Object.assign(form, emptyForm());
    duplicate.value = null;
    return;
  }
  loading.value = true;
  loadFailed.value = false;
  try {
    fill(await getLibraryKol(editingId.value));
  } catch (error) {
    loadFailed.value = true;
    notifyError(error);
  } finally {
    loading.value = false;
  }
}

function addTag(): void {
  const tag = tagInput.value.trim();
  if (!tag) return;
  if (form.tags.length >= 10) {
    ElMessage.warning(t("library.validation.tagsLimit"));
    return;
  }
  if (form.tags.includes(tag)) {
    tagInput.value = "";
    return;
  }
  form.tags = [...form.tags, tag];
  tagInput.value = "";
}

function removeTag(tag: string): void {
  form.tags = form.tags.filter(item => item !== tag);
}

/** Blanks become null so the API clears the field instead of storing an empty string. */
function toRequest(): LibraryKolRequest {
  const clean = (value: string | null): string | null => {
    const text = (value ?? "").trim();
    return text === "" ? null : text;
  };
  return {
    ...form,
    kolUsername: form.kolUsername.trim(),
    displayName: form.displayName.trim(),
    profileUrl: clean(form.profileUrl),
    audienceCountry: clean(form.audienceCountry),
    contact: clean(form.contact),
    remark: clean(form.remark),
    followUpOwner: form.followUpOwner.trim(),
    trackingNo: clean(form.trackingNo),
    followers: form.followers === null || `${form.followers}` === "" ? null : form.followers,
    avgViews: form.avgViews === null || `${form.avgViews}` === "" ? null : form.avgViews,
    engagementRate:
      form.engagementRate === null || form.engagementRate === "" ? null : form.engagementRate
  };
}

async function submit(): Promise<void> {
  serverErrors.value = {};
  duplicate.value = null;
  if (!formRef.value || !(await formRef.value.validate().catch(() => false))) return;
  saving.value = true;
  try {
    const saved = editingId.value
      ? await updateLibraryKol(editingId.value, toRequest())
      : await createLibraryKol(toRequest());
    ElMessage.success(editing.value ? t("library.saved") : t("library.created"));
    // The form tab closes and the creator's detail tab opens; an already open list is reloaded, and
    // an already open detail (editing an existing creator) reloads its data instead of showing
    // what it cached before the change.
    const detailPath = `/kol-library/kols/${saved.id}`;
    tabs.refresh("/kol-library/kols");
    if (editing.value) tabs.refresh(detailPath);
    tabs.close(route.path);
    tabs.open({
      path: detailPath,
      titleKey: saved.displayName,
      literalTitle: true,
      affix: false,
      componentName: "LibraryKolDetailPage"
    });
    await router.push(detailPath);
  } catch (error) {
    if (error instanceof ApiError && error.errorCode === "KOL_LIBRARY_DUPLICATE") {
      duplicate.value = error.payload as LibraryDuplicate | null;
    }
    notifyError(error);
  } finally {
    saving.value = false;
  }
}

function cancel(): void {
  tabs.close(route.path);
  void router.push(detailTabPath.value ?? "/kol-library/kols");
}

/** Opens the existing creator of the duplicate answer in a tab of its own. */
async function openDuplicate(): Promise<void> {
  const found = duplicate.value;
  if (!found) return;
  const path = `/kol-library/kols/${found.id}`;
  tabs.open({
    path,
    titleKey: found.displayName || t("route.libraryKolDetail"),
    literalTitle: Boolean(found.displayName),
    affix: false,
    componentName: "LibraryKolDetailPage"
  });
  await router.push(path);
}

watch(
  () => route.path,
  () => void load(),
  { immediate: true }
);
onMounted(() => {
  void nextTick(() => formRef.value?.clearValidate());
});
</script>

<template>
  <div class="form-page" data-test="library-form">
    <el-alert
      v-if="duplicate"
      class="form-page__duplicate"
      type="warning"
      :closable="false"
      data-test="library-duplicate"
    >
      <template #title>
        {{ t("library.duplicate", { owner: duplicate.followUpOwner || "-" }) }}
        <el-button link type="primary" data-test="library-duplicate-open" @click="openDuplicate">
          {{ t("library.view") }}
        </el-button>
      </template>
    </el-alert>
    <el-alert v-if="loadFailed" type="error" :closable="false" class="form-page__duplicate">
      <template #title>
        {{ t("table.loadFailed") }}
        <el-button link type="primary" @click="load">{{ t("table.retry") }}</el-button>
      </template>
    </el-alert>

    <el-form
      ref="formRef"
      v-loading="loading"
      :model="form"
      :rules="rules"
      label-position="top"
      class="form-page__form"
      @submit.prevent
    >
      <header class="form-page__header">
        <h1 class="form-page__title">{{ title }}</h1>
      </header>

      <!-- Requirement 1.2.5 ④: one card per group, single column inside each card (norm 13 §3.5). -->
      <el-card shadow="never" class="form-card" data-test="group-basic">
        <template #header>{{ t("library.group.basic") }}</template>
        <div class="form-card__grid">
          <el-form-item :label="t('library.field.kolUsername')" prop="kolUsername">
            <el-input
              v-model="form.kolUsername"
              maxlength="64"
              data-test="kol-username"
              :placeholder="t('library.field.kolUsernamePlaceholder')"
            />
          </el-form-item>
          <el-form-item :label="t('library.field.displayName')" prop="displayName">
            <el-input v-model="form.displayName" maxlength="100" data-test="display-name" />
          </el-form-item>
          <el-form-item :label="t('library.field.platform')" prop="platform">
            <el-select v-model="form.platform" data-test="platform">
              <el-option
                v-for="option in LIBRARY_PLATFORMS"
                :key="option.value"
                :label="libraryOptionLabel('platform', option.value)"
                :value="option.value"
              />
            </el-select>
          </el-form-item>
          <el-form-item :label="t('library.field.country')" prop="countryCode">
            <el-select v-model="form.countryCode" filterable data-test="country">
              <el-option
                v-for="option in LIBRARY_COUNTRIES"
                :key="option.value"
                :label="libraryOptionLabel('country', option.value)"
                :value="option.value"
              />
            </el-select>
          </el-form-item>
          <el-form-item :label="t('library.field.categories')" prop="categories">
            <el-select
              v-model="form.categories"
              multiple
              collapse-tags
              collapse-tags-tooltip
              data-test="categories"
            >
              <el-option
                v-for="option in LIBRARY_CATEGORIES"
                :key="option.value"
                :label="libraryOptionLabel('category', option.value)"
                :value="option.value"
              />
            </el-select>
          </el-form-item>
          <el-form-item :label="t('library.field.followers')" prop="followers">
            <el-input
              v-model="form.followers"
              type="number"
              min="0"
              step="1"
              data-test="followers"
            />
          </el-form-item>
          <el-form-item :label="t('library.field.avgViews')" prop="avgViews">
            <el-input
              v-model="form.avgViews"
              type="number"
              min="0"
              step="1"
              data-test="avg-views"
            />
          </el-form-item>
          <el-form-item :label="t('library.field.engagementRate')" prop="engagementRate">
            <el-input
              v-model="form.engagementRate"
              type="number"
              min="0"
              max="100"
              step="0.01"
              data-test="engagement-rate"
            />
          </el-form-item>
          <el-form-item :label="t('library.field.profileUrl')" prop="profileUrl">
            <el-input
              v-model="form.profileUrl"
              maxlength="512"
              data-test="profile-url"
              :placeholder="t('library.field.profileUrlPlaceholder')"
            />
          </el-form-item>
        </div>
      </el-card>

      <el-card shadow="never" class="form-card" data-test="group-audience">
        <template #header>{{ t("library.group.audience") }}</template>
        <div class="form-card__grid">
          <el-form-item :label="t('library.field.audienceCountry')" prop="audienceCountry">
            <el-input v-model="form.audienceCountry" maxlength="64" data-test="audience-country" />
          </el-form-item>
          <el-form-item :label="t('library.field.audienceAgeRange')" prop="audienceAgeRange">
            <el-select v-model="form.audienceAgeRange" clearable data-test="audience-age">
              <el-option
                v-for="option in LIBRARY_AUDIENCE_AGES"
                :key="option.value"
                :label="libraryOptionLabel('audienceAge', option.value)"
                :value="option.value"
              />
            </el-select>
          </el-form-item>
          <el-form-item :label="t('library.field.audienceGender')" prop="audienceGender">
            <el-select v-model="form.audienceGender" clearable data-test="audience-gender">
              <el-option
                v-for="option in LIBRARY_AUDIENCE_GENDERS"
                :key="option.value"
                :label="libraryOptionLabel('audienceGender', option.value)"
                :value="option.value"
              />
            </el-select>
          </el-form-item>
        </div>
      </el-card>

      <el-card shadow="never" class="form-card" data-test="group-contact">
        <template #header>{{ t("library.group.contact") }}</template>
        <div class="form-card__grid">
          <el-form-item :label="t('library.field.contact')" prop="contact">
            <el-input
              v-model="form.contact"
              maxlength="128"
              data-test="contact"
              :placeholder="t('library.field.contactPlaceholder')"
            />
          </el-form-item>
        </div>
      </el-card>

      <el-card shadow="never" class="form-card" data-test="group-quality">
        <template #header>{{ t("library.group.quality") }}</template>
        <div class="form-card__grid">
          <el-form-item :label="t('library.field.contentQuality')" prop="contentQuality">
            <el-select v-model="form.contentQuality" clearable data-test="content-quality">
              <el-option
                v-for="option in LIBRARY_LEVELS"
                :key="option.value"
                :label="libraryOptionLabel('level', option.value)"
                :value="option.value"
              />
            </el-select>
          </el-form-item>
          <el-form-item :label="t('library.field.salesPotential')" prop="salesPotential">
            <el-select v-model="form.salesPotential" clearable data-test="sales-potential">
              <el-option
                v-for="option in LIBRARY_LEVELS"
                :key="option.value"
                :label="libraryOptionLabel('level', option.value)"
                :value="option.value"
              />
            </el-select>
          </el-form-item>
          <el-form-item :label="t('library.field.cooperationAttitude')" prop="cooperationAttitude">
            <el-select v-model="form.cooperationAttitude" clearable data-test="attitude">
              <el-option
                v-for="option in LIBRARY_LEVELS"
                :key="option.value"
                :label="libraryOptionLabel('level', option.value)"
                :value="option.value"
              />
            </el-select>
          </el-form-item>
          <el-form-item :label="t('library.field.grade')" prop="grade">
            <el-select v-model="form.grade" clearable data-test="grade">
              <el-option
                v-for="option in LIBRARY_GRADES"
                :key="option.value"
                :label="libraryOptionLabel('grade', option.value)"
                :value="option.value"
              />
            </el-select>
          </el-form-item>
          <el-form-item :label="t('library.field.cooperationStage')" prop="cooperationStage">
            <el-select v-model="form.cooperationStage" clearable data-test="cooperation-stage">
              <el-option
                v-for="option in LIBRARY_COOPERATION_STAGES"
                :key="option.value"
                :label="libraryOptionLabel('cooperationStage', option.value)"
                :value="option.value"
              />
            </el-select>
          </el-form-item>
        </div>
      </el-card>

      <el-card shadow="never" class="form-card" data-test="group-tags">
        <template #header>{{ t("library.group.tags") }}</template>
        <div class="form-card__grid">
          <el-form-item :label="t('library.field.tags')">
            <div class="tags-editor">
              <el-tag
                v-for="tag in form.tags"
                :key="tag"
                closable
                data-test="tag-item"
                @close="removeTag(tag)"
              >
                {{ tag }}
              </el-tag>
              <el-input
                v-model="tagInput"
                maxlength="20"
                class="tags-editor__input"
                data-test="tag-input"
                :placeholder="t('library.field.tagPlaceholder')"
                @keyup.enter.prevent="addTag"
              />
              <el-button data-test="tag-add" @click="addTag">{{ t("library.addTag") }}</el-button>
            </div>
          </el-form-item>
          <el-form-item :label="t('library.field.remark')" prop="remark">
            <el-input
              v-model="form.remark"
              type="textarea"
              :rows="3"
              maxlength="1000"
              show-word-limit
              data-test="remark"
            />
          </el-form-item>
        </div>
      </el-card>

      <el-card shadow="never" class="form-card" data-test="group-follow-up">
        <template #header>{{ t("library.group.followUp") }}</template>
        <div class="form-card__grid">
          <el-form-item :label="t('library.field.outreachStatus')" prop="outreachStatus">
            <el-select v-model="form.outreachStatus" data-test="outreach-status">
              <el-option
                v-for="option in LIBRARY_OUTREACH_STATUSES"
                :key="option.value"
                :label="libraryOptionLabel('outreachStatus', option.value)"
                :value="option.value"
              />
            </el-select>
          </el-form-item>
          <el-form-item :label="t('library.field.followUpOwner')" prop="followUpOwner">
            <el-input v-model="form.followUpOwner" maxlength="64" data-test="follow-up-owner" />
          </el-form-item>
          <el-form-item :label="t('library.field.lastContactDate')" prop="lastContactDate">
            <el-date-picker
              v-model="form.lastContactDate"
              type="date"
              value-format="YYYY-MM-DD"
              data-test="last-contact-date"
            />
          </el-form-item>
          <el-form-item :label="t('library.field.sampleStatus')" prop="sampleStatus">
            <el-select v-model="form.sampleStatus" data-test="sample-status">
              <el-option
                v-for="option in LIBRARY_SAMPLE_STATUSES"
                :key="option.value"
                :label="libraryOptionLabel('sampleStatus', option.value)"
                :value="option.value"
              />
            </el-select>
          </el-form-item>
          <el-form-item :label="t('library.field.trackingNo')" prop="trackingNo">
            <el-input v-model="form.trackingNo" maxlength="64" data-test="tracking-no" />
          </el-form-item>
          <el-form-item :label="t('library.field.sampleDate')" prop="sampleDate">
            <el-date-picker
              v-model="form.sampleDate"
              type="date"
              value-format="YYYY-MM-DD"
              data-test="sample-date"
            />
          </el-form-item>
        </div>
      </el-card>

      <footer class="form-page__actions">
        <el-button data-test="form-cancel" @click="cancel">{{ t("common.cancel") }}</el-button>
        <el-button type="primary" :loading="saving" data-test="form-save" @click="submit">
          {{ t("common.save") }}
        </el-button>
      </footer>
    </el-form>
  </div>
</template>

<style scoped>
.form-page {
  display: flex;
  flex-direction: column;
  gap: var(--tcrm-space-4);
  max-width: 960px;
}
.form-page__duplicate {
  margin-bottom: var(--tcrm-space-2);
}
.form-page__form {
  display: flex;
  flex-direction: column;
  gap: var(--tcrm-space-4);
}
.form-page__header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.form-page__title {
  margin: 0;
}
/* Requirement 1.2.5 ④ and norm 13 §3.5: one column, fields at a readable width. */
.form-card__grid {
  display: grid;
  grid-template-columns: minmax(0, 640px);
  gap: 0 var(--tcrm-space-4);
}
.tags-editor {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--tcrm-space-2);
  width: 100%;
}
.tags-editor__input {
  width: 200px;
}
.form-page__actions {
  display: flex;
  justify-content: flex-end;
  gap: var(--tcrm-space-2);
  padding-bottom: var(--tcrm-space-4);
}
</style>
