# 任务 27（部署）：本地预览环境——一条命令起整套系统

> 本文件是交给编码代理执行的提示词，把全文作为任务输入即可。
>
> **执行环境**：本机执行，独立工作树 `.worktrees/27-local-preview`，分支 `task/27-local-preview` 从最新的 `main` 检出。需要 Docker、Node 24 与 pnpm（构建前端静态文件）；后端在镜像内构建，不需要本机 JDK。
>
> **并行说明**：与 25b、26、17b 同时进行，只改 `deploy/`、`.gitignore` 和文档，不改 `backend/`、`redirect/`、`admin-ui/`、`kol-portal/` 的代码。26 会在生产 Nginx 模板里给推广表上传加一个 300 MB 的路径；谁后合并谁负责把它同步到预览配置。
>
> **CI 说明（2026-10-18 起）**：GitHub Actions 暂停使用，**不要等待** `gh pr checks`。验收以本地检查为准，把命令与结果贴进 PR 描述和执行报告。

---

## 1. 目标

项目负责人想随时在本机看 `main`（或任一分支）的当前产出，不用分别起数据库、后端、两个 Vite 开发服务器。要求在仓库根目录执行一条命令：

```bash
deploy/scripts/preview.sh up
```

就能从**当前工作目录的源码**构建并启动整套系统（管理端、达人端、后端、跳转服务、PostgreSQL、Redis），浏览器打开即可使用。只用于本机查看，**不用于任何服务器**。

## 2. 必读

1. `AGENTS.md`、`docs/05-工程规范/`（重点 06 安全与配置、07 依赖、08 Git）。
2. `deploy/README.md`、`deploy/.env.example`、`deploy/docker-compose.prod.yml`、`deploy/docker-compose.dev.yml`、`deploy/nginx/`、`deploy/scripts/build-static.sh`。
3. `backend/Dockerfile`、`redirect/Dockerfile`。
4. 后端启动校验：`LinkUrls`、`KolPortalUrls`、`WeidianAdminBaseUrl`（这三个地址**必须是 https**，否则启动失败）、`CorsConfiguration`。

## 3. 已定的设计决定（不要自行改变）

### 3.1 文件

| 文件 | 内容 |
|---|---|
| `deploy/docker-compose.preview.yml` | 新增。Compose 项目名 `tcrm-preview`。服务与生产模板一致：nginx、crm-admin、crm-redirect、postgres、redis；后端与跳转服务用现有 Dockerfile 从源码构建，镜像版本与生产模板相同 |
| `deploy/nginx/preview/preview.conf` | 新增。只走 HTTP、不要证书、不引用 Cloudflare 回源 IP 段；三个站点按端口区分（见 3.2）；`location` 与反向代理规则照搬生产模板 `default.conf.template`（含登录限流），文件头注释写明「与生产模板保持同步」 |
| `deploy/scripts/preview.sh` | 新增。子命令 `up` / `down` / `reset`（见 3.3） |
| `.gitignore` | 忽略 `deploy/.env.preview` 与 `deploy/www-preview/` |
| `deploy/README.md` | 新增「本地预览」一节 |

**不改** `docker-compose.prod.yml`、`docker-compose.dev.yml`、生产 Nginx 模板、`build-static.sh` 的行为（`build-static.sh` 已支持 `TCRM_STATIC_DIR`，直接复用）。

### 3.2 端口与地址

只绑定 `127.0.0.1`，避开负责人和各任务代理正在用的端口：

| 站点 | 地址 |
|---|---|
| 管理端 | `http://localhost:8890` |
| 达人端 | `http://localhost:8891` |
| 跳转服务 | `http://localhost:8892`（`/t/…`、`/i/…`、`/healthz`） |

PostgreSQL、Redis、后端、跳转服务的端口**不映射到宿主机**，只在 Compose 网络内可达（与生产一致）。端口写死在预览配置里，不做成可配置项。

后端要求 `TCRM_LINK_BASE_URL`、`TCRM_KOL_PORTAL_BASE_URL`、`TCRM_ADMIN_BASE_URL` 为 https。**不修改后端校验**；预览环境填占位地址（例如 `https://t.preview.invalid`），README 写明：预览中生成的推广链接、邮件链接不能直接打开，跳转服务请用 `http://localhost:8892/t/…` 手动验证。

### 3.3 `preview.sh`

