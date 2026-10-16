# 任务 12c（后端 + 达人端）：达人通过商务的注册链接自助注册

> 本文件是交给编码代理执行的提示词，把全文作为任务输入即可。
>
> **并行说明**：
> - 与 11c（管理端）、13（订单）、15a（收款账户）、16（商务转移）同时进行。
> - 11c 按第 4.7 节的接口约定做管理端的「我的注册链接」与「账号状态」，本任务不改 `admin-ui/`（错误码文案除外，见第 3 节）。
> - 迁移脚本版本号固定为 `V202610171500`。

---

## 1. 背景

客户 2026-10-14 会议确定：

- 达人入驻以**自助注册**为主：商务把自己的注册链接发给达人；
- 达人只填邮箱、WhatsApp、主阵地（TikTok、Instagram、YouTube 等）；
- 系统生成唯一的达人编号，并自动归属到该商务；
- 不设审核，资料由商务事后补全；
- 客户强调「达人填得越少越好」。

任务 12a 已完成达人账号（登录、激活、找回密码、邮件发送），任务 11 已完成达人档案的新字段。本任务补上注册链接和自助注册。

## 2. 开始前必读

1. `AGENTS.md`、`docs/05-工程规范/`（重点：03、04、06）。
2. 设计说明书：
   - §2.4 国际化：邮件中英文都写；
   - §3.4 达人端登录与公司；
   - §4.2 达人入驻：「自助注册」第 1～5 条；
   - §5.2：`crm_kol` 的修订字段、`crm_kol_register_code`；
   - §6.3。
3. `docs/04-开发任务/12a-达人账号-后端-执行报告.md`：达人令牌、登录结果格式、邮件发送与模板、失败计数。
4. `docs/04-开发任务/12b-达人登录-达人端-执行报告.md`：达人端的会话与页面写法。
5. 现有代码：
   - `kol` 模块：`KolAuthService`、`KolMails`、`KolMailTemplates`、`KolService`、`KolWriter`、`KolCodeGenerator`、`KolVO`；
   - `system` 模块中判断「可作为商务」的规则（`BdUserService`）；
   - `kol-portal/src` 的 `views/login`、`api/auth.ts`、`stores/session.ts`。

## 3. 执行环境与交付

- 在独立的 git 工作树中工作，分支 `task/12c-kol-self-register` 已从最新的 `main` 检出。
- **只修改 `backend/`、`kol-portal/`、你的执行报告，以及第 4.8 节列出的文档。**
- 新增错误码时，按规范 03 同时在 `admin-ui/src/locales/{zh-CN,en-US}.ts` 补错误文案，`admin-ui` 的其他文件不得改动。
- 检查：
  - 后台：JDK 21 下 `mvn spotless:apply`、`mvn verify`、`mvn verify -Pdocker-it`；
  - 达人端：`pnpm install --frozen-lockfile && pnpm lint && pnpm typecheck && pnpm test && pnpm build && pnpm notices:check`。

  全部通过。
- 推送分支，按 PR 模板创建 PR，并用 `gh pr checks --watch` 等 CI 通过。**不要合并 PR，不要推送 `main`。**
- **不新增依赖。**

## 4. 已定的设计决定（不要自行改变）

### 4.1 迁移 `V202610171500__kol_register_code.sql`

新表 `crm_kol_register_code`：

| 字段 | 说明 |
|---|---|
| `id` | 雪花 ID，正值 |
| `tenant_id` | NOT NULL |
| `bd_user_id` | NOT NULL |
| `code` | `VARCHAR(32)`，全表唯一 |
| `status` | `SMALLINT`：1 有效、0 已作废 |
| 通用字段 | 含 `update_by`、`update_time`，不需要逻辑删除 |

部分唯一索引 `(bd_user_id) WHERE status = 1`：每个商务最多一个有效注册码。表和字段都要有中文注释。

### 4.2 注册码（管理端）

1. `GET /admin-api/kol/register-code`，权限 `kol:register-link`：
   - 返回当前账号的有效注册码；没有时自动生成一个；
   - 响应 `{code, url, status}`，`url = {TCRM_KOL_PORTAL_BASE_URL}/register?c={code}`。
