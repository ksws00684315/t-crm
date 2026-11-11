<script setup lang="ts">
import { ElMessage, ElMessageBox } from "element-plus";
import type { FormInstance, FormRules } from "element-plus";
import { computed, nextTick, onMounted, reactive, ref, watch } from "vue";
import { useI18n } from "vue-i18n";
import { useRoute, useRouter } from "vue-router";
import {
  createLibraryKol,
  deleteLibraryKol,
  getLibraryKol,
  updateLibraryKol,
  type LibraryDuplicate,
  type LibraryKolRequest,
  type LibraryKolDetailVO
} from "@/api/kol-library/kol";
import { useTabsStore } from "@/stores/tabs";
import { useUserStore } from "@/stores/user";
import { ApiError } from "@/utils/http/error";
import {
  LIBRARY_AUDIENCE_AGES,
  LIBRARY_AUDIENCE_GENDERS,
  LIBRARY_CATEGORIES,
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
import { libraryAbilities } from "@/utils/permission";

defineOptions({ name: "LibraryKolFormPage" });

const { t } = useI18n();
const route = useRoute();
const router = useRouter();
const tabs = useTabsStore();
const me = useUserStore();
const abilities = computed(() => libraryAbilities(me.user));

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

/** Second-level tabs of requirement 1.2.5 ④ (2026-11-10); every pane stays mounted. */
type FormTab = "basic" | "followUp" | "quality";
const activeTab = ref<FormTab>("basic");

/**
 * The validated fields in the order the form shows them, with the tab each lives on: a failed
 * save switches to the tab of the first error and scrolls to that field.
 */
const FIELD_TABS: readonly (readonly [string, FormTab])[] = [
  ["kolUsername", "basic"],
  ["displayName", "basic"],
  ["platform", "basic"],
  ["followers", "basic"],
  ["countryCode", "basic"],
  ["profileUrl", "basic"],
  ["contact", "basic"],
  ["remark", "basic"],
  ["categories", "basic"],
  ["avgViews", "basic"],
  ["engagementRate", "basic"],
  ["audienceCountry", "basic"],
  ["audienceAgeRange", "basic"],
  ["audienceGender", "basic"],
  ["outreachStatus", "followUp"],
  ["followUpOwner", "followUp"],
  ["lastContactDate", "followUp"],
  ["sampleStatus", "followUp"],
  ["trackingNo", "followUp"],
  ["sampleDate", "followUp"],
  ["followUpRemark", "followUp"],
  ["contentQuality", "quality"],
  ["salesPotential", "quality"],
  ["cooperationAttitude", "quality"],
  ["grade", "quality"]
];

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
    tags: [],
    remark: null,
    outreachStatus: libraryDefaultValue(LIBRARY_OUTREACH_STATUSES),
    followUpOwner: "",
    lastContactDate: null,
    sampleStatus: libraryDefaultValue(LIBRARY_SAMPLE_STATUSES),
    trackingNo: null,
    sampleDate: null,
    followUpRemark: null
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

type Check = (value: unknown) => string | null;

/** A rule that marks the field required (asterisk) and reports the first failing check. */
function requiredRule(check: Check, trigger: "blur" | "change") {
  return [
    {
      required: true,
      validator: (_rule: unknown, value: unknown, callback: (error?: Error) => void) => {
        const message = check(value);
        if (message) callback(new Error(message));
        else callback();
      },
      trigger
    }
  ];
}

const blankText = (value: unknown): boolean => `${value ?? ""}`.trim() === "";

/**
 * The seven required fields of requirement 1.2.6 ① (2026-11-10): creator ID, name, platform,
 * followers, country site, profile link and follow-up owner. The server checks them again; an
 * older creator without followers or link must have them filled in before the edit saves.
 */
const rules = computed<FormRules>(() => ({
  kolUsername: requiredRule(
    value => (blankText(value) ? t("library.validation.usernameRequired") : null),
    "blur"
  ),
  displayName: requiredRule(
    value => (blankText(value) ? t("library.validation.nameRequired") : null),
    "blur"
  ),
  platform: requiredRule(value => (blankText(value) ? t("common.required") : null), "change"),
  followers: requiredRule(value => {
    if (blankText(value)) return t("library.validation.followersRequired");
    return /^\d+$/.test(`${value}`.trim()) ? null : t("library.validation.followersInvalid");
  }, "blur"),
  countryCode: requiredRule(value => (blankText(value) ? t("common.required") : null), "change"),
  profileUrl: requiredRule(value => {
    if (blankText(value)) return t("library.validation.profileUrlRequired");
    return /^https?:\/\//i.test(`${value}`.trim())
      ? null
      : t("library.validation.profileUrlInvalid");
  }, "blur"),
  followUpOwner: requiredRule(
    value => (blankText(value) ? t("library.validation.ownerRequired") : null),
    "blur"
  )
}));

/** Read-only creator ID and name on the outreach tab, following the basic tab as it is typed. */
const shownUsername = computed(() => form.kolUsername.trim() || "—");
const shownName = computed(() => form.displayName.trim() || "—");

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
    tags: [...detail.tags],
    remark: detail.remark,
    outreachStatus: detail.outreachStatus,
    followUpOwner: detail.followUpOwner,
    lastContactDate: detail.lastContactDate,
    sampleStatus: detail.sampleStatus,
    trackingNo: detail.trackingNo,
    sampleDate: detail.sampleDate,
    followUpRemark: detail.followUpRemark
  });
}

