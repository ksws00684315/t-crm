<script setup lang="ts">
import { ref } from "vue";
import { logout } from "@/api/auth";
import { useI18n } from "vue-i18n";
import { useRoute, useRouter } from "vue-router";
import LocaleSwitch from "@/components/LocaleSwitch.vue";
import { useSessionStore } from "@/stores/session";
import { NAV_ITEMS } from "./nav";

defineOptions({ name: "AppLayout" });

const { t } = useI18n();
const route = useRoute();
const router = useRouter();
const session = useSessionStore();
const signingOut = ref(false);

/** The home entry ("/") must not stay highlighted on other pages, so it compares exactly. */
function isActive(path: string): boolean {
  return path === "/" ? route.path === "/" : route.path.startsWith(path);
}

async function signOut() {
  if (signingOut.value) return;
  signingOut.value = true;
  try {
    await logout();
  } catch {
    // Local logout must finish even if the server is unavailable.
  } finally {
    session.clearSession();
    signingOut.value = false;
    await router.replace("/login");
  }
}
</script>

<template>
  <div class="shell">
    <header class="shell__header">
      <div class="shell__top">
        <span class="shell__brand">{{ t("app.title") }}</span>
        <div class="shell__actions">
          <LocaleSwitch />
          <span v-if="session.kol" class="shell__identity"
            >{{ session.kol.name }} · {{ session.kol.tenantName }}</span
          >
          <details class="user-menu">
            <summary>{{ t("account.title") }}</summary>
            <nav :aria-label="t('account.title')">
              <RouterLink to="/account">{{ t("account.title") }}</RouterLink>
              <el-button :loading="signingOut" :disabled="signingOut" @click="signOut">{{
                t("common.signOut")
              }}</el-button>
            </nav>
          </details>
        </div>
      </div>
      <nav class="shell__nav" :aria-label="t('nav.label')">
        <RouterLink
          v-for="item in NAV_ITEMS"
          :key="item.name"
          :to="item.path"
          class="shell__nav-link"
          :aria-current="isActive(item.path) ? 'page' : undefined"
        >
          {{ t(item.title) }}
        </RouterLink>
      </nav>
    </header>
    <main class="shell__main">
      <RouterView />
    </main>
  </div>
</template>

<style scoped>
.shell {
  display: flex;
  flex-direction: column;
  min-height: 100%;
}
.shell__header {
  background: #fff;
  border-bottom: 1px solid var(--el-border-color-light);
}
.shell__top {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 12px 16px;
}
.shell__brand {
  font-size: 20px;
  font-weight: 600;
  line-height: 28px;
}
.shell__actions {
  display: flex;
  align-items: center;
  gap: 12px;
}
.shell__identity {
  overflow-wrap: anywhere;
}
.shell__nav {
  display: flex;
  align-items: center;
  gap: 4px;
  padding: 0 16px 8px;
  overflow-x: auto;
}
.shell__nav-link {
  display: inline-flex;
  align-items: center;
  min-height: 44px;
  padding: 0 14px;
  border-radius: 18px;
  white-space: nowrap;
  color: var(--el-text-color-regular);
  text-decoration: none;
}
.shell__nav-link[aria-current="page"] {
  background: var(--el-color-primary-light-9);
  color: var(--el-color-primary);
  font-weight: 600;
}
.user-menu {
  position: relative;
}
.user-menu summary {
  cursor: pointer;
  min-height: 44px;
  display: flex;
  align-items: center;
  white-space: nowrap;
}
.user-menu nav {
  position: absolute;
  right: 0;
  top: 100%;
  z-index: 10;
  background: white;
  padding: 12px;
  border: 1px solid var(--el-border-color-light);
  border-radius: 8px;
  min-width: 160px;
  display: grid;
  gap: 8px;
}
.user-menu a {
  display: flex;
  align-items: center;
  min-height: 44px;
}
.user-menu .el-button {
  min-height: 44px;
}
@media (max-width: 480px) {
  .shell__top {
    flex-wrap: wrap;
  }
  .shell__actions {
    width: 100%;
    flex-wrap: wrap;
  }
  .shell__identity {
    flex: 1;
    min-width: 80px;
  }
}
.shell__main {
  width: 100%;
  max-width: 960px;
  margin: 0 auto;
  padding: 16px;
  min-width: 0;
  overflow-x: hidden;
}
@media (min-width: 768px) {
  .shell__top {
    padding: 12px 32px;
  }
  .shell__nav {
    padding: 0 32px 8px;
  }
  .shell__main {
    padding: 24px 16px;
  }
}
@media (min-width: 1280px) {
  .shell__main {
    padding-inline: 24px;
  }
}
@media (max-width: 767px) {
  .shell__main {
    padding: 12px;
  }
}
</style>
