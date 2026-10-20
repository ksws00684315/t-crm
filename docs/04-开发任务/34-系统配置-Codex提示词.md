# 任务 34（后端 + 管理端 + 达人端 + 跳转服务）：系统配置——可随时修改的非敏感配置集中管理

> 本文件是交给编码代理执行的提示词，把全文作为任务输入即可。
>
> **执行环境**：本机执行。独立工作树 `.worktrees/34-system-config`，分支 `task/34-system-config`，已从最新的 `main` 检出。需要 JDK 21、Node 24、pnpm、Docker。
>
> **并行说明**：任务 35（本地共享中间件）同时进行。联调和浏览器验证要等 35 合并、负责人的环境切换完成后，连接共享中间件进行（见第 5 节）。在此之前先开发，并完成单元测试和集成测试。迁移版本号使用 `V202610211000`～`V202610211009`。
>
> **CI 说明**：GitHub Actions 暂停使用，**不要等待** `gh pr checks`。
>
> 本任务是负责人 2026-10-20 提出的新功能，不在 1018 表的功能项里。

---

## 1. 背景与决定（不要自行改变）

现在不少可以随时更换的值写在环境变量或代码常量里，例如达人端的域名。每次修改都要改配置、重启服务。负责人决定：

1. 新增「**系统配置**」功能，由**平台管理员**维护，**全系统一份**（不区分公司）。放在平台管理员的菜单里，「公司管理」旁边。
2. 除了密钥等敏感信息，代码里可以随时更换的内容都放进来。保存后**立即生效，不需要重启**。
3. 环境变量和现在的代码常量作为**默认值**：系统配置里有值时以它为准；「恢复默认」后回到默认值。

## 2. 必读

`AGENTS.md`、`docs/05-工程规范/`（重点 03、04、06）；设计说明书 §3、§10；ADR-008（登录失败限流）、ADR-017（验证码）、ADR-019（复制令牌与对外接口）、ADR-024（推广表上传）；`deploy/.env.example`、`deploy/README.md`；`backend/src/main/resources/application.yml`；跳转服务 `redirect/`。

## 3. 配置项清单

### 3.1 放进系统配置

| 分组 | 键（建议） | 说明 | 默认值来源 | 校验 |
|---|---|---|---|---|
| 站点 | `site.name` | 系统名称：两个前端的浏览器标题、左上角名称、登录页，以及邮件中的系统名 | SparkTrack | 1～32 字 |
| 站点 | `mail.from` | 邮件发件人（`显示名 <地址>`） | `TCRM_MAIL_FROM` | 邮箱格式；填了邮件 API Key 时必填 |
| 地址 | `url.kol-portal` | 达人端地址：邀请链接、邮件链接 | `TCRM_KOL_PORTAL_BASE_URL` | 见 3.3 |
| 地址 | `url.link` | 中转链接地址：`/t/…` 推广链接 | `TCRM_LINK_BASE_URL` | 见 3.3 |
| 地址 | `url.admin` | 管理端地址：微店授权回调 | `TCRM_ADMIN_BASE_URL` | 见 3.3；保存时提示「微店开放平台登记的回调域名需同步修改」 |
| 地址 | `url.upload` | 推广表上传地址（ADR-024 的上传子域名），可以为空，空时走管理端同源 | 原 `VITE_UPLOAD_BASE_URL` | 见 3.3，可以为空 |
| 地址 | `url.redirect-fallback` | 跳转服务找不到链接时的回退地址 | `TCRM_REDIRECT_FALLBACK_URL` | 见 3.3 |
| 业务默认值 | `copy-token.default-quota`、`copy-token.default-valid-minutes` | 复制令牌默认额度、默认有效分钟 | 1、1440 | 不超出下面的上限 |
| 业务上限 | `copy-token.max-quota`、`copy-token.max-valid-minutes` | 令牌额度上限、有效期上限 | 100000、10080 | 正整数 |
| 业务上限 | `shop.max-per-company` | 每家公司店铺数量上限 | 3 | 1～1000 |
| 业务上限 | `payout-account.max-per-kol` | 每位达人收款账户上限 | 5 | 1～20 |
| 业务默认值 | `kol.invite-ttl-hours` | 后台录入达人的邀请邮件有效期 | 72 | 1～720 |
| 业务默认值 | `oper-log.retention-days` | 操作日志保留天数 | 180 | 90～3650（规范 06 的最低要求如果更高，以规范为准） |
| 限流与安全 | `login.fail.pair-limit`、`login.fail.ip-limit`、`login.fail.account-limit`、`login.fail.window-minutes` | 登录失败锁定阈值（ADR-008） | 5、20、50、15 | 每项都有下限，不能关闭防护（例如次数 ≥ 3，窗口 ≥ 5 分钟） |
| 限流与安全 | `captcha.issue-per-minute` | 验证码每分钟签发上限 | 120 | 10～1000 |
| 限流与安全 | `kol.register.ip-per-hour`、`kol.register-preview.ip-per-hour` | 达人注册、注册预览每 IP 每小时上限 | 20、20 | 1～1000 |
| 限流与安全 | `kol.forgot.email-limit`、`kol.forgot.email-window-minutes`、`kol.forgot.ip-limit`、`kol.forgot.ip-window-minutes` | 找回密码限流 | 3、10、20、60 | 有下限 |
| 限流与安全 | `product-links.ip-per-minute`、`product-links.convert-token-per-minute`、`product-links.resolve-token-per-minute` | 对外复制接口限流（ADR-019） | 120、10、120 | 有下限 |
| 限流与安全 | `weidian.qps` | 微店接口每秒请求数 | 5 | 1～50 |