async function load(): Promise<void> {
  activeTab.value = "basic";
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
    followUpRemark: clean(form.followUpRemark),
    followers: form.followers === null || `${form.followers}` === "" ? null : form.followers,
    avgViews: form.avgViews === null || `${form.avgViews}` === "" ? null : form.avgViews,
    engagementRate:
      form.engagementRate === null || form.engagementRate === "" ? null : form.engagementRate
  };
}

/**
 * Validates all three tabs. On failure the first field in display order that has an error decides
 * the tab to show, and the page scrolls to it (requirement 1.2.5 ④).
 */
async function validateAll(): Promise<boolean> {
  if (!formRef.value) return false;
  let invalid: Record<string, unknown> = {};
  const valid = await formRef.value.validate().catch((fields: Record<string, unknown>) => {
    invalid = fields ?? {};
    return false;
  });
  if (valid) return true;
  const first = FIELD_TABS.find(([prop]) => prop in invalid);
  if (first) {
    activeTab.value = first[1];
    await nextTick();
    formRef.value.scrollToField(first[0]);
  }
  return false;
}

async function submit(): Promise<void> {
  serverErrors.value = {};
  duplicate.value = null;
  if (!(await validateAll())) return;
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

/**
 * Deletes the creator from its edit form; super administrators only (requirement 1.2.5 ④,
 * 2026-11-10). The form tab and any tab of the creator close, and the list reloads.
 */
async function removeKol(): Promise<void> {
  const id = editingId.value;
  if (!id) return;
  const name = form.displayName.trim() || form.kolUsername.trim();
  try {
    await ElMessageBox.confirm(t("library.confirmDelete", { name }), t("library.deleteTitle"), {
      type: "warning",
      confirmButtonText: t("common.delete"),
      cancelButtonText: t("common.cancel")
    });
  } catch {
    return;
  }
  try {
    await deleteLibraryKol(id);
    ElMessage.success(t("library.deleted"));
    const detail = `/kol-library/kols/${id}`;
    for (const path of tabs.tabs
      .map(tab => tab.path)
      .filter(path => path === detail || path.startsWith(`${detail}/`))) {
      tabs.close(path);
    }
    tabs.refresh("/kol-library/kols");
    await router.push("/kol-library/kols");
  } catch (error) {
    notifyError(error);
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
        <el-button
          v-if="editing && abilities.canDelete"
          type="danger"
          plain
          data-test="form-delete"
          @click="removeKol"
        >
          {{ t("common.delete") }}
        </el-button>
      </header>

      <!-- Requirement 1.2.5 ④ (2026-11-10): three second-level tabs, styled like the creator
           list / assignment tabs; cards hold one column each (norm 13 §3.5). -->
      <el-tabs v-model="activeTab" class="form-tabs" data-test="form-tabs">
        <el-tab-pane :label="t('library.formTab.basic')" name="basic" data-test="tab-basic">
          <div class="form-tabs__cards">
            <el-card shadow="never" class="form-card" data-test="section-basic">
              <template #header>{{ t("library.formSection.basic") }}</template>
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
                <el-form-item :label="t('library.field.followers')" prop="followers">
                  <el-input
                    v-model="form.followers"
                    type="number"
                    min="0"
                    step="1"
                    data-test="followers"
                  />
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
                <el-form-item :label="t('library.field.profileUrl')" prop="profileUrl">
                  <el-input
                    v-model="form.profileUrl"
                    maxlength="512"
                    data-test="profile-url"
                    :placeholder="t('library.field.profileUrlPlaceholder')"
                  />
                </el-form-item>
                <el-form-item :label="t('library.field.contact')" prop="contact">
                  <el-input
                    v-model="form.contact"
                    maxlength="128"
                    data-test="contact"
                    :placeholder="t('library.field.contactPlaceholder')"
                  />
                </el-form-item>
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
                    <el-button data-test="tag-add" @click="addTag">
                      {{ t("library.addTag") }}
                    </el-button>
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

            <el-card shadow="never" class="form-card" data-test="section-account-type">
              <template #header>{{ t("library.formSection.accountType") }}</template>
              <div class="form-card__grid">
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
              </div>
            </el-card>

            <el-card shadow="never" class="form-card" data-test="section-audience">
              <template #header>{{ t("library.formSection.audience") }}</template>
              <div class="form-card__grid">
                <el-form-item :label="t('library.field.audienceCountry')" prop="audienceCountry">
                  <el-input
                    v-model="form.audienceCountry"
                    maxlength="64"
                    data-test="audience-country"
                  />
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
          </div>
        </el-tab-pane>

        <el-tab-pane
          :label="t('library.formTab.followUp')"
          name="followUp"
          data-test="tab-follow-up"
        >
          <el-card shadow="never" class="form-card" data-test="section-follow-up">
            <!-- The creator being entered, following the basic tab as it is typed. -->
            <dl class="form-card__identity" data-test="follow-up-identity">
              <div>
                <dt>{{ t("library.field.kolUsername") }}</dt>
                <dd data-test="identity-username">{{ shownUsername }}</dd>
              </div>
              <div>
                <dt>{{ t("library.field.displayName") }}</dt>
                <dd data-test="identity-name">{{ shownName }}</dd>
              </div>
            </dl>
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
              <el-form-item :label="t('library.field.followUpRemark')" prop="followUpRemark">
                <el-input
                  v-model="form.followUpRemark"
                  type="textarea"
                  :rows="3"
                  maxlength="1000"
                  show-word-limit
                  data-test="follow-up-remark"
                />
              </el-form-item>
            </div>
          </el-card>
        </el-tab-pane>

        <el-tab-pane :label="t('library.formTab.quality')" name="quality" data-test="tab-quality">
          <el-card shadow="never" class="form-card" data-test="section-quality">
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
              <el-form-item
                :label="t('library.field.cooperationAttitude')"
                prop="cooperationAttitude"
              >
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
            </div>
          </el-card>
        </el-tab-pane>
      </el-tabs>

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
/* Same plain el-tabs as the creator list / assignment page; the cards of a pane stack with the
   16px gap of norm 10. */
.form-tabs__cards {
  display: flex;
  flex-direction: column;
  gap: var(--tcrm-space-4);
}
/* The creator being entered, read-only at the top of the outreach tab. */
.form-card__identity {
  display: flex;
  flex-wrap: wrap;
  gap: var(--tcrm-space-2) var(--tcrm-space-6);
  margin: 0 0 var(--tcrm-space-4);
}
.form-card__identity div {
  display: flex;
  gap: var(--tcrm-space-2);
}
.form-card__identity dt {
  color: var(--tcrm-color-text-secondary);
}
.form-card__identity dd {
  margin: 0;
  word-break: break-all;
}
/* Norm 13 §3.5: one column, fields at a readable width (at most 640px, §3.10). */
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
