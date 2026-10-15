# 任务 12a（后端）：达人账号——登录、激活、找回密码与邮件发送

> 本文件是交给编码代理执行的提示词，把全文作为任务输入即可。
>
> **并行说明**：
> - 与 10a（可配置权限）、11（达人档案扩展）、12b（达人端页面）同时进行。12b 按第 4.8 节的接口约定开发。
> - 10a 或 11 先合并时，rebase 到最新 `main` 再继续。
> - 迁移脚本版本号固定为 `V202610171200`。

---

## 1. 背景

达人端目前只有本地占位登录，没有任何 `/kol-api/**` 接口。

客户确认的入驻方式（2026-10-14）以「达人通过商务的注册链接自助注册」为主，由后续任务 12c 实现；「商务在后台录入达人并发邀请邮件」作为兜底。两种方式都需要达人账号、登录与找回密码，这就是本任务。

设计已定，见：

- 设计说明书 §3.4「达人端登录」1～6 条：邮箱 + 密码；同一邮箱可在多家公司各有账号，密码匹配多个账号时让达人选择公司；
- §4.2「后台录入」；
- §6.3；§7.3 邮件。

## 2. 开始前必读

1. `AGENTS.md`、`docs/05-工程规范/`（重点：03、04、06）。
2. 设计说明书 §2.4（国际化：邮件中英文都写）、§3.3、§3.4（达人端登录、公司状态）、§4.2、§6.3、§7.3。
3. ADR-008（登录失败分层计数）、ADR-014、ADR-017 第 11 条（达人端是否加验证码另行评估，本任务的决定见 4.4）。
4. 现有代码：
   - `security` 模块：`AuthService`、`LoginFailures`、`CurrentUserContext`、`StpKolUtil`、`SecurityConfiguration`；
   - `kol` 模块：`KolService`、`KolWriter`；
   - `shared` 中的公司范围工具（`TenantScope`）；
   - `application.yml` 的 `sa-token` 段。

## 3. 执行环境与交付

- 在独立的 git 工作树中工作，分支 `task/12a-kol-account-backend` 已从最新的 `main` 检出。
- **只修改 `backend/`、`deploy/docker-compose.prod.yml` 与环境变量样例（只新增 4.6 的变量）、你的执行报告，以及第 4.9 节列出的文档。**
- JDK 21 下执行 `mvn spotless:apply`、`mvn verify`、`mvn verify -Pdocker-it`，全部通过。
- 推送分支并按 PR 模板创建 PR，用 `gh pr checks --watch` 等 CI 通过。**不要合并 PR，不要推送 `main`。**

## 4. 已定的设计决定（不要自行改变）

### 4.1 迁移 `V202610171200__kol_account.sql`

1. `crm_kol` 新增列：
   - `password_hash VARCHAR(100) NULL`：BCrypt；
   - `account_status SMALLINT NOT NULL DEFAULT 0`：0 未激活 / 1 已激活；
   - `credential_version INT NOT NULL DEFAULT 0`；
   - `last_login_time TIMESTAMPTZ(3) NULL`。

   约束：`CHECK (account_status = 0 OR password_hash IS NOT NULL)`。
2. 新表 `crm_kol_auth_token`：
   - 字段：`id`、`tenant_id`、`kol_id`、`purpose VARCHAR(8)`（`INVITE` / `RESET`）、`token_hash CHAR(64)`（唯一）、`expires_at`、`used_at`、`create_time`；
   - 索引 `(kol_id, purpose)`；只追加，用过置 `used_at`。
3. 表与列都要有中文注释。

### 4.2 达人令牌体系

1. `StpKolUtil.STP` 使用独立配置，不受 `sa-token` 全局配置影响：
   - 令牌名 `X-Kol-Token`，只从请求头读取；
   - 绝对有效期 7 天，空闲 3 天失效；
   - 允许多端登录，每个达人最多 10 个令牌。

   这些值写成配置项 `crm.kol-auth.*`，配上默认值。

   注意：`application.yml` 中 `sa-token.active-timeout` 的注释写着「对全部账号体系生效」，要相应改掉，并在设计说明书 §3.3「空闲超时」一行中写明达人端的取值。
2. 登录态绑定达人 ID 与公司 ID。每次请求按达人 ID 重读：
   - 达人状态、账号状态、`credential_version`，以及所属公司状态；
   - 达人停用、公司停用或凭据版本不一致时，注销该令牌并返回 401（`AUTH_UNAUTHORIZED` / `AUTH_ACCOUNT_DISABLED`），与管理端一致；
   - 读取结果按请求缓存，每个请求只查一次。
3. 新增 `/kol-api/**` 拦截器：
   - 除 4.3 中标为「公开」的接口外都要求达人登录；
   - 进入后用 `TenantScope` 打开该达人所属公司的范围；
   - 管理员令牌不能访问 `/kol-api/**`，达人令牌也不能访问 `/admin-api/**`，各写测试。