2. `POST /admin-api/kol/register-code`，权限 `kol:register-link`：同一事务内把旧码置为作废并生成新码，返回同样的结构。
3. 注册码为 32 位随机 Base62（`SecureRandom`），明文保存（设计 §5.2：它只决定公司和归属商务，不是登录凭证）。
4. 只有「可作为商务」的账号能拿到注册码：同公司、启用、`STAFF`、`OWN`、有 `kol:edit`，规则与 `BdUserService` 相同、复用同一处代码。其他账号（包括超管）返回 422 `KOL_INVALID_BD_USER`。
5. 写入操作日志。

### 4.3 自助注册（达人端，均为公开接口）

| 方法与路径 | 说明 |
|---|---|
| `GET /kol-api/register/{code}` | 注册码有效（存在、`status=1`，所属商务仍「可作为商务」，所属公司启用）时返回 `{tenantName}`；否则 404 `KOL_REGISTER_CODE_INVALID` |
| `POST /kol-api/register/email-codes` | `{code, email}`：注册码必须有效。向邮箱发送 6 位数字验证码，10 分钟有效；同一邮箱 60 秒内只能发一次（返回 429 `KOL_EMAIL_CODE_TOO_FREQUENT`）。邮箱已在该公司注册时**不发码**，直接返回 409 `KOL_EMAIL_EXISTS` |
| `POST /kol-api/register` | 见下 |

`POST /kol-api/register` 的请求体：

```json
{
  "code": "...",
  "email": "...",
  "emailCode": "123456",
  "password": "...",
  "whatsapp": "+...",
  "primaryPlatform": "TikTok",
  "primaryHandle": "@xxx",
  "primaryUrl": "https://...",
  "name": "...",
  "country": "US"
}
```

- `primaryUrl`、`name`、`country` 可选，其余必填。
- `primaryPlatform` 只接受 `TikTok`、`Instagram`、`YouTube`、`X`、`Facebook`、`Other`。

处理规则：

1. **注册码**：重新校验，必须有效。
2. **邮箱验证码**：
   - 存在 Redis，`kol:register:email-code:{tenantId}:{邮箱小写的 SHA-256}`，值里同时存验证码的哈希和剩余次数；
   - 错误最多 5 次，用完作废，返回 400 `KOL_EMAIL_CODE_INVALID`；
   - 正确后立即删除（只能用一次）。
3. **字段格式**：与任务 11 一致（WhatsApp、国家）；密码规则与 12a 相同。
4. **创建达人**：
   - 生成达人编号；
   - `register_source = 2`；
   - `account_status = 1`（已激活），保存密码哈希；
   - `bd_user_id` 为注册码所属商务，并写一条商务归属历史；
   - 社媒账号写入一条 `{platform, handle, url, primary: true}`；
   - 未填名称时，`name` 取 `primaryHandle`。
5. **邮箱冲突**：邮箱在本公司已存在时返回 409 `KOL_EMAIL_EXISTS`。前端显示「该邮箱已注册，请直接登录」。并发重复提交由唯一索引兜底，同样返回 409。
6. **自动登录**：注册成功后直接登录，响应与 `POST /kol-api/auth/login` 成功时相同（`{tokenName, token, expiresIn, kol}`）。
7. **限流**：
   - `email-codes`：同一 IP 每小时最多 20 次；
   - `register`：同一 IP 每小时最多 20 次、同一邮箱每小时最多 10 次。

   超出返回 429 `KOL_REGISTER_TOO_FREQUENT`。Redis 键使用独立前缀。
8. **审计**：注册成功用 12a 的达人操作审计记录（操作者为该达人）。
9. **日志**：日志中不得出现验证码、密码或完整邮箱。

### 4.4 验证码邮件

- 沿用 12a 的 `MailSender` 与模板写法，英文在前、中文在后，写明公司名称。
- 主题：`[{公司名}] Your verification code / 您的验证码`。
- 正文含验证码与有效期（10 分钟）。
- `crm.mail.log-links=true` 时，日志额外输出验证码，仅供本机开发使用，与 12a 的链接日志开关相同。

### 4.5 `KolVO` 增加账号状态

管理端达人列表与详情的 `KolVO` 增加 `accountStatus`（0 未激活 / 1 已激活），供 11c 显示与判断是否显示「发送邀请」。

### 4.6 达人端注册页

1. **路由** `/register?c={code}`，公开。
   - 进入时校验注册码；无效时显示「注册链接无效或已失效，请联系您的商务获取新链接」，并提供「去登录」。
   - 有效时在标题下显示「加入 {公司名}」。
