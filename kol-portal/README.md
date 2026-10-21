# T-CRM 达人端（kol-portal）

字体、字号、间距与响应式布局遵守 [统一前端视觉与布局规范](../docs/05-工程规范/10-前端视觉与布局规范.md)。

面向达人的前端：Vite + Vue 3 + TypeScript + Element Plus，默认英文、支持中英文切换，适配手机宽度。

已提供邮箱密码登录与公司选择、激活、找回及重置密码、本人资料与修改密码。首页继续保留占位内容，业务页面由后续任务提供。

公开路由：`/login`、`/activate?token=`、`/forgot`、`/reset?token=`。`/` 和 `/account` 需要登录。激活成功直接登录；重置成功返回登录并显示提示。

## 目录结构（规范 01 第 6 节）

```
src/
├── api/          请求函数与接口类型；api/constants.ts 集中定义 baseURL（/kol-api）与达人令牌请求头名称
├── views/        页面（login、password-link、forgot、account、home、error）
├── components/   语言切换与公开账号页面布局
├── layout/       顶栏 + 内容区布局（移动端优先）
├── stores/       Pinia 状态（本地登录态）
├── router/       路由与守卫
├── utils/        请求封装、会话存储
└── types/        路由 meta 类型扩展等
```

## 约定

- 请求封装与管理端一致：响应信封 `{code, msg, data, errorCode, traceId}`；按 `errorCode` 显示当前语言的文案，不展示后台 `msg`；未知错误码显示通用提示并附 `traceId`；HTTP 401 清除登录态并回到 `/login?redirect=<当前完整路径>`，登录页上的 401 在页面内提示。
- 达人请求使用 `X-Kol-Token` 与当前语言的 `Accept-Language`；会话由 Pinia 管理，通过 `utils/session.ts` 保存令牌、到期时间与本人资料。改密成功后替换新会话；退出调用服务端接口，失败也清除本地会话。
- 路由守卫以本地登录态（`localStorage` 中未过期的令牌）判断：未登录访问任何受保护页面跳转 `/login`；已登录访问 `/login` 回到 `/`。登录成功仅接受站内 redirect 路径。
- 开发时 `/kol-api` 由 Vite 代理到 `http://localhost:8080`（环境变量 `VITE_KOL_API_TARGET` 可修改）；生产由 Nginx 转发。
- 密码统一校验 10～72 位可打印 ASCII，两次新密码须一致；表格外链只允许 HTTP(S)。

## 命令

本地开发与测试使用 **Node.js 24**（CI 使用的版本）；**Node 20 下 vitest 会失败**。`package.json` 的 `engines` 同时接受 `^22.13.0`，但请以 Node 24 为准。包管理器用 pnpm 10。

```bash
pnpm install --frozen-lockfile
pnpm dev          # http://localhost:8849
pnpm lint         # ESLint（含 Prettier 规则，警告也算失败）+ prettier --check
pnpm typecheck    # vue-tsc --noEmit
pnpm test         # Vitest
pnpm build        # vue-tsc + vite build，产物在 dist/
pnpm notices      # 重新生成 THIRD-PARTY-NOTICES（依赖变更后执行并提交）
pnpm notices:check  # 重新生成到临时文件并与已提交文件比对（CI 使用）
```

## 第三方许可声明

`THIRD-PARTY-NOTICES` 由 generate-license-file 4.2.5（ISC，稳定版）根据 `package.json` 的 `dependencies` 及其传递依赖生成，范围略大于实际打包进 `dist/` 的子集；构建、检查和测试工具不在其中。
