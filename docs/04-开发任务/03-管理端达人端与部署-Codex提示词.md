# 任务 03：管理端、达人端与部署模板

> 本文件是交给编码代理（Codex）执行的提示词。直接把本文件全文作为任务输入即可。
> **前置条件：任务 02（工程化基线与规范整改）的 PR 已合并到 `main`。** 未合并时停止，不要开始。

---

## 1. 背景

后台 `backend/` 与跳转服务 `redirect/` 已按工程规范整改完成并接入 CI。本任务完成剩余骨架：管理端 `admin-ui`、达人端 `kol-portal`、部署模板 `deploy/`，并为前端接入 CI。

**不实现业务功能**，只对接后台已有的认证、用户管理、操作日志接口。

## 2. 开始前必读

1. `AGENTS.md`
2. `docs/05-工程规范/` 全部文件，重点：01 第 6 节（前端结构）、02 第 7 节（前端编码）、03（接口格式）、05（前端测试）、06（安全）、07（依赖）、08（流程与 CI）
3. `docs/04-开发任务/02-工程化基线与规范整改-执行报告.md`：后台整改后的实际接口、响应格式、错误码与环境变量
4. 后台 OpenAPI 文档（若任务 02 已接入 springdoc）或后台 controller 源码：以实际代码为准

## 3. 执行环境与交付

- 执行机不能运行 Docker；容器相关验证由 CI 和验证机完成。
- 从最新的 `main` 创建分支 `task/03-frontends-and-deploy`，在该分支提交。
- 完成后推送分支，`gh pr create` 创建 PR（按 PR 模板填写），`gh pr checks --watch` 等待 CI 全部通过；失败则在本分支修复。**不要合并 PR，不要推送 `main`。**
- 提交遵循规范 08，末尾加 `Co-Authored-By: Codex <noreply@openai.com>`。

## 4. 依赖规则

按规范 07：直接依赖必须是正式稳定版并写死版本；传递依赖中的预发布版本不阻断，但须在报告中列出（包名、版本、引入路径，可用 `pnpm why`）；提交 `pnpm-lock.yaml`；不得用 `overrides` / `resolutions` 强行改写传递依赖版本。

## 5. 执行步骤

### 第 1 步：管理端 admin-ui

1. 从 `pure-admin/pure-admin-thin` 获取**最新稳定 tag**（非国际化版本）放到 `admin-ui/`；不嵌套 `.git`；保留 LICENSE；在 `admin-ui/README.md` 开头注明来源、tag、获取日期。
2. 删除演示页面与模拟接口，只保留布局、登录、路由守卫等基础能力。
3. 目录按规范 01 第 6 节调整（`api/`、`views/`、`stores/`、`router/` 等）；页面不得直接调用 axios。
4. 对接后台：
   - 请求封装统一处理信封 `{code, msg, data, errorCode, traceId}`；令牌放在请求头 `X-Admin-Token`；401 时清除登录态跳回登录页；
   - 按 `errorCode` 显示中文提示（建立错误码 → 文案映射，覆盖后台 `ErrorCode` 的全部取值），未知错误码显示通用提示并附 `traceId`；
   - 分页按 `{items, total, page, size}` 处理；ID、金额按字符串处理。
5. 按角色**静态**控制菜单与路由（路由 `meta.roles`），不从后台拉取动态菜单；扩展模板的路由类型定义以支持 `meta.roles`。
6. 页面：登录、首页占位、修改密码、用户管理（列表、新增、编辑、启停、重置密码；仅 `SUPER_ADMIN`）、操作日志（仅 `SUPER_ADMIN`）。界面中文。
7. 按规范 02 第 7 节：`<script setup lang="ts">`、`strict`、禁止 `any`、ESLint + Prettier。
8. 测试（Vitest）：路由守卫（未登录跳转、角色不符拒绝）、请求封装（信封解析、401 处理、错误码映射）、权限判断工具函数。
9. 开发代理：`/admin-api` → `http://localhost:8080`。

上一轮执行中遇到过的问题（按实际情况处理）：pnpm 11 的构建脚本白名单需写在 `pnpm-workspace.yaml`；模板的 CDN 插件在加载配置时解析 `vue-demi` 失败，不使用 CDN 时不要导入该插件。

