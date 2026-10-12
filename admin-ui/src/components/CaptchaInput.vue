<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { useI18n } from "vue-i18n";
import { createCaptcha, type CaptchaAnswer } from "@/api/auth";

/**
 * Captcha input plus image for every administrator login (login page and reauth dialog).
 * Each captcha is single-use on the backend, so the parent calls `refresh()` after every failed
 * attempt. When the backend has captchas disabled nothing is rendered and `answer()` is undefined.
 */
defineOptions({ name: "CaptchaInput" });

const code = defineModel<string>({ required: true });
const emit = defineEmits<{ enter: [] }>();

const { t } = useI18n();
const enabled = ref(true);
const captchaId = ref<string | null>(null);
const image = ref<string | null>(null);
const failed = ref(false);
const loading = ref(false);
let requestSeq = 0;

async function refresh(): Promise<void> {
  const seq = ++requestSeq;
  code.value = "";
  loading.value = true;
  try {
    const result = await createCaptcha();
    if (seq !== requestSeq) return;
    enabled.value = result.enabled;
    captchaId.value = result.captchaId;
    image.value = result.image;
    failed.value = false;
  } catch {
    if (seq !== requestSeq) return;
    captchaId.value = null;
    image.value = null;
    failed.value = true;
  } finally {
    if (seq === requestSeq) loading.value = false;
  }
}

/** Answer to send with the login request; undefined when captchas are disabled. */
function answer(): CaptchaAnswer | undefined {
  if (!enabled.value) return undefined;
  return { captchaId: captchaId.value ?? "", captchaCode: code.value.trim() };
}

const visible = computed(() => enabled.value);

defineExpose({ refresh, answer, enabled: visible });

onMounted(refresh);
</script>

<template>
  <div v-if="visible" class="captcha" data-test="captcha">
    <el-input
      v-model="code"
      class="captcha__input"
      maxlength="8"
      autocomplete="off"
      :placeholder="t('captcha.placeholder')"
      data-test="captcha-input"
      @keyup.enter="emit('enter')"
    />
    <button
      type="button"
      class="captcha__image"
      :title="t('captcha.refresh')"
      :aria-label="t('captcha.refresh')"
      :disabled="loading"
      data-test="captcha-refresh"
      @click="refresh"
    >
      <img v-if="image" :src="image" :alt="t('captcha.imageAlt')" data-test="captcha-image" />
      <span v-else-if="failed" class="captcha__failed">{{ t("captcha.loadFailed") }}</span>
    </button>
  </div>
</template>

<style scoped>
.captcha {
  display: flex;
  gap: var(--tcrm-space-2);
  width: 100%;
}
.captcha__input {
  flex: 1;
  min-width: 0;
}
.captcha__image {
  display: flex;
  align-items: center;
  justify-content: center;
  flex: none;
  width: 96px;
  height: 32px;
  padding: 0;
  overflow: hidden;
  cursor: pointer;
  /* The image has its own light background in both themes; the border keeps it framed in dark. */
  background: var(--el-fill-color-light);
  border: 1px solid var(--el-border-color);
  border-radius: var(--el-border-radius-base);
}
.captcha__image:focus-visible {
  outline: 2px solid var(--el-color-primary);
  outline-offset: 1px;
}
.captcha__image img {
  display: block;
  width: 96px;
  height: 32px;
}
.captcha__failed {
  padding: 0 4px;
  font-size: 12px;
  line-height: 1.2;
  color: var(--el-color-danger);
}
</style>
