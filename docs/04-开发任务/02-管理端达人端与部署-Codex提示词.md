# 任务：续做项目骨架——管理端、达人端与部署模板

> 本文件是交给编码代理（Codex）执行的提示词。直接把本文件全文作为任务输入即可。
> 这是《01-项目骨架搭建-Codex提示词》的续做任务，只完成其中未完成的第 6～8 步。

---

## 1. 背景与当前状态

你在仓库 `t-crm`（分支 `main`，远程 `origin` 为私有仓库 `ksws00684315/t-crm`）中工作。开始前先 `git pull origin main`，然后通读：

- `docs/04-开发任务/01-项目骨架搭建-Codex提示词.md`：原任务，本次沿用其中第 2 节技术决策、第 3 节目录结构、第 5 节禁止事项（**依赖版本规则以本文件第 2 节为准**）
- `docs/04-开发任务/01-项目骨架搭建-执行报告.md`：上一轮执行情况，第 9 节为验证机结果
- `docs/03-设计/系统详细设计说明书.md`：§2、§3、§10、§12

**已完成并通过验证机验证**（不要改动，除非本任务需要对接）：

- `backend/`：crm-admin 后台，管理员登录、用户管理、操作日志等接口已实现，接口清单见执行报告第 4 节。令牌请求头为 `X-Admin-Token`，默认端口 8080。
- `redirect/`：crm-redirect 跳转服务，默认端口 8081。
- 验证机已修复：Redis 空密码认证问题、未知路径返回 404。

**本次要完成**：原提示词的第 6 步（管理端 admin-ui）、第 7 步（达人端 kol-portal）、第 8 步（部署模板、根 README、执行报告、提交推送）。

**执行环境说明**：执行机**不能运行 Docker**，不要安装或启动 Docker。需要容器的运行验证由验证机按 `docs/04-开发任务/01-项目骨架搭建-本机验证清单.md` 执行。你只需保证不依赖 Docker 的验证全部在本机通过。

## 2. 依赖版本规则（替代原提示词第 2 节"通用要求"中的版本条款）

上一轮因传递依赖中存在预发布版本而中止。规则调整如下：

1. **直接依赖**（`package.json` 的 `dependencies` / `devDependencies`，`pom.xml` 中直接声明的依赖与插件）必须是**正式稳定版**，版本号写死；禁止 BETA、RC、alpha、M、SNAPSHOT、`next`、范围版本、`latest`。
2. **传递依赖**中出现的预发布版本**不作为阻断条件**，不要为此降级、强制覆盖（`overrides` / `resolutions`）或替换上游工具链。
3. 在执行报告中**列出所有预发布版本的传递依赖**：包名、版本、引入路径（例如 `pnpm why <包名>` 的输出摘要）。
4. 锁文件（`pnpm-lock.yaml`）必须提交，保证依赖可复现。

## 3. 执行步骤

每一步完成后执行"验证"，通过后再进入下一步。

### 第 6 步：管理端前端 admin-ui

要求与原提示词第 6 步相同，补充以下内容：

- 从官方仓库 `pure-admin/pure-admin-thin` 获取**最新稳定 tag**（非国际化版本；上一轮使用的是 v6.2.0，如仍为最新则继续使用），放到 `admin-ui/`。不要嵌套 `.git`；保留 LICENSE，在 `admin-ui/README.md` 开头注明来源仓库、tag 和获取日期。
- 删除演示页面与模拟接口（mock），只保留布局、登录、权限路由等基础能力。
- 对接后台接口：登录调用 `POST /admin-api/auth/login`，令牌放在请求头 `X-Admin-Token`；用 `GET /admin-api/auth/me` 获取当前用户与角色；令牌失效（401）时清除登录态并跳回登录页。
- **按角色静态控制菜单与路由**，不从后端拉取动态菜单。
- 页面：登录、首页占位、修改密码、用户管理（列表、新增、编辑、启停、重置密码；仅 `SUPER_ADMIN`）、操作日志（仅 `SUPER_ADMIN`）。
- 开发代理：`/admin-api` 转发到 `http://localhost:8080`。
- 界面文案中文。

上一轮遇到的已知问题（供参考，按实际情况处理）：

- pnpm 11 不再读取 `package.json` 中的构建脚本白名单，需要迁移到 `pnpm-workspace.yaml`。
- 静态路由元数据中的 `meta.roles` 不在模板的路由类型定义中，导致 `pnpm typecheck` 失败，需要扩展类型定义。
- 模板的 CDN 插件在配置加载时解析 `vue-demi` 失败（即使关闭 CDN 开关也会在导入时初始化），导致 `pnpm build` 失败，需要调整该插件的引入方式（例如不需要时不导入）。
- 上一轮在执行机仓库外保留过管理端草稿（`t-crm-blocked-admin-ui-20261004-*`）。如仍存在，可以参考，但最终代码必须通过本步全部验证。

