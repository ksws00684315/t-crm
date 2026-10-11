<script setup lang="ts">
import { useI18n } from "vue-i18n";
import LocaleSwitch from "@/components/LocaleSwitch.vue";

defineOptions({ name: "LoginPage" });

const { t } = useI18n();
</script>

<template>
  <div class="login">
    <div class="login__bar">
      <LocaleSwitch />
    </div>
    <el-card class="login__card" shadow="always">
      <h1 class="login__title">{{ t("login.title") }}</h1>
      <el-form label-position="top" @submit.prevent>
        <el-form-item :label="t('login.account')">
          <el-input disabled :placeholder="t('common.comingSoon')" />
        </el-form-item>
        <el-form-item :label="t('login.password')">
          <el-input disabled type="password" :placeholder="t('common.comingSoon')" />
        </el-form-item>
        <el-button type="primary" class="login__submit" disabled>{{ t("login.submit") }}</el-button>
      </el-form>
      <p class="login__hint">{{ t("login.hint") }}</p>
    </el-card>
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
  font-size: 20px;
  text-align: center;
}
.login__submit {
  width: 100%;
}
.login__hint {
  margin: 16px 0 0;
  color: var(--el-text-color-secondary);
  font-size: 13px;
  text-align: center;
}
@media (min-width: 768px) {
  .login__bar {
    top: 24px;
    right: 32px;
  }
}
</style>