**验证**：`pnpm install --frozen-lockfile && pnpm lint && pnpm typecheck && pnpm test && pnpm build` 全部通过（脚本名以项目实际为准，须在 `package.json` 中提供这五个脚本）。

### 第 2 步：达人端 kol-portal

1. Vite + Vue 3 + TypeScript 新建工程，目录按规范 01 第 6 节。
2. 依赖：Element Plus（英文语言包）、vue-router、pinia、axios、ECharts（只安装）。
3. 骨架：基础布局（适配手机宽度）、`/login` 与 `/` 占位页、路由守卫（未登录跳转 `/login`，暂以本地状态判断，达人登录接口由后续任务提供）。
4. 请求封装与管理端一致的信封与错误处理方式，`baseURL` 为 `/kol-api`，错误文案为英文；达人令牌请求头名称在 `api` 层集中定义，留待后续任务对接。
5. 界面全英文；规范 02 第 7 节的编码要求同样适用。
6. Vitest：路由守卫、请求封装。

**验证**：同第 1 步的五个命令全部通过。

### 第 3 步：部署模板 deploy

1. `docker-compose.dev.yml`：MySQL 8.4、Redis 7；数据卷；健康检查；不挂载初始化 SQL（Flyway 建表）；库 `tcrm` 与应用账号通过 MySQL 镜像环境变量创建；**宿主机端口用环境变量**（`${TCRM_DB_PORT:-3306}`、`${TCRM_REDIS_PORT:-6379}`），后台连接使用同名变量。
2. `docker-compose.prod.yml`（模板）：nginx、crm-admin、crm-redirect、mysql、redis；资源限制参考设计说明书 §10；MySQL、Redis 不映射到公网端口。
3. `nginx/`：`t.域名` → crm-redirect；`kol.域名` → kol-portal 静态资源 + `/kol-api` 转发后台；`admin.域名` → admin-ui 静态资源 + `/admin-api` 转发后台。按规范 06 配置安全响应头与 HSTS；域名用占位变量。
4. 为 backend、redirect 提供 `Dockerfile`（多阶段构建，运行镜像使用 JDK 21 JRE，以非 root 用户运行）。
5. `.env.example`：列全所有变量，名称与各工程实际使用的一致（以任务 02 执行报告为准），值留空或为示例。

### 第 4 步：CI 补充

1. `.github/workflows/admin-ui.yml`、`kol-portal.yml`：对应目录变更时运行；Node LTS、pnpm 缓存、`pnpm install --frozen-lockfile`、lint、typecheck、test、build、`pnpm audit --audit-level high`。
2. `.github/workflows/deploy.yml`：`deploy/**` 变更时用 `.env.example` 执行两个 compose 文件的 `docker compose config -q`，并构建两个 `Dockerfile`。
3. `.github/dependabot.yml` 增加两个前端工程的 npm 检查。

### 第 5 步：文档与报告

1. 根 `README.md`：补充前端与部署部分（本地启动顺序：数据库 → 后台 → 跳转服务 → 两个前端；端口冲突时修改 `TCRM_DB_PORT`）。
2. 更新 `docs/04-开发任务/01-项目骨架搭建-本机验证清单.md` 中与新接口路径、新命令不一致的地方（例如 `/admin-api/system/users`），使其可直接用于验证。
3. 执行报告 `docs/04-开发任务/03-管理端达人端与部署-执行报告.md`：结论；版本与来源；每步改动与验证结果；预发布传递依赖清单；CI 结果与 PR 链接；偏离与遗留事项。

## 6. 禁止事项与停止条件

遵守 `AGENTS.md` 与任务 02 第 6 节的禁止事项。出现以下情况时停止，推送已完成的提交并以 `[WIP]` 标题创建 PR，在报告中说明：

- 某个直接依赖没有满足规范 07 的稳定版；找不到 pure-admin-thin 稳定 tag；
- 后台接口与前端需求不一致，需要修改后台才能完成；
- CI 在合理排查后仍无法通过。

**传递依赖中存在预发布版本不是停止条件。**
