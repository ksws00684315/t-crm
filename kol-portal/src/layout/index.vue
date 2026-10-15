<script setup lang="ts">
import { ref } from "vue";
import { logout } from "@/api/auth";
import { useI18n } from "vue-i18n";
import { useRouter } from "vue-router";
import LocaleSwitch from "@/components/LocaleSwitch.vue";
import { useSessionStore } from "@/stores/session";

defineOptions({ name: "AppLayout" });

const { t } = useI18n();
const router = useRouter();
const session = useSessionStore();
const signingOut = ref(false);

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
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 12px 16px;
  background: #fff;
  border-bottom: 1px solid var(--el-border-color-light);
}
.shell__brand {
  font-size: 16px;
  font-weight: 600;
}
.shell__actions {
  display: flex;
  align-items: center;
  gap: 12px;
}
.shell__identity {
  overflow-wrap: anywhere;
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
  .shell__header {
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
}
@media (min-width: 768px) {
  .shell__header {
    padding: 12px 32px;
  }
  .shell__main {
    padding: 24px 32px;
  }
}
</style>
