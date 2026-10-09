<script setup lang="ts">
// 根组件：只放路由出口。应用外壳（侧栏 / 顶栏）在 layouts/AdminLayout.vue，
// 登录页等「无外壳」的页面直接挂在根出口下。
</script>

<template>
  <router-view />
</template>
