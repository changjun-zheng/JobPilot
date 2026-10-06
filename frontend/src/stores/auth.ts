import { defineStore } from 'pinia'

const TOKEN_KEY = 'jobpilot_token'

/**
 * 登录态。scaffold 阶段只存 token（localStorage 持久化，刷新不丢）。
 * 真正的登录/注册请求与用户信息在「登录闭环」实现时补进这里。
 */
export const useAuthStore = defineStore('auth', {
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
