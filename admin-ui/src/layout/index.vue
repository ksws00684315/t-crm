<script setup lang="ts">
import { ArrowDown, Check, Expand, Fold, Search } from "@element-plus/icons-vue";
import { computed, onBeforeUnmount, onMounted, ref, watch } from "vue";
import { useI18n } from "vue-i18n";
import { useRoute, useRouter } from "vue-router";
import LocaleSwitch from "@/components/LocaleSwitch.vue";
import { translate } from "@/locales";
import { routes } from "@/router/routes";
import { useCompanySync } from "@/composables/useCompanySync";
import { useLocaleStore } from "@/stores/locale";
import { useMenuSearchStore } from "@/stores/menu-search";
import { usePayoutPendingStore } from "@/stores/payout-pending";
import { useReauthStore } from "@/stores/reauth";
import { useTabsStore } from "@/stores/tabs";
import { useUserStore } from "@/stores/user";
import { flattenMenu, rememberRecentMenu } from "@/utils/menu-search";
import { accessOf, accountLabel, buildMenu, canAccessRoute, roleLabel } from "@/utils/permission";
import { watermarkInk, watermarkText } from "@/utils/watermark";
import { affixFor } from "@/utils/tabs";
import {
  isMacPlatform,
  loadSidebarCollapsed,
  saveSidebarCollapsed,
  subscribeNarrow
} from "@/utils/sidebar";
import MenuSearch from "./MenuSearch.vue";
import NotificationBell from "./NotificationBell.vue";
import ReauthDialog from "./ReauthDialog.vue";
import SideMenu from "./SideMenu.vue";
import TabBar from "./TabBar.vue";

defineOptions({ name: "AppLayout" });

const { t } = useI18n();
const route = useRoute();
const router = useRouter();
const user = useUserStore();
const localeStore = useLocaleStore();
const menuSearch = useMenuSearchStore();
const payoutPending = usePayoutPendingStore();
const reauth = useReauthStore();
const tabs = useTabsStore();
const company = useCompanySync();

const collapsed = ref(loadSidebarCollapsed());
const narrow = ref(false);
const drawerOpen = ref(false);
const mac = isMacPlatform();

const access = computed(() => accessOf(user.user));
const menu = computed(() => {
  void localeStore.locale;
  const items = buildMenu(routes, access.value);
  // The pending-review badge rides on the review entry; zero renders no badge (task 15b §4.2.5).
  const badge = payoutPending.count;
  if (badge > 0) {
    for (const item of items) {
      for (const child of item.children ?? []) {
        if (child.path === "/fund/payouts") child.badge = badge;
      }
      if (item.path === "/fund/payouts") item.badge = badge;
    }
  }
  return items;
});
const roleText = computed(() => (user.user ? accountLabel(user.user) : ""));
const tenantLabel = computed(() => {
  if (access.value.isPlatformAdmin) return t("common.platformScope");
  return user.user?.tenantName || "";
});
const userLabel = computed(() =>
  t("layout.user", { nickname: user.user?.nickname ?? "", role: roleText.value })
);
const crumbs = computed(() => {
  void localeStore.locale;
  return route.matched
    .filter(record => Boolean(record.meta.title) && record.path !== "/")
    .map(record => ({
      path: record.path,
      title: translate(String(record.meta.title))
    }));
});
const asideWidth = computed(() =>
  collapsed.value ? "var(--tcrm-sidebar-collapsed)" : "var(--tcrm-sidebar-width)"
);
const watermark = ref(
  watermarkText(user.user?.nickname ?? "", user.user?.username ?? "", new Date())
);
const watermarkColor = watermarkInk("#15181d");

const unsubscribeNarrow = subscribeNarrow(matches => {
  narrow.value = matches;
  if (!matches) drawerOpen.value = false;
});

function toggleSidebar(): void {
  if (narrow.value) {
    drawerOpen.value = !drawerOpen.value;
    return;
  }
  collapsed.value = !collapsed.value;
  saveSidebarCollapsed(collapsed.value);
}

async function onCommand(command: string) {
  if (command === "password") {
    await router.push("/account/password");
  } else if (command === "logout") {
    reauth.close();
    tabs.reset();
    await user.logout();
    await router.replace("/login");
  }
}

tabs.restore(
  affixFor(access.value.isPlatformAdmin),
  path => canAccessRoute(router.resolve(path).meta, access.value),
  user.user?.id ?? null
);

