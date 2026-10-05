<script setup lang="ts">
defineOptions({ name: "NotFoundPage" });
</script>

<template>
  <el-result icon="info" title="404" sub-title="Page not found">
    <template #extra>
      <el-button type="primary" @click="$router.push('/')">Back to home</el-button>
    </template>
  </el-result>
</template>
