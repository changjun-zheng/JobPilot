import { fileURLToPath, URL } from 'node:url'
import vue from '@vitejs/plugin-vue'
import { defineConfig } from 'vite'

// 管理端 SPA（独立后台，设计草案 §1#5）。
// 与用户端 frontend/ 分开：不同的入口、不同的端口、不同的登录态（管理令牌 vs 用户令牌）。
export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  server: {
    // 与用户端 5173 区分，可同时开两个端各自调试
    port: 5174,
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
})