watch(
  () => route.path,
  () => {
    watermark.value = watermarkText(
      user.user?.nickname ?? "",
      user.user?.username ?? "",
      new Date()
    );
    const componentName = route.meta.componentName;
    const titleKey = route.meta.title;
    if (!componentName || !titleKey || route.meta.noTab) return;
    const affix = affixFor(access.value.isPlatformAdmin);
    tabs.open({
      path: route.path,
      titleKey,
      affix: route.path === affix.path,
      componentName
    });
    const userId = user.user?.id;
    if (userId && flattenMenu(menu.value).some(entry => entry.path === route.path)) {
      rememberRecentMenu(userId, route.path);
    }
  },
  { immediate: true }
);

onMounted(() => {
  user.refreshProfile().catch(() => undefined);
  payoutPending.start();
});

onBeforeUnmount(() => {
  payoutPending.stop();
  unsubscribeNarrow();
});
</script>

<template>
  <el-container
    class="layout"
    :class="{ 'is-collapsed': collapsed && !narrow, 'is-narrow': narrow }"
    data-test="layout-root"
  >
    <el-aside v-if="!narrow" :width="asideWidth" class="layout__aside">
      <SideMenu
        :items="menu"
        :active="route.path"
        :collapse="collapsed"
        @select="drawerOpen = false"
      />
    </el-aside>
    <el-drawer
      v-else
      v-model="drawerOpen"
      direction="ltr"
      :size="224"
      :with-header="false"
      class="layout__drawer"
      data-test="sidebar-drawer"
    >
      <SideMenu :items="menu" :active="route.path" :collapse="false" @select="drawerOpen = false" />
    </el-drawer>
    <el-container>
      <el-header class="layout__header" height="56px">
        <div class="layout__header-start">
          <el-button
            text
            data-test="sidebar-toggle"
            :aria-label="collapsed || narrow ? t('layout.expand') : t('layout.collapse')"
            @click="toggleSidebar"
          >
            <el-icon><Expand v-if="collapsed || narrow" /><Fold v-else /></el-icon>
          </el-button>
          <el-breadcrumb>
            <el-breadcrumb-item
              v-for="(crumb, index) in crumbs"
              :key="crumb.path"
              :to="index < crumbs.length - 1 ? crumb.path : undefined"
            >
              {{ crumb.title }}
            </el-breadcrumb-item>
          </el-breadcrumb>
        </div>
        <div class="layout__actions">
          <el-button data-test="menu-search" @click="menuSearch.open()">
            <el-icon><Search /></el-icon>
            <span class="layout__label">{{ t("layout.searchMenu") }}</span>
            <kbd class="layout__kbd">{{
              mac ? t("layout.shortcutMac") : t("layout.shortcutOther")
            }}</kbd>
          </el-button>
          <!-- Keyed by company: a switch rebuilds the bell and it reloads the unread count at once. -->
          <NotificationBell v-if="!access.isPlatformAdmin" :key="company.currentId.value ?? ''" />
          <LocaleSwitch />
          <el-dropdown
            v-if="company.switchable.value"
            trigger="click"
            :disabled="company.switching.value"
            data-test="company-switch"
            @command="company.switchTo"
          >
            <span
              class="layout__company layout__label"
              role="button"
              :aria-label="t('company.switchTitle')"
            >
              {{ tenantLabel }}
              <el-icon><ArrowDown /></el-icon>
            </span>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item
                  v-for="item in company.companies.value"
                  :key="item.tenantId"
                  :command="item.tenantId"
                  :data-test="`company-option-${item.tenantId}`"
                >
                  <span class="layout__company-option">
                    <el-icon
                      class="layout__company-check"
                      :class="{ 'is-current': item.tenantId === company.currentId.value }"
                      ><Check
                    /></el-icon>
                    <span>{{ item.tenantName }}</span>
                    <span class="layout__company-role">{{ roleLabel(item.role) }}</span>
                  </span>
                </el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
          <el-dropdown trigger="click" @command="onCommand">
            <span class="layout__user">
              <span class="layout__label">{{ userLabel }}</span>
              <span
                v-if="tenantLabel && !company.switchable.value"
                class="layout__tenant layout__label"
                data-test="tenant-label"
                >{{ tenantLabel }}</span
              >
              <el-icon><ArrowDown /></el-icon>
            </span>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item command="password">{{
                  t("layout.changePassword")
                }}</el-dropdown-item>
                <el-dropdown-item command="logout" divided>{{
                  t("common.signOut")
                }}</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
      </el-header>
      <TabBar />
      <el-main class="layout__main">
        <el-watermark
          class="layout__watermark"
          :content="watermark"
          :rotate="-20"
          :gap="[140, 120]"
          :font="{ color: watermarkColor, fontSize: 14 }"
          data-test="watermark"
        >
          <RouterView v-slot="{ Component }">
            <Transition name="tcrm-page" mode="out-in">
              <KeepAlive :key="company.cacheKey.value" :include="tabs.include">
                <component :is="Component" v-if="Component" :key="tabs.keyFor(route.path)" />
              </KeepAlive>
            </Transition>
          </RouterView>
        </el-watermark>
      </el-main>
    </el-container>
    <MenuSearch />
    <ReauthDialog />
  </el-container>