**验证**：`cd admin-ui && pnpm install && pnpm build` 成功；模板自带的类型检查与 lint 命令全部通过。

### 第 7 步：达人端 kol-portal

要求与原提示词第 7 步相同：Vite + Vue 3 + TypeScript；Element Plus（英文语言包）、vue-router、pinia、axios、ECharts（只安装）；基础布局（适配手机宽度）、`/login` 与 `/` 占位页、路由守卫占位；axios `baseURL` 为 `/kol-api`，开发代理到 `http://localhost:8080`；界面全英文；配置 ESLint 与 Prettier。

**验证**：`pnpm install && pnpm build && pnpm lint` 全部通过。

### 第 8 步：部署模板、文档与提交

**部署模板（`deploy/`，本机不启动任何容器）**

- `docker-compose.dev.yml`：MySQL 8.4 与 Redis 7，数据卷持久化，带健康检查，不挂载初始化 SQL（表结构由 Flyway 创建）。数据库需预先创建库 `tcrm` 和应用账号（通过 MySQL 镜像的环境变量完成）。
- **本机端口可配置**：宿主机映射端口使用环境变量，默认 `3306`、`6379`（例如 `${TCRM_DB_PORT:-3306}:3306`），因为验证机的 `3306` 已被其他程序占用。后台连接端口使用同一个 `TCRM_DB_PORT`，保证改一处即可。
- `docker-compose.prod.yml`（模板）：nginx、crm-admin、crm-redirect、mysql、redis 五个服务，资源限制参考设计说明书 §10。
- `nginx/`：三个站点配置模板——`t.域名`（转发 crm-redirect）、`kol.域名`（kol-portal 静态资源 + `/kol-api` 转发后台）、`admin.域名`（admin-ui 静态资源 + `/admin-api` 转发后台）。域名用占位变量。
- `.env.example`：列出全部环境变量，**变量名必须与 `backend`、`redirect` 的 `application.yml` 中实际使用的一致**（执行报告第 5 节已列出），并补充 MySQL 镜像所需的 root 密码、应用库名、账号等变量；值留空或给示例，不写真实密码。
- 若本机有 Docker CLI，执行 `docker compose -f deploy/docker-compose.dev.yml config -q` 与 `docker compose -f deploy/docker-compose.prod.yml config -q`（可使用 `.env.example` 作为 `--env-file`）检查语法。

**文档**

- 根目录 `README.md`（中文）：项目简介、目录说明、各工程版本、本地启动步骤（数据库 → 后台 → 跳转服务 → 两个前端）、常用命令（含 `mvn verify -Pdocker-it`、端口冲突时如何修改 `TCRM_DB_PORT`）。
- 新建 `docs/04-开发任务/02-管理端达人端与部署-执行报告.md`，格式同原提示词第 7 节，并增加一节"预发布传递依赖清单"（见本文件第 2 节第 3 条）。
- 不要修改 `docs/` 下已有文件。

**提交与推送**

- Conventional Commits，按工程拆分，例如：
  - `chore(admin-ui): import pure-admin-thin <tag>`
  - `feat(admin-ui): wire login, user management and audit log pages`
  - `feat(kol-portal): init kol portal skeleton`
  - `chore(deploy): add docker compose and nginx templates`
  - `docs: add root readme and continuation report`
- 每条提交信息末尾加上：

  ```
  Co-Authored-By: Codex <noreply@openai.com>
  ```

- 全部步骤完成且本机可执行的验证全部通过后，执行 `git push origin main`。不要新增或修改远程配置，不要推送到其他分支或远程。若任务中止，已完成的提交仍推送，并在报告中注明中止位置。
- 禁止 `git push --force`、`git reset --hard`、`git clean -fd`、改写已推送历史。

## 4. 停止条件

遇到以下情况，停止执行并在报告中说明，不要自行换方案：

- 某个**直接依赖**没有满足第 2 节要求的稳定版；
- 找不到 pure-admin-thin 的正式稳定 tag；
- 网络无法访问代码仓库或依赖仓库；
- 本机缺少 Node、pnpm 等必需工具且无法安装；
- 任一验证步骤在合理排查后仍失败。

**传递依赖中存在预发布版本不是停止条件。**
