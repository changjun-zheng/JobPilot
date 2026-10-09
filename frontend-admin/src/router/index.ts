import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router'
import { useAdminAuthStore } from '@/stores/auth'

const routes: RouteRecordRaw[] = [
  {
    path: '/login',
    name: 'login',
    component: () => import('@/views/AdminLoginView.vue'),
    meta: { public: true },
  },
  {
    path: '/',
    component: () => import('@/layouts/AdminLayout.vue'),
    children: [
      { path: '', redirect: { name: 'companies' } },
      { path: 'companies', name: 'companies', component: () => import('@/views/CompaniesView.vue') },
      { path: 'documents', name: 'documents', component: () => import('@/views/DocumentsView.vue') },
    ],
  },
  { path: '/:pathMatch(.*)*', redirect: { name: 'companies' } },
]

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes,
})

// 未登录（无管理令牌）跳管理登录页。管理令牌与用户令牌分开存，互不干扰。
router.beforeEach((to) => {
  const auth = useAdminAuthStore()
  if (!to.meta.public && !auth.isAuthenticated) {
    return { name: 'login', query: { redirect: to.fullPath } }
  }
  return true
})

export default router