</template>

<style scoped>
.layout {
  height: 100%;
  --layout-aside-width: var(--tcrm-sidebar-width);
}
.layout.is-collapsed {
  --layout-aside-width: var(--tcrm-sidebar-collapsed);
}
.layout.is-narrow {
  --layout-aside-width: 0px;
}
.layout__aside {
  background: var(--tcrm-color-card);
  border-right: 1px solid var(--tcrm-color-border);
  overflow: hidden;
}
.layout__header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--tcrm-space-3);
  background: var(--tcrm-color-card);
  border-bottom: 1px solid var(--tcrm-color-border);
  padding: 0 var(--tcrm-space-4);
}
.layout__header-start {
  display: flex;
  align-items: center;
  gap: var(--tcrm-space-2);
  min-width: 0;
}
.layout__actions {
  display: flex;
  align-items: center;
  gap: var(--tcrm-space-2);
}
.layout__kbd {
  margin-left: var(--tcrm-space-2);
  padding: 0 6px;
  border: 1px solid var(--tcrm-color-border);
  border-radius: var(--tcrm-radius-tag);
  font-family: var(--tcrm-font-sans);
  font-size: 14px;
  line-height: 22px;
  color: var(--tcrm-color-text-secondary);
  background: var(--tcrm-color-muted);
}
.layout__header-start :deep(.el-breadcrumb) {
  min-width: 0;
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
}
.layout__header-start :deep(.el-breadcrumb__item) {
  float: none;
  display: inline;
  white-space: nowrap;
}
.layout__actions,
.layout__user,
.layout__tenant {
  white-space: nowrap;
  flex-shrink: 0;
}
.layout__user {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  cursor: pointer;
  color: var(--tcrm-color-text);
}
.layout__tenant {
  color: var(--tcrm-color-text-secondary);
}
.layout__company {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  white-space: nowrap;
  cursor: pointer;
  color: var(--tcrm-color-text);
}
.layout__company-option {
  display: inline-flex;
  align-items: center;
  gap: var(--tcrm-space-2);
}
.layout__company-check {
  visibility: hidden;
}
.layout__company-check.is-current {
  visibility: visible;
  color: var(--el-color-primary);
}
.layout__company-role {
  color: var(--tcrm-color-text-secondary);
  font-size: 14px;
}
.layout__main {
  display: flex;
  flex-direction: column;
  background: var(--tcrm-color-page);
  padding: var(--tcrm-space-4);
  min-height: 0;
  overflow: hidden;
}
.layout__main :deep(.el-main) {
  padding: 0;
}
.layout__watermark {
  flex: 1;
  min-height: 0;
  height: 100%;
}
.layout__watermark :deep(> div) {
  height: 100%;
  min-height: 0;
}
@media (max-width: 991px) {
  .layout__label,
  .layout__kbd {
    display: none;
  }
}
@media (max-width: 767px) {
  .layout__header {
    gap: var(--tcrm-space-2);
    padding-inline: var(--tcrm-space-3);
  }

  .layout__actions {
    gap: var(--tcrm-space-1);
  }
}
</style>

<style>
.tcrm-page-enter-active,
.tcrm-page-leave-active {
  transition: opacity 150ms;
}
.tcrm-page-enter-from,
.tcrm-page-leave-to {
  opacity: 0;
}
@media (prefers-reduced-motion: reduce) {
  .tcrm-page-enter-active,
  .tcrm-page-leave-active {
    transition: none;
  }
}
</style>