4. 达人端接口在 `ArchitectureTest` 中同样必须声明（公开接口用 `@PublicEndpoint`，其余用一个新的达人登录注解或 `@SaCheckLogin(type = "kol")`），规则一并扩展。

### 4.3 接口

| 方法与路径 | 认证 | 说明 |
|---|---|---|
| `POST /kol-api/auth/login` | 公开 | `{email, password}`。按 §3.4 第 1、2 条处理：0 个匹配返回 401 `AUTH_INVALID_CREDENTIALS`；1 个直接登录；多个返回 `{selectionToken, companies:[{tenantId, tenantName}]}` |
| `POST /kol-api/auth/login/select` | 公开 | `{selectionToken, tenantId}`；凭据存 Redis，5 分钟有效、只能用一次，只能换取已通过密码校验的账号 |
| `POST /kol-api/auth/logout` | 达人 | 注销当前令牌 |
| `POST /kol-api/auth/activate` | 公开 | `{token, password}`：邀请令牌激活并设置密码，成功后直接返回登录结果 |
| `POST /kol-api/auth/forgot` | 公开 | `{email}`：始终返回成功；该邮箱在各启用公司有已激活的账号时，发一封邮件，按公司分别给出重置链接 |
| `POST /kol-api/auth/reset` | 公开 | `{token, password}`：重置密码，`credential_version` 加 1（旧令牌全部失效） |
| `GET /kol-api/me` | 达人 | 本人资料：`kolCode`、`name`、`email`、`country`、`tenantName`；11 合并后补 `whatsapp`、`sheetUrl` |
| `PUT /kol-api/me/password` | 达人 | `{oldPassword, newPassword}`；成功后 `credential_version` 加 1，签发新令牌返回，当前会话保持登录 |
| `POST /admin-api/kol/kols/{id}/invite` | 与达人编辑相同的权限（10a 后为 `kol:edit`） | 重发邀请：作废未使用的旧邀请，签发新邀请；已激活的达人返回 409 `KOL_ALREADY_ACTIVATED` |

登录成功的响应：`{tokenName: "X-Kol-Token", token, expiresIn, kol: {同 /me}}`。

### 4.4 规则

1. **密码**：10～72 位可打印 ASCII 字符，与管理端相同；BCrypt。
2. **失败计数与锁定**：按 ADR-008 分三层（邮箱 + 来源 IP、来源 IP、邮箱），阈值与管理端相同，Redis 键使用独立前缀，与管理员互不影响。锁定返回 429 `AUTH_ACCOUNT_LOCKED`。账号不存在时也执行一次 BCrypt。
3. **验证码**：达人端登录**不使用图形验证码**（负责人 2026-10-16 决定：客户要求达人操作尽量少；防护靠锁定与 Nginx 限速）。在设计说明书 §3.4 达人端登录中补一句。
4. **未激活与停用**：
   - 未激活的账号没有密码，按密码错误处理；
   - 密码正确但达人或公司已停用，返回 401 `AUTH_ACCOUNT_DISABLED`。
5. **邀请令牌**：32 字节随机数，Base64URL 编码，库里只存 SHA-256。有效期 72 小时，只能用一次。
6. **重置令牌**：同样存哈希，有效期 30 分钟，只能用一次。重置成功后，该达人其他未使用的重置令牌一并作废。
7. **自动发送邀请**：后台**单个新建**的达人在事务提交后自动发送邀请邮件；发送失败只记 `WARN`，不回滚，可用重发接口补发。Excel 导入**不**自动发送，避免超出邮件服务的每日额度。
8. **找回密码限流**：同一邮箱 10 分钟内最多 3 封，同一 IP 每小时最多 20 次；超出时仍返回成功，但不发送。
9. **审计**：激活、改密、重置写入操作日志；日志中不得出现密码、令牌、重置链接。

### 4.5 邮件

1. `integration` 模块新增 `MailSender` 接口与 Resend 实现：
   - 使用 JDK `HttpClient` 调用 `POST https://api.resend.com/emails`，`Authorization: Bearer {key}`；
   - 超时 10 秒，失败重试 2 次（退避 1、3 秒）；
   - 不新增依赖。
2. 未配置 API Key 时使用 `LoggingMailSender`：只记录收件人与模板名。仅在配置 `crm.mail.log-links=true`（默认 false，只用于本机开发）时额外记录链接，代码与配置注释要写明「生产禁止开启」。
3. **模板**：邀请、重置密码两种。
   - 每封邮件同时包含英文和中文，英文在前，写明公司名称；
   - 正文为纯文本 + 简单 HTML，链接指向达人端：`{TCRM_KOL_PORTAL_BASE_URL}/activate?token=...` 与 `/reset?token=...`；
   - 主题：`[{公司名}] Set up your creator account / 设置您的达人账号`，以及 `[{公司名}] Reset your password / 重置密码`。
4. 测试用捕获型实现断言收件人、主题、链接。

### 4.6 新增环境变量（平台级）