清单中的「建议键」可以按代码习惯调整，但分组与含义不变。如果实现中发现某一项在当前代码里无法不重启就生效，停下来报告，不要悄悄去掉。

### 3.2 不放进系统配置（保持现状）

- **密钥与凭证**：数据库与 Redis 密码、`TCRM_SECRET_KEY`、`TCRM_IP_HASH_SALT`、邮件 API Key、首次平台管理员账号。
- **基础设施，改了必须重启**：数据库与 Redis 的地址和端口、服务端口、跨域来源、可信代理、客户端 IP 头、线程池大小与队列、定时任务时间、点击流长度、达人登录会话时长与令牌名。
- **生产开关**：登录验证码开关、邮件链接写日志开关。
- **第三方接口地址**：微店、Google、汇率、Resend。
- **技术常量**：字段长度、加密参数、文件大小上限（与 Nginx、Spring multipart 绑定）。

在 `deploy/README.md` 中列出这两份清单。

### 3.3 地址类校验

- 必须是 `https://`，末尾不带 `/`，不能带路径（与现有的启动校验一致）。
- **例外**：允许 `http://localhost[:端口]` 和 `http://127.0.0.1[:端口]`，让本地预览能生成可以直接打开的链接。
- 环境变量中的默认值也按同样的规则校验：后端启动时的现有校验相应放宽这一个例外，其余不变。

## 4. 实现要求

### 4.1 后端

- 新表 `sys_config`：`config_key`（唯一）、`config_value`、`update_by`、`update_time`。
  - 这是平台级的表，不带 `tenant_id`，与 `sys_tenant` 同类：加入租户插件的忽略清单，并在设计说明书中写明这个规范 04 例外。
  - 表和字段都有注释。只保存被修改过的项；没有记录就使用默认值。
- 配置定义集中在一处：枚举或注册表，写明每一项的键、分组、类型（字符串、地址、整数、分钟等）、中英文名称的语言包键、默认值来源和校验规则。
- 读取服务（例如 `SystemConfigService`）：进程内缓存；保存时刷新缓存。只部署一个实例，不需要分布式缓存。
- **把清单中每一项的使用处改为从读取服务取值**，并且每次使用时取当前值，而不是启动时读一次。例如：
  - 限流器每次判断时读取阈值；
  - 生成邀请链接和邮件时读取地址；
  - 微店 QPS 限流器在配置变化后重建或调整速率。
- **跳转服务**（独立进程）：后端在启动时、以及保存 `url.redirect-fallback` 时，把当前值写入 Redis 的约定键，例如 `crm:config:redirect-fallback-url`。跳转服务读取这个键，本地缓存约 30 秒；键不存在时使用自己的环境变量。
- **接口**：
  - 平台管理员专用，其他账号 403：
    - `GET /admin-api/platform/system-configs`：按分组返回每一项的 `key`、`group`、`type`、`value`（当前生效值）、`defaultValue`、`overridden`、`min`、`max`；
    - `PUT /admin-api/platform/system-configs/{key}`，请求体 `{value}`：校验失败返回 400，错误码登记在 `ErrorCode`；
    - `DELETE /admin-api/platform/system-configs/{key}`：恢复默认。
  - 公开接口，不需要登录：
    - `GET /admin-api/public/site-config` → `{siteName, uploadBaseUrl}`；
    - `GET /kol-api/public/site-config` → `{siteName}`。
  - 每次修改写操作日志，摘要为「系统配置 {名称}：{旧值} → {新值}」。地址类可以明文记录，其余按现有规则。
- 设计说明书：§3 写明系统配置的定位与平台级例外，§6.2 / §6.3 登记接口（`ApiCatalogConsistencyTest` 必须通过），§10 写明部署时哪些值可以在界面中修改。

### 4.2 管理端

