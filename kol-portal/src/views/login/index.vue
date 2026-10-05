<script setup lang="ts">
defineOptions({ name: "LoginPage" });
</script>

<template>
  <div class="login">
    <el-card class="login__card" shadow="always">
      <h1 class="login__title">T-CRM Creator Portal</h1>
      <el-form label-position="top" @submit.prevent>
        <el-form-item label="Account">
          <el-input disabled placeholder="Coming soon" />
        </el-form-item>
        <el-form-item label="Password">
          <el-input disabled type="password" placeholder="Coming soon" />
        </el-form-item>
        <el-button type="primary" class="login__submit" disabled>Sign in</el-button>
      </el-form>
      <p class="login__hint">Creator sign-in will be available in a later release.</p>
    </el-card>
  </div>
</template>

<style scoped>
.login {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 100%;
  padding: 16px;
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
</style>
