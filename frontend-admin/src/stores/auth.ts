import { defineStore } from 'pinia'

// 与用户端 frontend/ 用**不同的 key**：同一浏览器里两个端的登录态互不覆盖。
const TOKEN_KEY = 'jobpilot_admin_token'

/** 管理端登录态。管理令牌 ≠ 用户令牌（第二认证轴）——存的是管理令牌 */
export const useAdminAuthStore = defineStore('adminAuth', {
  state: () => ({
    token: localStorage.getItem(TOKEN_KEY) as string | null,
  }),
  getters: {
    isAuthenticated: (state) => !!state.token,
  },
  actions: {
    setToken(token: string) {
      this.token = token
      localStorage.setItem(TOKEN_KEY, token)
    },
    clear() {
      this.token = null
      localStorage.removeItem(TOKEN_KEY)
    },
  },
})
