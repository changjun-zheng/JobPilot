import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router'
import { useAuthStore } from '@/stores/auth'

const routes: RouteRecordRaw[] = [
  {
    path: '/login',
    name: 'login',
    component: () => import('@/views/LoginView.vue'),
    // public：未登录可访问。守卫靠这个标记放行
    meta: { public: true },
  },
  {
    path: '/',
    component: () => import('@/layouts/AppLayout.vue'),
    children: [
      { path: '', redirect: { name: 'chat' } },
      { path: 'chat', name: 'chat', component: () => import('@/views/ChatView.vue') },
      { path: 'knowledge', name: 'knowledge', component: () => import('@/views/KnowledgeView.vue') },
      { path: 'account', name: 'account', component: () => import('@/views/AccountView.vue') },
    ],
  },
  { path: '/:pathMatch(.*)*', redirect: { name: 'chat' } },
]

const router = createRouter({
  // history 模式：URL 形如 /chat。生产部署需服务器把非 /api 路径回退到 index.html
  history: createWebHistory(import.meta.env.BASE_URL),
  routes,
})

// 未登录跳登录页。scaffold 阶段仅按本地 token 判断，登录闭环下一轮实现。
router.beforeEach((to) => {
  const auth = useAuthStore()
  if (!to.meta.public && !auth.isAuthenticated) {
    return { name: 'login', query: { redirect: to.fullPath } }
  }
  return true
})

export default router
