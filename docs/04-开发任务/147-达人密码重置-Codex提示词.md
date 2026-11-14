# 任务 147：达人密码重置（超管专属）

> **执行环境**：本机。
> - 工作树由执行代理按负责人 2026-11-11 会话指示创建：`/Users/hh-mini/Public/dev/t-crm/.worktrees/147-kol-password-reset`，分支 `task/147-kol-password-reset`，从最新 `main`（`62db5904`）检出。
> - 端口：后端 `TCRM_ADMIN_PORT=18147`，管理端 Vite `8847`。不改达人端、跳转服务。
> - 连接共享中间件：`deploy/.env.local` 只读，工作树里用 `TCRM_LOCAL_ENV_FILE` 指向主目录的这个文件（外部模式，任务 109）。测试数据前缀 `t147_`。
> - 本机后端可以设 `CRM_AUTH_CAPTCHA_ENABLED=false`（只限本地开发）。
> - 不得重建负责人的预览 `tcrm-preview`，不得碰隧道服务 `com.tcrm.aliyun-tunnel`。
> - **无数据库迁移、不新增依赖。**
>
> **必读**（通用阅读范围见 [`本机任务通用要求.md`](本机任务通用要求.md)，全文必读）：
> - 本文第 1 节（负责人 2026-11-11 需求原文）是本任务唯一需求依据；进度跟踪表 04.01（P0 第 13 行）未规定该功能，属「基线未规定」新增口径，实现后登记进 [`进度表基线偏离清单.md`](进度表基线偏离清单.md)。
> - 设计说明书 §4.11（达人管理接口表、权限清单）、§3.4 凭据版本机制；ADR-018（接口权限声明）；ADR-038（版本拦截，`KolController` 未标注 `@EditionScope`，仅分销版可达，符合预期）。
> - 规范 03 第 1～7 节、规范 06 第 2 节、规范 05 第 2、3 节；规范 10/11 弹窗小节；skill `$browser-acceptance`。

## 1. 需求（负责人 2026-11-11 原话）

> 当前有新的需求，在达人列表页-》基础信息子页面中，增加一个重置达人密码的功能，点击弹窗让用户二次确认后，将达人的密码重置为符合当前系统密码规范的一串随机字符串。该功能权限为超级管理员可见。

## 2. 要求

### 2.1 后端（`kol` 模块）
1. 新接口 `POST /admin-api/kol/kols/{id}/password-reset`：
   - `@SaCheckRole("SUPER_ADMIN")` + `@AuditLog(module = "KOL", action = "Reset password")`；返回 `R<ResetKolPasswordVO>`（新 Controller VO，含一次性明文 `password`）。
   - 服务层生成随机密码（16 位，`SecureRandom`，字母数字去易混淆字符 `0 1 O I L l i o`，满足密码规范 10～72 位可打印 ASCII），BCrypt 入库，`credential_version` 加 1，`StpKolUtil.logout` 注销达人全部会话，作废该达人未使用的重置令牌（`KolAuthTokens.invalidateUnused`，`PURPOSE_RESET`）。
   - 更新走 `scoped(id, user)` + `requireUpdated`（复用 `KolService` 现行写法）：公司内不存在 404，达人未激活（`account_status = 0`）返回 422 `KOL_NOT_ACTIVATED`（登记进 `shared.ErrorCode`）。
   - 明文只在本次响应返回；操作日志、应用日志不得出现明文。
2. 单元测试：随机密码生成器（长度、字符集、不重复性）；服务层成功路径（哈希可验证、凭据版本、作废令牌、登出）、未激活 422、404；Controller 超管 200、子账号 403。

### 2.2 管理端（`admin-ui`）
1. `KolProfileDialog`（行操作「基础资料」弹窗）邮箱行增加「重置密码」按钮（danger link）：仅**超级管理员、非审核模式、达人已激活**时可见。
2. 点击后 `ElMessageBox` 二次确认（说明：重置后该达人当前登录立即失效、新密码仅显示一次），确认后调用接口，成功后弹窗内一次性展示新密码并提供复制（`CopyText`）；失败走统一错误提示。
3. 文案全部进语言包（中英文对称）。

### 2.3 文档
1. 设计说明书：修订记录追加 2026-11-11 条目；达人管理接口表与权限清单各加一行。
2. `进度表基线偏离清单.md`：登记「基线未规定」条目（基线位置：`功能评估-P0` 第 13 行 E 列 04.01；出处：负责人 2026-11-11 需求）。
3. 执行报告 `147-达人密码重置-执行报告.md`：含逐条复核表、检查结果、浏览器验收截图清单、收尾清理复查。

## 3. 范围外

- 不发送任何邮件或站内通知；不提供管理员自定义新密码输入；不改达人端自助找回流程；不新增权限码（超管角色专属，与 `PUT /{id}/bd-user` 一致）。
