# 任务 12b（达人端）：登录、选择公司、激活、找回密码与修改密码

> 本文件是交给编码代理执行的提示词，把全文作为任务输入即可。
>
> **并行说明**：与 12a（达人账号后端）同时进行，按第 4.4 节的接口约定开发，不依赖 12a 已合并。联调时以 12a 的分支为准。

---

## 1. 背景

达人端（`kol-portal/`）现在只有占位登录页，输入框是禁用的。任务 12a 提供了达人账号接口：

- 邮箱 + 密码登录；同一邮箱在多家公司各有账号且密码相同时，先选择公司；
- 后台录入的达人通过邀请邮件中的链接激活并设置密码；
- 找回密码与修改密码。

本任务把这些做成达人端页面。客户要求达人操作尽量少、界面简单（2026-10-14 会议），达人端默认英文，支持中英文切换，并适配手机。

## 2. 开始前必读

1. `AGENTS.md`、`docs/05-工程规范/`（重点：02 第 7 节、05、06 第 3 节）。
2. 设计说明书 §2.4（国际化）、§3.4「达人端登录」、§6.3。
3. `docs/04-开发任务/06d-中英文切换-达人端-执行报告.md` 第 2 节：国际化写法。
4. 现有代码：`kol-portal/src` 下的 `router/`（`guard.ts`、`routes.ts`）、`stores/session.ts`、`utils/session.ts`、`api/`（`http.ts`、`constants.ts`、`error-codes.ts`、`setup.ts`）、`views/login/`、`layout/`、`locales/`。

## 3. 执行环境与交付

- 在独立的 git 工作树中工作，分支 `task/12b-kol-login-portal` 已从最新的 `main` 检出。
- **只修改 `kol-portal/` 和你的执行报告。**
- 在 `kol-portal/` 下执行 `pnpm install --frozen-lockfile && pnpm lint && pnpm typecheck && pnpm test && pnpm build && pnpm notices:check`，全部通过。
- 推送分支并按 PR 模板创建 PR，用 `gh pr checks --watch` 等 CI 通过。**不要合并 PR，不要推送 `main`。**
- **不新增依赖。**

## 4. 已定的设计决定（不要自行改变）

### 4.1 页面与路由

| 路由 | 公开 | 内容 |
|---|:-:|---|
| `/login` | ✓ | 邮箱、密码、登录按钮、「忘记密码？」链接、语言切换。密码匹配多家公司时，在同一页弹出公司选择（单选列表，显示公司名称），选中后完成登录 |
| `/activate?token=` | ✓ | 设置密码（两次输入）。成功后直接登录进入首页。令牌无效或过期时提示「链接已失效，请联系您的商务重新发送」 |
| `/forgot` | ✓ | 输入邮箱。提交后不论邮箱是否存在，都显示「如果该邮箱已注册，我们已发送重置邮件」 |
| `/reset?token=` | ✓ | 设置新密码（两次输入）。成功后跳转登录页并提示「密码已重置，请登录」 |
| `/account` | | 本人资料（只读：达人编号、名称、邮箱、国家、公司；有表格地址时显示可点击的链接），以及修改密码表单 |
| `/` | | 首页保持现有内容，页头显示本人名称与公司，用户菜单提供「我的账号」「退出」 |

### 4.2 会话

1. 登录成功后保存令牌与到期时间（沿用 `utils/session.ts`）、达人资料；请求头 `X-Kol-Token`。
2. 任何请求返回 401 时：清除会话，跳转 `/login?redirect=<当前路径>`，并提示「登录已过期，请重新登录」；登录页自己的 401 只在页面上提示。达人端**不做**原地重新登录弹窗。
3. 修改密码成功后，用响应中的新令牌替换会话，保持登录。
4. 退出：调用登出接口，失败也清除本地会话。

### 4.3 表单规则

1. 邮箱：必填，格式校验。
2. 密码：10～72 位可打印 ASCII，与后端一致；两次输入必须相同。
3. 提交按钮在请求期间显示加载并禁止重复点击。
4. 错误码对应的文案在 `api/error-codes.ts` 与语言包中补齐（4.4 列出的全部错误码）。
5. 手机宽度（≤ 480px）下表单铺满宽度，按钮至少 44px 高。

### 4.4 接口约定（与 12a 共用，不得改动）

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

`kol` 对象与 `/me` 相同。`expiresIn` 单位为秒。选择凭据失效（`KOL_LOGIN_SELECTION_INVALID`）时关闭公司选择框，提示重新登录。

### 4.5 不在本任务范围

- 自助注册页（任务 12c）。
- 佣金、提现、收款账户等页面（后续任务）。
- 后端（任务 12a）。

## 5. 执行步骤

1. 接口封装与类型。
2. 会话与路由守卫（401 处理、redirect）。
3. 登录与公司选择。
4. 激活、找回、重置。
5. 我的账号与修改密码；页头用户菜单。
6. 语言包、测试、执行报告。

## 6. 测试要求

| 场景 | 期望 |
|---|---|
| 登录 | 单公司直接进入；多公司弹出选择，选中后进入；凭据失效时提示 |
| 错误 | 密码错误、锁定、停用分别显示对应文案 |
| redirect | 带 redirect 时登录后回到原页；只接受站内路径 |
| 401 | 已登录状态下接口返回 401 时跳转登录页并清除会话 |
| 激活 | 两次密码不一致不提交；令牌失效提示；成功后进入首页 |
| 找回 | 提交后始终显示同一提示 |
| 重置 | 成功后跳转登录页并提示 |
| 改密 | 旧密码错误显示 422 文案；成功后会话令牌被替换 |
| 国际化 | 新键在两份语言包中一致；中英文切换后页面文字随之改变 |

现有测试不得删除或弱化。

**浏览器验证**（结果写进报告并附截图）：连接 12a 分支的后端，后台新建一个达人；从日志获取激活链接（`crm.mail.log-links=true`），激活、退出、登录、修改密码、找回并重置。截图：

- 登录页、公司选择、激活页，中英文各一张；
- 手机宽度的登录页。

## 7. 文档与报告

执行报告写在 `docs/04-开发任务/12b-达人登录-达人端-执行报告.md`：页面与路由、会话处理、错误码文案、测试清单、浏览器验证与截图、偏离与遗留。

## 8. 禁止事项与停止条件

**禁止**：修改 `kol-portal/` 以外的文件；新增依赖；改变 4.4 的接口约定；删除或弱化测试；推送 `main` 或合并 PR；使用 `push --force`、`reset --hard`。

**遇到以下情况停下来**（推送已完成的提交，以 `[WIP]` 标题创建 PR，在报告中说明）：4.4 与 12a 的实际实现不一致；验证在合理排查后仍失败。
