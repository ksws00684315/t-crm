<script setup lang="ts">
import type { FormInstance, FormRules } from "element-plus";
import { reactive, ref } from "vue";
import { useI18n } from "vue-i18n";
import { useRoute, useRouter } from "vue-router";
import LocaleSwitch from "@/components/LocaleSwitch.vue";
import { useUserStore } from "@/stores/user";
import { notifyError } from "@/utils/notify";
import { rule, validateExistingPassword, validateUsername } from "@/utils/validation";

defineOptions({ name: "LoginPage" });

const { t } = useI18n();
const router = useRouter();
const route = useRoute();
const user = useUserStore();

const formRef = ref<FormInstance>();
const loading = ref(false);
const form = reactive({ username: "", password: "" });
const rules: FormRules = {
  username: [rule(validateUsername)],
  password: [rule(validateExistingPassword)]
};

/** Only same-site paths are honoured, never an absolute URL. */
function safeRedirect(value: unknown): string {
  return typeof value === "string" && value.startsWith("/") && !value.startsWith("//")
    ? value
    : "/";
}

async function submit() {
  if (!formRef.value || !(await formRef.value.validate().catch(() => false))) return;
  loading.value = true;
  try {
    await user.login(form.username, form.password);
    await router.replace(safeRedirect(route.query.redirect));
  } catch (error) {
    form.password = "";
    notifyError(error);
  } finally {
    loading.value = false;
  }
}
</script>

<template>
  <div class="login">
    <el-card class="login__card" shadow="always">
      <h1 class="login__title">{{ t("login.title") }}</h1>
      <el-form ref="formRef" :model="form" :rules="rules" label-position="top" @submit.prevent>
        <el-form-item :label="t('login.username')" prop="username">
          <el-input v-model="form.username" autocomplete="username" maxlength="64" />
        </el-form-item>
        <el-form-item :label="t('login.password')" prop="password">
          <el-input
            v-model="form.password"
            type="password"
            show-password
            autocomplete="current-password"
            maxlength="72"
            @keyup.enter="submit"
          />
        </el-form-item>
        <el-button type="primary" class="login__submit" :loading="loading" @click="submit">
          {{ t("login.submit") }}
        </el-button>
      </el-form>
    </el-card>
    <!-- After the form in the DOM so existing tests still address the first two inputs. -->
    <div class="login__bar">
      <LocaleSwitch />
    </div>
  </div>
</template>

<style scoped>
.login {
  position: relative;
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 100%;
  padding: 16px;
}
.login__bar {
  position: absolute;
  top: 16px;
  right: 16px;
}
.login__card {
  width: 100%;
  max-width: 380px;
}
.login__title {
  margin: 0 0 24px;
  font-size: 22px;
  text-align: center;
}
.login__submit {
  width: 100%;
}
</style>
