<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from "vue";
import { useI18n } from "vue-i18n";
import {
  fetchRemoteVersion,
  monitorVersion,
  readSnoozeUntil,
  VERSION_SNOOZE_MS,
  writeSnoozeUntil
} from "@/utils/version";

defineOptions({ name: "VersionNotice" });

const { t } = useI18n();
const visible = ref(false);
let stop: () => void = () => undefined;

function later(): void {
  writeSnoozeUntil(Date.now() + VERSION_SNOOZE_MS);
  visible.value = false;
}

function refresh(): void {
  window.location.reload();
}

onMounted(() => {
  stop = monitorVersion({
    dev: import.meta.env.DEV,
    current: __APP_VERSION__,
    now: () => Date.now(),
    fetchRemote: fetchRemoteVersion,
    readSnooze: readSnoozeUntil,
    onPrompt: () => {
      visible.value = true;
    }
  });
});

onBeforeUnmount(() => stop());
</script>

<template>
  <aside v-if="visible" class="version-notice" data-test="version-notice">
    <p>{{ t("version.message") }}</p>
    <div class="version-notice__actions">
      <el-button size="small" data-test="version-later" @click="later">{{
        t("version.later")
      }}</el-button>
      <el-button size="small" type="primary" data-test="version-refresh" @click="refresh">
        {{ t("version.refresh") }}
      </el-button>
    </div>
  </aside>
</template>

<style scoped>
.version-notice {
  position: fixed;
  z-index: 40;
  top: var(--tcrm-space-4);
  right: var(--tcrm-space-4);
  width: 320px;
  padding: var(--tcrm-space-4);
  border: 1px solid var(--tcrm-color-border);
  border-radius: var(--tcrm-radius-card);
  background: var(--tcrm-color-card);
  color: var(--tcrm-color-text);
  box-shadow: var(--tcrm-shadow-overlay);
}
.version-notice p {
  margin: 0 0 var(--tcrm-space-3);
}
.version-notice__actions {
  display: flex;
  justify-content: flex-end;
  gap: var(--tcrm-space-2);
}
</style>
