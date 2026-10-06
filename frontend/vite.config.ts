import { fileURLToPath, URL } from 'node:url'
import vue from '@vitejs/plugin-vue'
import { defineConfig } from 'vite'

// https://vite.dev/config/
export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      // 路径别名 @ -> src，与 tsconfig.app.json 的 paths 保持一致
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  server: {
    port: 5173,
    // 开发期把 /api 代理到后端，前端代码里统一写 /api/v1/...，不必关心端口与跨域。
    // 生产部署时前端与后端同源（后端托管静态资源或反代），baseURL 不变。
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
})
