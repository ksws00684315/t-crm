<script setup lang="ts">
import { ElMessage, ElMessageBox } from "element-plus";
import { computed, onMounted, reactive, ref } from "vue";
import { useI18n } from "vue-i18n";
import { listKols, updateKolStatus } from "@/api/kol/kol";
import { listBdUsers } from "@/api/system/bd-user";
import type { BdUserVO, KolVO } from "@/api/types";
import { useUserStore } from "@/stores/user";
import { formatInstant } from "@/utils/format";
import { notifyError } from "@/utils/notify";
import { kolAbilities, linkAbilities } from "@/utils/permission";
import { formatRate } from "@/utils/rate";
import ChangeBdDialog from "./ChangeBdDialog.vue";
import KolDetailDrawer from "./KolDetailDrawer.vue";
import KolFormDialog from "./KolFormDialog.vue";
import KolImportDialog from "./KolImportDialog.vue";
import KolLinksDrawer from "./KolLinksDrawer.vue";

defineOptions({ name: "KolListPage" });

const { t } = useI18n();
const me = useUserStore();
/** Button visibility only; the backend enforces the same rules (task 04 §4.3). */
const abilities = computed(() => kolAbilities(me.user));
const links = computed(() => linkAbilities(me.user));

const loading = ref(false);
const rows = ref<KolVO[]>([]);
const total = ref(0);
const bdUsers = ref<BdUserVO[]>([]);
const query = reactive<{
  page: number;
  size: number;
  keyword: string;
  country: string;
  status?: 0 | 1;
  bdUserId?: string;
}>({ page: 1, size: 20, keyword: "", country: "" });

const formVisible = ref(false);
const editingKol = ref<KolVO | null>(null);
const bdVisible = ref(false);
const bdKol = ref<KolVO | null>(null);
const detailVisible = ref(false);
const detailId = ref<string | null>(null);
const importVisible = ref(false);
const linksVisible = ref(false);
const linksKol = ref<KolVO | null>(null);

async function load() {
  loading.value = true;
  try {
    const page = await listKols({
      page: query.page,
      size: query.size,
      keyword: query.keyword.trim() || undefined,
      country: query.country.trim().toUpperCase() || undefined,
      status: query.status,
      bdUserId: abilities.value.canManageBd ? query.bdUserId || undefined : undefined
    });
    rows.value = page.items;
    total.value = Number(page.total);
  } catch (error) {
    notifyError(error);
  } finally {
    loading.value = false;
  }
}

async function loadBdUsers() {
  if (!abilities.value.canManageBd) return;
  try {
    bdUsers.value = await listBdUsers();
  } catch (error) {
    notifyError(error);
  }
}

function search() {
  query.page = 1;
  void load();
}

function reset() {
  query.keyword = "";
  query.country = "";
  query.status = undefined;
  query.bdUserId = undefined;
  search();
}

function openCreate() {
  editingKol.value = null;
  formVisible.value = true;
}

function openEdit(row: KolVO) {
  editingKol.value = row;
  formVisible.value = true;
}

function openBd(row: KolVO) {
  bdKol.value = row;
  bdVisible.value = true;
}

function openDetail(row: KolVO) {
  detailId.value = row.id;
  detailVisible.value = true;
}

function openLinks(row: KolVO) {
  linksKol.value = row;
  linksVisible.value = true;
}

async function toggleStatus(row: KolVO) {
  const target = row.status === 1 ? 0 : 1;
  const disabling = target === 0;
  try {
    await ElMessageBox.confirm(
      disabling
        ? t("kol.confirmDisable", { code: row.kolCode, name: row.name })
        : t("kol.confirmEnable", { code: row.kolCode, name: row.name }),
      disabling ? t("kol.disableTitle") : t("kol.enableTitle"),
      { type: "warning" }
    );
  } catch {
    return; // cancelled
  }
  try {
    await updateKolStatus(row.id, target);
    ElMessage.success(disabling ? t("kol.disabled") : t("kol.enabled"));
    await load();
  } catch (error) {
    notifyError(error);
  }
}

onMounted(() => {
  void load();
  void loadBdUsers();
});
</script>

