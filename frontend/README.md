# JobPilot 前端

面向求职者的 JobPilot 前端（I-4）。Vue 3 + Vite + TypeScript + Element Plus 单页应用，独立于后端 Spring 应用。

## 技术栈

- **Vue 3**（`<script setup>` 单文件组件）+ **vue-router**（history 模式）+ **Pinia**（状态）
- **Vite** 构建；`@` 别名指向 `src/`
- **Element Plus** 组件库（全量注册，见 `src/main.ts`）
- **axios** 封装在 `src/api/http.ts`：注入 JWT、解包后端 `ApiResponse`、401 回登录页

## 开发

```bash
pnpm install
pnpm dev        # http://localhost:5173
pnpm build      # vue-tsc 类型检查 + 生产构建到 dist/
pnpm preview    # 预览构建产物
```

开发期 `vite.config.ts` 把 `/api` 代理到后端 `http://localhost:8080`，前端代码统一写 `/api/v1/...`，不关心端口与跨域。

## 目录

```
src/
├── main.ts            应用入口：pinia + router + Element Plus
├── App.vue            根组件（仅 router-view）
├── router/index.ts    路由表 + 未登录守卫
├── stores/auth.ts     登录态（token 存 localStorage）
├── api/http.ts        axios 实例 + 拦截器（JWT / 解包 / 401）
├── layouts/           带顶栏的应用外壳
└── views/             页面（对话 / 知识库 / 账号 / 登录）
```

## 当前状态

已就绪：路由 + 未登录守卫、状态、axios 封装、应用外壳，以及四个页面——**登录闭环**（注册 / 登录 → `/auth` → 存 token）、**对话页**（会话列表 + 消息区 + 输入，接 `/agent/run` 与 `/conversations`）、**知识库页**（文档列表 + 导入 + 状态轮询 + 重建 / 删除，接 `/knowledge/documents`）、**账号设置页**（用量展示，接 `/usage/summary`）。以上均已用真实后端 curl 验证过契约。
