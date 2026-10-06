<script setup lang="ts">
import { ElMessage, ElMessageBox } from "element-plus";
import { computed, onMounted, reactive, ref } from "vue";
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
  const verb = target === 0 ? "停用" : "启用";
  try {
    await ElMessageBox.confirm(
      `确定要${verb}达人 ${row.kolCode}（${row.name}）吗？`,
      `${verb}达人`,
      {
        type: "warning"
      }
    );
  } catch {
    return; // cancelled
  }
  try {
    await updateKolStatus(row.id, target);
    ElMessage.success(`已${verb}`);
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
      <el-form-item label="关键字">
        <el-input
          v-model="query.keyword"
          clearable
          maxlength="128"
          placeholder="编号 / 姓名 / 邮箱"
          @keyup.enter="search"
        />
      </el-form-item>
      <el-form-item label="国家">
        <el-input
          v-model="query.country"
          clearable
          maxlength="2"
          placeholder="如 US"
          style="width: 90px"
          @keyup.enter="search"
        />
      </el-form-item>
      <el-form-item label="状态">
        <el-select v-model="query.status" clearable placeholder="全部" style="width: 110px">
          <el-option label="启用" :value="1" />
          <el-option label="停用" :value="0" />
        </el-select>
      </el-form-item>
      <el-form-item v-if="abilities.canManageBd" label="负责商务" data-test="bd-filter">
        <el-select
          v-model="query.bdUserId"
          clearable
          filterable
          placeholder="全部"
          style="width: 160px"
        >
          <el-option
            v-for="bd in bdUsers"
            :key="bd.id"
            :label="`${bd.nickname}（${bd.username}）`"
            :value="bd.id"
          />
        </el-select>
      </el-form-item>
      <el-form-item>
        <el-button type="primary" @click="search">查询</el-button>
        <el-button @click="reset">重置</el-button>
        <template v-if="abilities.canWrite">
          <el-button type="success" data-test="create" @click="openCreate">新增达人</el-button>
          <el-button data-test="import" @click="importVisible = true">批量导入</el-button>
        </template>
      </el-form-item>
    </el-form>

    <el-table v-loading="loading" :data="rows" row-key="id" border>
      <el-table-column prop="kolCode" label="达人编号" width="120" />
      <el-table-column prop="name" label="姓名" min-width="120" />
      <el-table-column prop="email" label="邮箱" min-width="180" />
      <el-table-column prop="country" label="国家" width="70" />
      <el-table-column label="负责商务" min-width="110">
        <template #default="{ row }">{{ (row as KolVO).bdUserName ?? "未分配" }}</template>
      </el-table-column>
      <el-table-column label="默认佣金" width="100">
        <template #default="{ row }">{{ formatRate((row as KolVO).defaultRate) }}</template>
      </el-table-column>
      <el-table-column label="状态" width="80">
        <template #default="{ row }">
          <el-tag :type="(row as KolVO).status === 1 ? 'success' : 'info'">
            {{ (row as KolVO).status === 1 ? "启用" : "停用" }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="创建时间" width="170">
        <template #default="{ row }">{{ formatInstant((row as KolVO).createTime) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="280" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="openDetail(row as KolVO)">详情</el-button>
          <el-button
            v-if="links.canViewLinks"
            link
            type="primary"
            data-test="links"
            @click="openLinks(row as KolVO)"
          >
            链接
          </el-button>
          <template v-if="abilities.canWrite">
            <el-button link type="primary" data-test="edit" @click="openEdit(row as KolVO)">
              编辑
            </el-button>
            <el-button
              link
              :type="(row as KolVO).status === 1 ? 'danger' : 'success'"
              data-test="status"
              @click="toggleStatus(row as KolVO)"
            >
              {{ (row as KolVO).status === 1 ? "停用" : "启用" }}
            </el-button>
          </template>
          <el-button
            v-if="abilities.canManageBd"
            link
            type="warning"
            data-test="change-bd"
            @click="openBd(row as KolVO)"
          >
            更换商务
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