- 平台管理员菜单：「公司管理」旁边新增「系统配置」。其他角色看不到。
- 页面按分组显示（站点、地址、业务默认值与上限、限流与安全）。每一项显示：名称、当前值、默认值、「已修改」标记、编辑、恢复默认。整数类显示允许的范围。
- 修改 `url.admin` 时，弹出二次确认，说明需要同步修改微店后台登记的回调域名。
- 顶部说明：「保存后立即生效，无需重启。密钥类配置不在此处，仍由服务器环境变量管理。」
- 浏览器标题、左上角名称、登录页的系统名称改为从 `/public/site-config` 读取，读取失败时显示语言包中的默认值 SparkTrack。
- 推广表上传改为从 `/public/site-config` 读取 `uploadBaseUrl`，删除构建时变量 `VITE_UPLOAD_BASE_URL`，同步 `deploy/.env.example` 和 README。

### 4.3 达人端

- 浏览器标题、登录页和注册页的系统名称从 `/kol-api/public/site-config` 读取，读取失败时使用默认值。

## 5. 测试与验证

**单元测试与集成测试**（必须）：

- 每个配置项的默认值、覆盖、恢复默认、校验（含地址的 localhost 例外、整数上下限）；
- 缓存刷新：保存后立即生效（集成测试中修改后马上读取到新值）；
- 代表性的使用处已经改为读取配置：修改 `url.kol-portal` 后，邀请链接马上变化；修改 `kol.register.ip-per-hour` 后，限流阈值马上变化；修改 `copy-token.default-quota` 后，新建令牌使用新默认值；修改 `site.name` 后，邮件中的系统名变化；
- 跳转服务：Redis 键存在时使用其值，不存在时回退环境变量；
- 权限：非平台管理员访问配置接口 403；公开接口不需要登录，并且只返回约定的字段；
- 前端：配置页的分组、编辑、恢复默认、管理端地址的二次确认；系统名称与上传地址从接口读取。

**检查与复验，用子代理完成**（使用你所在工具的子代理功能，例如 Claude Code 的 Agent / Task 工具），主代理负责开发、汇总和修复：

1. **检查子代理**：后端 `mvn spotless:apply && mvn verify && mvn verify -Pdocker-it`；跳转服务 `cd redirect && mvn verify`（有 docker-it 时一并执行）；两个前端 `pnpm install --frozen-lockfile && pnpm lint && pnpm typecheck && pnpm test && pnpm build && pnpm notices:check`。
2. **复核子代理**：只读。对照本提示词第 1、3、4 节逐条检查，**特别核对 3.1 清单中的每一项是否真的在所有使用处改为读取配置**（用 grep 找出常量和 `@Value` 的残留），返回「要求 → 实现位置 → 是否满足 → 差异」清单。
3. **浏览器验证子代理**：在任务 35 合并、负责人通知后执行，连接共享中间件。
   - 用 `deploy/.env.local`；后端端口 18263，管理端 Vite `--port 8831 --strictPort`，达人端 Vite `--port 8832 --strictPort`；跳转服务如需本地启动，用 18265；
   - 测试账号一律加前缀 `t34_`；
   - 逐项截图：
     - a. 平台管理员看到「系统配置」，超管和商务看不到；
     - b. 修改达人端地址为 `http://localhost:8832` 后，不重启，超管的邀请弹窗立即显示新链接，并且能打开注册页；
     - c. 修改系统名称后，两个前端的标题变化；
     - d. 修改管理端地址时出现二次确认；
     - e. 恢复默认；
     - f. 操作日志中有修改记录。
   - 截图放在 `admin-ui/screenshots/34/`、`kol-portal/screenshots/34/`。

## 6. 交付

- 执行报告 `docs/04-开发任务/34-系统配置-执行报告.md`：配置项清单（键、默认值、使用处）、未纳入项及理由、复核清单、测试与截图。
- 收尾：
  - 停掉自己启动的进程；
  - 删除自己在共享数据库中造的 `t34_` 测试数据；**把改过的系统配置全部恢复默认**；
  - **不得停止或删除共享中间件的容器和数据卷**；
  - Testcontainers 残留容器一并删除；不做全局清理。
  - 在报告中写明清理了什么。
- 提交遵循 Conventional Commits（英文），推送分支，按 PR 模板创建 PR，PR 描述中贴出子代理的结果。**不要合并 PR，不要推送 `main`。**

## 7. 禁止事项与停止条件

**禁止**：

- 把 3.2 中的任何一项放进系统配置；
- 修改已合并的迁移；新增依赖；跨模块直接写 SQL；删除或弱化测试；降低覆盖率门槛；
- 推送 `main`、合并 PR、rebase、`push --force`、`reset --hard`；
- 停止或删除共享中间件；全局 Docker 清理。

**停下来报告**：

- 清单中某一项无法不重启就生效；
- 平台级的表与租户插件或规范 04 冲突，且无法按 `sys_tenant` 的方式处理；
- 合理排查后验证仍然失败。

完成后回复一段总结：PR 链接、配置项清单、子代理复核与验证结果、遗留问题。
