<script setup lang="ts">
import { useRoute, useRouter } from 'vue-router'
import { ElMessageBox } from 'element-plus'
import { useAdminAuthStore } from '@/stores/auth'

const route = useRoute()
const router = useRouter()
const auth = useAdminAuthStore()

const navItems = [
  { index: '/companies', label: '公司与岗位' },
  { index: '/documents', label: '平台面经' },
]

async function logout() {
  try {
    await ElMessageBox.confirm('确定退出管理后台？', '提示', { type: 'warning' })
  } catch {
    return // 用户取消
  }
  // 管理令牌无服务端撤销（无黑名单，见设计草案：多管理员与操作留痕后置）——清本地即可
  auth.clear()
  router.push({ name: 'login' })
}
</script>

<template>
  <el-container class="layout">
    <el-header class="layout-header">
      <div class="brand">
        <span class="brand-name">JobPilot <span class="brand-accent">管理后台</span></span>
      </div>
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
.brand-name {
  font-weight: 700;
  font-size: 18px;
  letter-spacing: -0.01em;
  color: #0f1115;
}
.brand-accent {
  color: #d97706; /* 管理端用暖色，与用户端的蓝区分开 */
}
.nav {
  flex: 1;
  border-bottom: none;
}
.layout-main {
  flex: 1;
  min-height: 0;
  flex-direction: column;
  padding: 24px;
  overflow-y: auto;
}
</style>
