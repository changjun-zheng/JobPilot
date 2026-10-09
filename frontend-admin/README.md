# JobPilot 管理后台（frontend-admin）

平台内容维护端（公司 / 岗位 / 平台面经）。与用户端 `frontend/` **分开的独立 SPA**：
不同的入口、不同的端口、**不同的登录态**——管理令牌与用户令牌是两条认证轴（设计草案 §1#5），
后端分别用 `jobpilot.admin.jwt-secret` 与 `jobpilot.security.jwt-secret` 验签，互不认账。

## 开发

```bash
pnpm install
pnpm dev        # http://localhost:5174（用户端是 5173，可同时开）
pnpm build      # vue-tsc 类型检查 + 生产构建到 dist/
```

开发期 `vite.config.ts` 把 `/api` 代理到后端 `http://localhost:8080`。

## 前置：后端启用管理面

管理面**默认禁用**（`jobpilot.admin.username` 留空 → 所有 `/api/v1/admin/**` 一律 401）。
在仓库根的 `.env`（gitignored）里填三项后重启后端：

```properties
JOBPILOT_ADMIN_USERNAME=your-admin
JOBPILOT_ADMIN_PASSWORD=your-strong-password
JOBPILOT_ADMIN_JWT_SECRET=至少32字节的随机串（必须与 JOBPILOT_SECURITY_JWT_SECRET 不同）
```

## 目录

```
src/
├── main.ts                应用入口
├── App.vue                根组件（仅 router-view）
├── router/index.ts        路由表 + 未登录守卫（管理令牌）
├── stores/auth.ts         管理登录态（token 存 localStorage，key 与用户端不同）
├── api/http.ts            axios 实例 + 拦截器（管理令牌 / 解包 / 401 回登录）
├── api/admin.ts           管理端接口封装
├── layouts/AdminLayout.vue 带顶栏的管理外壳
└── views/
    ├── AdminLoginView.vue 管理员登录
    ├── CompaniesView.vue  公司与岗位（增改、上下架）
    └── DocumentsView.vue  平台面经（导入 / 重建索引 / 下架）
```

## 现状与边界

已可用：管理登录、公司增改与上下架、面经**同步导入**（切分 → 嵌入 → 索引）、重建索引、一键下架。

未做（后端接口已具备或属后续）：公司×岗位的可视化关联编辑、多管理员与操作留痕、
管理员登出的服务端撤销（当前仅清本地令牌，依赖短有效期）。