- `up`：
  1. 检查 `docker`、`pnpm`、`node`、`openssl` 存在，缺少时给出明确提示并退出；
  2. `deploy/.env.preview` 不存在时生成：数据库账号密码、`TCRM_REDIS_PASSWORD`、`TCRM_SECRET_KEY`（`openssl rand -base64 32`）、`TCRM_IP_HASH_SALT`（`openssl rand -hex 32`）、首次平台管理员用户名与密码全部随机生成；其余变量填 3.2 的占位地址，`TCRM_MAIL_API_KEY` 为空（邮件走日志兜底）。文件权限 `600`。已存在时不覆盖；
  3. 用 `TCRM_STATIC_DIR=deploy/www-preview` 调用 `build-static.sh` 构建两个前端；
  4. `docker compose -p tcrm-preview -f deploy/docker-compose.preview.yml --env-file deploy/.env.preview up -d --build`，等待后端健康检查通过（超时给出 `docker compose logs` 的查看方法）；
  5. 打印三个地址；**仅首次生成 `.env.preview` 时**在终端打印一次平台管理员用户名和密码，并提示「之后可在 `deploy/.env.preview` 中查看」。
- `down`：停止容器，保留数据卷（下次 `up` 数据还在）。
- `reset`：停止并删除数据卷与 `deploy/.env.preview`，回到全新状态；执行前要求输入 `yes` 确认。
- `set -euo pipefail`，可以在任意目录调用（脚本内定位仓库根目录），通过 `shellcheck`（本机没有就在报告中说明）。

### 3.4 预览环境的取舍

- 登录验证码保持开启（与生产一致）。
- 资源限制可以比生产小，或不设；Redis 仍要求密码、`noeviction`。
- 不做演示数据初始化。README 写清首次使用步骤：用平台管理员登录 → 平台管理里为默认公司创建超级管理员 → 用超级管理员登录管理端。
- 不新增镜像以外的依赖；Compose 中使用的镜像版本与生产模板相同、写死版本。

## 4. 验证（写进执行报告并附截图）

1. 全新状态：`preview.sh up` 一次成功；`docker compose -p tcrm-preview ps` 全部 healthy 或 running。
2. 浏览器：
   - 管理端 `http://localhost:8890` 用平台管理员登录（含验证码）→ 创建默认公司超级管理员 → 用超级管理员登录，打开达人列表、商品、订单等任意几个页面无报错；
   - 达人端 `http://localhost:8891` 打开登录页、注册页；用管理端某商务的注册链接（把占位域名换成 `http://localhost:8891`）注册一个达人并登录；
   - `http://localhost:8892/healthz` 返回 200。
3. `preview.sh down` 后再 `up`：数据仍在；`up` 第二次不重新打印密码、不覆盖 `.env.preview`。
4. `preview.sh reset` 后再 `up`：回到全新状态。
5. `git status` 确认 `deploy/.env.preview`、`deploy/www-preview/` 未被跟踪；仓库、报告、截图中没有任何生成的密码。
6. 现有检查不受影响：
   - `docker compose -f deploy/docker-compose.dev.yml --env-file deploy/.env.example config -q`
   - `docker compose -f deploy/docker-compose.prod.yml --env-file deploy/.env.example config -q`
   - `docker compose -f deploy/docker-compose.preview.yml --env-file deploy/.env.example config -q`
   - `python3 deploy/scripts/check-healthchecks.py`
7. 记录首次构建与再次构建（无代码改动）的耗时。

收尾：`preview.sh reset`（输入 yes），停掉自己启动的所有进程。

## 5. 文档与报告

- `deploy/README.md`「本地预览」：一句话说明用途（只用于本机）、前置条件、三个子命令、地址、首次使用步骤、占位链接的限制、如何看某个分支（切到该分支或工作树后执行同一命令）、与生产模板保持同步的提醒。
- 执行报告 `docs/04-开发任务/27-本地预览环境-执行报告.md`：做了什么、验证结果与截图、耗时、偏离与遗留。

## 6. 交付

- 提交遵循 Conventional Commits（英文），推送分支，按 PR 模板创建 PR，PR 描述中贴出第 4 节的验证结果。
- **禁止**：合并 PR、推送 `main`、`push --force`、`reset --hard`、改写已推送历史；修改后端、跳转服务或前端代码；修改生产 Compose 与生产 Nginx 模板；提交任何密钥、密码或 `.env.preview`；新增依赖。
- **停下来报告**（推送已完成的提交，以 `[WIP]` 标题创建 PR）：不修改后端就无法启动（例如 https 校验之外还有阻断）；合理排查后验证仍失败。

完成后回复一段总结：PR 链接、做了什么、验证结果、遗留。