| 变量 | 说明 |
|---|---|
| `TCRM_MAIL_API_KEY` | Resend API Key；为空时使用日志实现 |
| `TCRM_MAIL_FROM` | 发件人，例如 `T-CRM <no-reply@域名>`；配置了 Key 却没配本项时，启动失败 |
| `TCRM_KOL_PORTAL_BASE_URL` | 达人端地址，例如 `https://kol.域名`；必填，要求 https 且无末尾斜杠，校验方式与 `TCRM_LINK_BASE_URL` 相同 |

同步 `deploy/docker-compose.prod.yml` 中 `crm-admin` 的环境变量与样例文件。

### 4.7 不在本任务范围

- 自助注册、注册链接、邮箱验证码（任务 12c）。
- 达人端页面（任务 12b）。
- 达人看板、订单、资金接口（后续任务）。

### 4.8 与 12b 共用的接口约定（不得改动）

```text
请求头：X-Kol-Token: <token>；Accept-Language
POST /kol-api/auth/login          ← {email, password}
                                  → data: {tokenName, token, expiresIn, kol} 或 {selectionToken, companies:[{tenantId, tenantName}]}
POST /kol-api/auth/login/select   ← {selectionToken, tenantId} → data: {tokenName, token, expiresIn, kol}
POST /kol-api/auth/logout
POST /kol-api/auth/activate       ← {token, password} → data: {tokenName, token, expiresIn, kol}
POST /kol-api/auth/forgot         ← {email} → data: null（始终成功）
POST /kol-api/auth/reset          ← {token, password} → data: null
GET  /kol-api/me                  → data: {kolCode, name, email, country, tenantName, whatsapp?, sheetUrl?}
PUT  /kol-api/me/password         ← {oldPassword, newPassword} → data: {tokenName, token, expiresIn, kol}
错误码：AUTH_INVALID_CREDENTIALS 401、AUTH_ACCOUNT_LOCKED 429、AUTH_ACCOUNT_DISABLED 401、
       AUTH_UNAUTHORIZED 401、KOL_AUTH_TOKEN_INVALID 400（邀请或重置令牌无效、过期、已用）、
       AUTH_OLD_PASSWORD_INCORRECT 422、KOL_LOGIN_SELECTION_INVALID 400
```

### 4.9 文档

- 设计说明书：
  - §3.3：写明达人端令牌的有效期；
  - §3.4：达人端登录不使用验证码；
  - §6.3：与 4.8 一致；
  - §12：新增的三个环境变量。
- 部署说明（`deploy/README.md`）：补充邮件服务的配置步骤，包括发件域名的 SPF、DKIM、DMARC，以及 Resend 控制台中的操作。

## 5. 执行步骤

1. 迁移与达人令牌体系、拦截器。
2. 登录、选择公司、登出、`/me`、改密。
3. 邀请（含自动发送、重发）、激活。
4. 找回、重置。
5. 邮件发送。
6. 测试、文档、执行报告。

## 6. 测试要求

| 场景 | 期望 |
|---|---|
| 单公司登录 | 正确密码登录，返回令牌；错误密码 401 |
| 多公司 | 同一邮箱在两家公司、相同密码时返回选择凭据；凭据只能用一次、5 分钟后失效、不能换取未通过密码校验的账号 |
| 锁定 | 三层计数与管理端一致；锁定返回 429；与管理员的计数互不影响 |
| 停用 | 达人或公司停用后，登录返回 `AUTH_ACCOUNT_DISABLED`；已签发令牌的下一次请求返回 401 |
| 令牌隔离 | 达人令牌访问 `/admin-api/**`、管理员令牌访问 `/kol-api/**` 都返回 401 |
| 邀请 | 新建达人后自动发邮件；重发后旧令牌失效；72 小时后失效；用过后失效；已激活时重发返回 409 |
| 找回 | 未知邮箱也返回成功且不发邮件；多公司时一封邮件含多个链接；限流；重置后旧令牌全部失效 |
| 改密 | 旧密码错误 422；成功后旧令牌失效、新令牌可用 |
| 公司范围 | 达人端请求自动带上所属公司；查不到另一家公司的数据 |
| 日志 | 任何日志中不出现密码、令牌、重置链接（`log-links` 关闭时） |

## 7. 文档与报告

执行报告写在 `docs/04-开发任务/12a-达人账号-后端-执行报告.md`，内容包括：迁移、令牌配置、接口实际响应示例（供 12b 核对）、邮件模板全文、环境变量、测试清单、偏离与遗留。

## 8. 禁止事项与停止条件

**禁止**：修改已合并的迁移脚本；新增依赖；修改 `admin-ui/`、`kol-portal/`、`redirect/`；在日志、异常信息或响应中输出密码、令牌明文；删除或弱化测试；推送 `main` 或合并 PR；使用 `push --force`、`reset --hard`。

**遇到以下情况停下来**（推送已完成的提交，以 `[WIP]` 标题创建 PR，在报告中说明）：

- Sa-Token 无法为达人体系设置独立的超时与令牌名；
- 与 10a、11 的改动发生无法自行判断的冲突；
- 设计说明书与本提示词冲突；
- 验证在合理排查后仍失败。
