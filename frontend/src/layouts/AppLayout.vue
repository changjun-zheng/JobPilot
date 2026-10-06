<script setup lang="ts">
import { useRoute, useRouter } from 'vue-router'
import { ElMessageBox } from 'element-plus'
import * as authApi from '@/api/auth'
import { useAuthStore } from '@/stores/auth'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

const navItems = [
  { index: '/chat', label: '对话' },
  { index: '/knowledge', label: '知识库' },
  { index: '/account', label: '账号' },
]

async function logout() {
  try {
    await ElMessageBox.confirm('确定退出登录？', '提示', { type: 'warning' })
  } catch {
    return // 用户取消
  }
  try {
    await authApi.logout() // 服务端撤销令牌（jti 进黑名单）
  } catch {
    // 网络失败也要继续本地登出，否则用户被卡在登录态里出不去
  }
  auth.clear()
  router.push({ name: 'login' })
}
</script>

<template>
  <el-container class="layout">
    <el-header class="layout-header">
      <div class="brand">JobPilot</div>
      <el-menu :default-active="route.path" mode="horizontal" router :ellipsis="false" class="nav">
        <el-menu-item v-for="item in navItems" :key="item.index" :index="item.index">
          {{ item.label }}
        </el-menu-item>
      </el-menu>
      <el-button text @click="logout">退出</el-button>
    </el-header>

    <el-main class="layout-main">
      <router-view />
    </el-main>
  </el-container>
</template>

<style scoped>
.layout {
  height: 100vh;
}
.layout-header {
  display: flex;
  align-items: center;
  gap: 24px;
  background: #fff;
  border-bottom: 1px solid #e6e9f0;
}
.brand {
  font-weight: 700;
  font-size: 18px;
  color: #4f46e5;
}
.nav {
  flex: 1;
  border-bottom: none;
}
.layout-main {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
  padding: 24px;
}
</style>
