# T-CRM 达人端（kol-portal）

面向达人的前端：Vite + Vue 3 + TypeScript + Element Plus（英文语言包），**界面全英文**，适配手机宽度。

本阶段只提供骨架：`/login` 与 `/` 占位页、路由守卫、请求封装。达人登录接口和业务页面由后续任务提供。

## 目录结构（规范 01 第 6 节）

```
src/
├── api/          请求函数与接口类型；api/constants.ts 集中定义 baseURL（/kol-api）与达人令牌请求头名称
├── views/        页面（login、home、error）
├── components/   可复用组件（暂无）
├── layout/       顶栏 + 内容区布局（移动端优先）
├── stores/       Pinia 状态（本地登录态）
├── router/       路由与守卫
├── utils/        请求封装、会话存储
└── types/        路由 meta 类型扩展等
```

## 约定

- 请求封装与管理端一致：响应信封 `{code, msg, data, errorCode, traceId}`；按 `errorCode` 显示**英文**文案，不展示后台 `msg`；未知错误码显示通用提示并附 `traceId`；HTTP 401 清除登录态并回到 `/login`。
- 达人令牌请求头名称暂定为 `X-Kol-Token`（见 `src/api/constants.ts`），**待达人登录任务确认**；该任务同时补充达人侧的错误码文案。
- 路由守卫以本地登录态（`localStorage` 中未过期的令牌）判断：未登录访问任何受保护页面跳转 `/login`；已登录访问 `/login` 回到 `/`。目前没有登录入口，因此页面只能看到登录占位页。
- 开发时 `/kol-api` 由 Vite 代理到 `http://localhost:8080`（环境变量 `VITE_KOL_API_TARGET` 可修改）；生产由 Nginx 转发。
- **ECharts 暂未安装**：任务提示词要求安装，但 ECharts 6.1.0 的传递依赖 `tslib@2.3.0`（经 `zrender`）采用 0BSD，不在规范 07 第 3 节运行时许可证白名单内，且扩充白名单须由项目负责人决定并记录 ADR。获批后执行 `pnpm add --save-exact echarts@6.1.0 && pnpm notices` 即可。

## 命令

需要 Node.js `^22.13.0 || >=24`（CI 使用当前 LTS）和 pnpm 10。

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