<template>
  <el-card shadow="never">
    <el-form :inline="true" :model="query" @submit.prevent>
      <el-form-item :label="t('common.keyword')">
        <el-input
          v-model="query.keyword"
          clearable
          maxlength="128"
          :placeholder="t('kol.keywordPlaceholder')"
          @keyup.enter="search"
        />
      </el-form-item>
      <el-form-item :label="t('common.country')">
        <el-input
          v-model="query.country"
          clearable
          maxlength="2"
          :placeholder="t('kol.countryPlaceholder')"
          style="width: 90px"
          @keyup.enter="search"
        />
      </el-form-item>
      <el-form-item :label="t('common.status')">
        <el-select
          v-model="query.status"
          clearable
          :placeholder="t('common.all')"
          style="width: 110px"
        >
          <el-option :label="t('common.enabled')" :value="1" />
          <el-option :label="t('common.disabled')" :value="0" />
        </el-select>
      </el-form-item>
      <el-form-item v-if="abilities.canManageBd" :label="t('kol.bd')" data-test="bd-filter">
        <el-select
          v-model="query.bdUserId"
          clearable
          filterable
          :placeholder="t('common.all')"
          style="width: 160px"
        >
          <el-option
            v-for="bd in bdUsers"
            :key="bd.id"
            :label="t('kol.bdOption', { nickname: bd.nickname, username: bd.username })"
            :value="bd.id"
          />
        </el-select>
      </el-form-item>
      <el-form-item>
        <el-button type="primary" @click="search">{{ t("common.search") }}</el-button>
        <el-button @click="reset">{{ t("common.reset") }}</el-button>
        <template v-if="abilities.canWrite">
          <el-button type="success" data-test="create" @click="openCreate">{{
            t("kol.create")
          }}</el-button>
          <el-button data-test="import" @click="importVisible = true">{{
            t("kol.import")
          }}</el-button>
        </template>
      </el-form-item>
    </el-form>

    <el-table v-loading="loading" :data="rows" row-key="id" border>
      <el-table-column prop="kolCode" :label="t('kol.code')" width="120" />
      <el-table-column prop="name" :label="t('common.name')" min-width="120" />
      <el-table-column prop="email" :label="t('common.email')" min-width="180" />
      <el-table-column prop="country" :label="t('common.country')" width="70" />
      <el-table-column :label="t('kol.bd')" min-width="110">
        <template #default="{ row }">{{
          (row as KolVO).bdUserName ?? t("common.unassigned")
        }}</template>
      </el-table-column>
      <el-table-column :label="t('kol.defaultRate')" width="100">
        <template #default="{ row }">{{ formatRate((row as KolVO).defaultRate) }}</template>
      </el-table-column>
      <el-table-column :label="t('common.status')" width="80">
        <template #default="{ row }">
          <el-tag :type="(row as KolVO).status === 1 ? 'success' : 'info'">
            {{ (row as KolVO).status === 1 ? t("common.enabled") : t("common.disabled") }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column :label="t('common.createdAt')" width="170">
        <template #default="{ row }">{{ formatInstant((row as KolVO).createTime) }}</template>
      </el-table-column>
      <el-table-column :label="t('common.actions')" width="280" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="openDetail(row as KolVO)">{{
            t("common.detail")
          }}</el-button>
          <el-button
            v-if="links.canViewLinks"
            link
            type="primary"
            data-test="links"
            @click="openLinks(row as KolVO)"
          >
            {{ t("kol.links") }}
          </el-button>
          <template v-if="abilities.canWrite">
            <el-button link type="primary" data-test="edit" @click="openEdit(row as KolVO)">
              {{ t("common.edit") }}
            </el-button>
            <el-button
              link
              :type="(row as KolVO).status === 1 ? 'danger' : 'success'"
              data-test="status"
              @click="toggleStatus(row as KolVO)"
            >
              {{ (row as KolVO).status === 1 ? t("common.disabled") : t("common.enabled") }}
            </el-button>
          </template>
          <el-button
            v-if="abilities.canManageBd"
            link
            type="warning"
            data-test="change-bd"
            @click="openBd(row as KolVO)"
          >
            {{ t("kol.changeBd") }}
          </el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-pagination
      v-model:current-page="query.page"
      v-model:page-size="query.size"
      class="pager"
      background
      layout="total, sizes, prev, pager, next"
      :page-sizes="[10, 20, 50, 100]"
      :total="total"
      @current-change="load"
      @size-change="search"
    />

    <KolFormDialog
      v-model="formVisible"
      :kol="editingKol"
      :can-pick-bd="abilities.canManageBd"
      :bd-users="bdUsers"
      @saved="load"
    />
    <ChangeBdDialog v-model="bdVisible" :kol="bdKol" :bd-users="bdUsers" @saved="load" />
    <KolDetailDrawer v-model="detailVisible" :kol-id="detailId" />
    <KolImportDialog v-model="importVisible" @finished="search" />
    <KolLinksDrawer
      v-model="linksVisible"
      :kol-code="linksKol?.kolCode ?? null"
      :kol-name="linksKol?.name"
    />
  </el-card>
</template>

<style scoped>
.pager {
  margin-top: 16px;
  justify-content: flex-end;
}
</style>
