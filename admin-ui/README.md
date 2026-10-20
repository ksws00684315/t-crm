# T-CRM 管理端（admin-ui）

前端字体、字号、间距与响应式规则遵守 [统一前端视觉与布局规范](../docs/05-工程规范/10-前端视觉与布局规范.md)。

> **来源**：[pure-admin/pure-admin-thin](https://github.com/pure-admin/pure-admin-thin)（精简版、非国际化版本，MIT）
> **版本**：tag `v6.2.0`（提交 `f0ff132561ab684bb78379239adf29f9a38ac7f1`，2025-10-30 发布，当时最新稳定 tag；`git ls-remote --tags` 核对过没有更高的 tag）
> **获取日期**：2026-10-05（以 `git clone --depth 1 --branch v6.2.0` 取得，不含上游 `.git`）
> **许可证**：原 `LICENSE`（MIT，Copyright (c) 2020-present, pure-admin）保留在本目录。

## 与上游模板的关系

模板的布局代码（标签页、全局设置、搜索、通知、动态菜单等）约 40 个文件、全部为非严格 TypeScript，在 `strict` 下有 400 多处类型错误，并含大量 `any`，达不到规范 02 第 7 节的要求。因此本工程只**沿用模板的工程骨架与技术栈**（Vue 3、Vite、TypeScript、Element Plus、Pinia、Vue Router、ESLint + Prettier、`@/` 别名、前端目录约定），其余**重写为精简实现**：

| 项目                                                                   | 处理                                                                                     |
| ---------------------------------------------------------------------- | ---------------------------------------------------------------------------------------- |
| 演示页面（权限演示、欢迎页）、`mock/`、`vite-plugin-fake-server`       | 删除                                                                                     |
| 动态菜单（`get-async-routes`）、`refresh-token`                        | 删除；改为静态路由，用路由 `meta.roles` 控制菜单与访问                                   |
| CDN 插件（`vite-plugin-cdn-import`）、压缩/可视化/代码检查器等构建插件 | 不使用                                                                                   |
| Tailwind、Sass、国际化、`@pureadmin/*`、`@vueuse/*`、ECharts 等        | 未引入（本阶段未使用）                                                                   |
| 模板的 husky / commitlint / lint-staged / stylelint / 模板 Dockerfile  | 删除（提交规范由仓库级规范约束，部署由 `deploy/` 负责）                                  |
| 布局、登录页、路由守卫                                                 | 按模板的思路重写（侧边栏 + 顶栏 + 内容区；登录后按角色显示菜单；守卫为纯函数，便于测试） |

直接依赖全部固定为精确的稳定版本（`.npmrc` 中 `save-exact=true`），主版本沿用模板所在的版本线，未跟进已发布的下一主版本（Vite 8、ESLint 10、TypeScript 7、Pinia 4、Vue Router 5），升级按规范 07 第 4 节单独处理。

## 目录结构（规范 01 第 6 节）

```
src/
├── api/          请求函数与接口类型（与后台 *Request / *VO 一一对应）；错误码 → 中文文案映射
│   ├── system/   用户管理、操作日志
│   └── http.ts   唯一的 axios 实例；页面不得直接调用 axios
├── views/        页面（login、home、account、system/user、system/oper-log、error）
├── components/   可复用组件（暂无）
├── layout/       侧边栏 + 顶栏布局
├── stores/       Pinia 状态（登录用户）
├── router/       静态路由（meta.roles）、纯函数守卫
├── utils/        请求封装、会话存储、权限判断、表单校验、格式化
└── types/        路由 meta 类型扩展等
```

## 与后台的约定

- 接口前缀 `/admin-api`；开发时由 Vite 代理到 `http://localhost:8080`（可用环境变量 `VITE_ADMIN_API_TARGET` 修改）；生产由 Nginx 转发。
- 响应信封 `{code, msg, data, errorCode, traceId}`；令牌放在请求头 `X-Admin-Token`。
- HTTP 401 清除登录态并跳回登录页（保留原页面地址）。
- 按 `errorCode` 显示中文文案（`src/api/error-codes.ts`）；**不展示后台 `msg`**；未知错误码显示通用提示并附 `traceId`。`src/api/__tests__/error-codes.spec.ts` 直接读取后台 `ErrorCode.java`，后台新增错误码而前端未补文案时测试会失败。
- 表单先按与后台相同的约束校验（`src/utils/validation.ts`）；后台仍返回 `COMMON_VALIDATION_FAILED` 时，只把返回的字段名标红并显示前端自己的文案“该字段格式不正确”。
- 分页为 `{items, total, page, size}`；ID 为字符串。
- 修改密码成功后后台会使该账号所有会话失效，前端随即清除登录态并回到登录页。
- 登录态保存在 `localStorage`（键 `tcrm-admin-session`，含令牌与到期时间，默认 8 小时，与后台 `sa-token.timeout` 一致）。页面已通过 Nginx 的 `Content-Security-Policy` 限制脚本来源。

## 命令

本地开发与测试使用 **Node.js 24**（CI 使用的版本）；**Node 20 下 vitest 会失败**。`package.json` 的 `engines` 同时接受 `^22.13.0`，但请以 Node 24 为准。包管理器用 pnpm 10。

```bash
pnpm install --frozen-lockfile
pnpm dev          # http://localhost:8848
pnpm lint         # ESLint（含 Prettier 规则，警告也算失败）+ prettier --check
pnpm typecheck    # vue-tsc --noEmit
pnpm test         # Vitest
pnpm build        # vue-tsc + vite build，产物在 dist/
pnpm notices      # 重新生成 THIRD-PARTY-NOTICES（依赖变更后执行并提交）
pnpm notices:check  # 重新生成到临时文件并与已提交文件比对（CI 使用）
```

## 第三方许可声明

`THIRD-PARTY-NOTICES` 由 [generate-license-file](https://www.npmjs.com/package/generate-license-file) 4.2.5（ISC，稳定版）根据 `package.json` 的 `dependencies` 及其传递依赖生成，列出可能打包进产物的全部运行时依赖及其许可证原文（范围略大于实际打包进 `dist/` 的子集）。仅用于构建、检查和测试的 `devDependencies` 不在其中。