2. **表单**（手机优先，单列）：
   - 邮箱 + 「发送验证码」按钮：60 秒倒计时，重复点击时显示接口返回的提示；
   - 验证码；
   - 密码、确认密码（规则提示同 12b）；
   - WhatsApp；
   - 主阵地：平台下拉 + 账号；可选的主页链接；
   - 「更多（可选）」折叠区：名称、国家。
3. **提交**：成功后保存会话（与 12b 登录相同）并进入首页。`KOL_EMAIL_EXISTS` 时显示提示和「去登录」链接（带上邮箱）。
4. **登录页**不加注册入口：注册只能通过商务的链接。
5. 所有文字放进中英两份语言包；新错误码的文案写进 `kol-portal/src/api/error-codes.ts` 与语言包，并同步写进管理端语言包（见第 3 节）。

### 4.7 与 11c 共用的接口约定（不得改动）

```text
GET  /admin-api/kol/kols、/{id}       → KolVO 新增 accountStatus: 0|1
GET  /admin-api/kol/register-code     → data: { code, url, status }    （kol:register-link；没有时自动生成）
POST /admin-api/kol/register-code     → data: { code, url, status }    （作废旧码并生成新码）
错误码：KOL_INVALID_BD_USER 422（不可作为商务的账号请求注册码）
```

### 4.8 文档

- 设计说明书：
  - §4.2「自助注册」与本任务实际实现一致（必要时更新限流与验证码规则）；
  - §5.2：`crm_kol_register_code` 写上实际字段；
  - §6.2、§6.3：接口表。
- 不需要新的环境变量。

### 4.9 不在本任务范围

- 管理端的注册链接与账号状态界面（11c）。
- 注册后的资料补全界面（11c 的达人表单已覆盖）。
- 达人看板、资金（以后任务）。

## 5. 执行步骤

1. 迁移与注册码接口。
2. 验证码发送（含邮件模板、限流）。
3. 注册接口与自动登录。
4. `KolVO.accountStatus`。
5. 达人端注册页。
6. 测试、文档、执行报告。

## 6. 测试要求

| 场景 | 期望 |
|---|---|
| 注册码 | 商务首次 GET 自动生成；POST 后旧码失效；超管、`ALL` 子账号、无 `kol:edit` 的子账号返回 422；跨公司隔离 |
| 注册码失效 | 码作废、商务停用、商务失去 `kol:edit` 或被改为 `ALL`、公司停用时，GET 返回 404，注册与发码都被拒绝 |
| 验证码 | 60 秒限频；10 分钟过期；错误 5 次作废；正确后只能用一次；已注册邮箱返回 409 且不发码 |
| 注册 | 成功创建达人（来源 2、已激活、归属商务、有商务归属历史、主阵地）并返回登录结果；未填名称时取账号名；邮箱冲突 409；并发重复提交只成功一次 |
| 限流 | IP 与邮箱两个维度，超出返回 429 |
| 日志 | 日志中没有验证码、密码、完整邮箱（`log-links` 关闭时） |
| `KolVO` | 列表与详情返回 `accountStatus` |
| 达人端 | 无效码提示；发码倒计时；表单校验；成功后进入首页；409 显示去登录 |
| 国际化 | 新键在两端语言包中一致；残留中文测试通过 |

**浏览器验证**（写进报告并附截图）：

- 用商务账号通过接口取得注册链接，在达人端完成注册（验证码从日志获取），注册后进入首页；
- 管理端达人列表能看到这位达人，来源为「自助注册」、已激活、归属该商务；
- 截图：注册页（中、英各一张）、无效链接提示、手机宽度。

## 7. 文档与报告

执行报告写在 `docs/04-开发任务/12c-达人自助注册-执行报告.md`：迁移、接口与示例响应、限流与验证码规则、邮件模板全文、测试清单、浏览器验证与截图、偏离与遗留。

## 8. 禁止事项与停止条件

**禁止**：

- 修改已合并的迁移脚本；
- 新增依赖；
- 修改 `redirect/`、`deploy/`，以及 `admin-ui/` 中语言包以外的文件；
- 在日志、异常或响应中输出验证码、密码明文；
- 删除或弱化测试；
- 推送 `main` 或合并 PR；
- 使用 `push --force`、`reset --hard`。

**遇到以下情况停下来**（推送已完成的提交，以 `[WIP]` 标题创建 PR，在报告中说明）：

- 设计说明书与本提示词冲突；
- 与 16（商务转移）对 `kol` 模块的改动发生无法自行判断的冲突；
- 验证在合理排查后仍失败。
