<script setup lang="ts">
import { useRouter } from "vue-router";
import { useSessionStore } from "@/stores/session";

defineOptions({ name: "AppLayout" });

const router = useRouter();
const session = useSessionStore();

async function signOut() {
  session.clearSession();
  await router.replace("/login");
}
</script>

<template>
  <div class="shell">
    <header class="shell__header">
      <span class="shell__brand">T-CRM Creator Portal</span>
      <el-button size="small" @click="signOut">Sign out</el-button>
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
