# 任务 04：达人管理与批量导入框架

> 本文件是交给编码代理执行的提示词。直接把本文件全文作为任务输入即可。
> **前置条件：任务 03（管理端、达人端与部署模板，PR #16）已合并到 `main`。** 未合并时停止，不要开始。

---

## 1. 背景

M1 里程碑要演示"录入达人 → 生成专属链接 → 点击跳转并计数"，拆为三个任务：

| 任务 | 内容 |
|---|---|
| **04（本任务）** | 批量任务框架（`task` 模块）+ 达人管理（`kol` 模块）+ 管理端页面 |
| 05 | 店铺、原商品、专属商品对应关系（`product` 模块），复用本任务的导入框架 |
| 06 | 中转链接、Redis 链接索引、点击采集（`link` 模块） |

本任务交付后，运营可以在管理端录入、编辑、启停达人，按 Excel 批量导入达人，商务只能看到和管理自己负责的达人。

**本任务明确不做**（后续任务处理，不要提前实现，也不要预留字段）：

- 达人登录账号、邀请邮件、设置密码（与达人端登录一起做）；因此 `crm_kol` 本次不建 `account_status`、`password_hash` 列；
- 结算冻结（`settle_frozen`、`frozen_reason`，与佣金结算一起做）；
- 达人导出、业务审计表 `crm_biz_audit`、站内消息、任务重试。

## 2. 开始前必读

1. `AGENTS.md`
2. `docs/05-工程规范/` 全部文件，重点：01（模块与分层）、03（接口）、04（数据库）、05（测试与必测清单）、06（鉴权、数据隔离、导入文件）、07（新增依赖）
3. 设计说明书 `docs/03-设计/系统详细设计说明书.md`：§1.3 角色、§3 权限与数据隔离、§4.2 达人入驻、§5.2 `crm_kol` / `crm_kol_bd_history` / `crm_task`、§8.2 编号生成
4. 现有代码：`security.api`（`CurrentUserContext`、`AccountStore`、`Role`、`DataScope`）、`system` 模块（用户管理的写法是本任务的参照）、`shared`（`R`、`PageResult`、`ErrorCode`、`AuditLog`、`BaseEntity`）、`admin-ui/src/views/system/user`

**设计说明书与工程规范冲突时以规范为准**，本任务已知的冲突及处理：

| 设计说明书 | 本任务按规范执行 |
|---|---|
| §6.2 路径 `/admin-api/kol/list`、`/admin-api/kol/{id}` 等单数形式 | 规范 03：`/admin-api/kol/kols` 等复数 kebab-case（见第 5 节） |
| §6.1 `R<T>` 的 `rows`、`total` | 规范 03：信封 + `{items,total,page,size}` |
| §5.2 `TINYINT` | 规范 04 与现有 `sys_user`：状态用 `SMALLINT` + `CHECK` |
| §5.2 `crm_task.error_file_url` | 错误文件存数据库（见第 4 节），不存 URL |

完成后在设计说明书中同步修改上述涉及达人与任务的部分（§5.2 两张达人表与 `crm_task`、§6.2 达人与任务的接口行、§8.2 达人编号），其他模块的描述不动。

## 3. 执行环境与交付

- 执行机不能运行 Docker；`*IT` 集成测试由 CI 和验证机运行，本地运行 `mvn verify`（不带 `-Pdocker-it`）。
- 从最新的 `main` 创建分支 `task/04-kol-and-import`，在该分支提交，按下面的步骤分别提交。
- 完成后推送分支，`gh pr create` 按 PR 模板填写，`gh pr checks --watch` 等待 CI 全部通过；失败则在本分支修复。**不要合并 PR，不要推送 `main`。**
- 提交遵循规范 08（Conventional Commits），末尾加执行代理自己的 `Co-Authored-By` 行。

## 4. 已定的设计决定（不要自行改变）

### 4.1 达人编号

- 格式 `KOL` + 至少 4 位数字（`^KOL\d{4,8}$`，与跳转服务的校验一致），如 `KOL0001`、`KOL12345`。
- 新建时由系统生成：PostgreSQL 序列 `crm_kol_code_seq` 取号，按 4 位补零；若该编号已被占用（见下条），继续取下一个号。
- **Excel 导入时允许填写已有编号**（客户可能已经在用编号），填写的编号须符合格式且未被占用；不填则由系统生成。
- 编号创建后不可修改；唯一约束覆盖已逻辑删除的记录（编号永不复用）。

### 4.2 商务归属

- 商务 = `OPERATOR` 角色 + `data_scope = OWN` 的管理员。
- `crm_kol.bd_user_id` 可为空，含义为"未分配商务"（列注释写明）。
- `OWN` 用户新建或导入的达人，`bd_user_id` 强制为本人，忽略请求或文件中的商务字段。
- `SUPER_ADMIN` 与 `OPERATOR + ALL` 新建、导入、更换商务时，指定的商务必须是启用状态的 `OPERATOR + OWN` 账号，或留空。
- 更换商务（含从空到有、从有到空）时，在一个事务内关闭 `crm_kol_bd_history` 的当前记录（写 `end_time`）并新增一条记录；首次分配同样写一条历史。同一达人任意时刻最多一条 `end_time IS NULL` 的记录（部分唯一索引保证）。
- 商务账号被停用或改为 `ALL` 时不自动处理其达人，本任务只在列表中照常显示。

### 4.3 权限（§3.1，按 `role` + `data_scope` 判定）

| 操作 | SUPER_ADMIN | OPERATOR + ALL | OPERATOR + OWN（商务） | FINANCE |
|---|:-:|:-:|:-:|:-:|
| 列表、详情 | 全部 | 全部 | 仅本人负责 | 全部（只读） |
| 新增、编辑、启停 | ✓ | ✓ | 仅本人负责 | — |
| 更换商务 | ✓ | ✓ | — | — |
| Excel 导入 | ✓ | ✓ | ✓（归属本人） | — |
| 查看任务进度、下载错误文件 | 全部任务 | 本人提交的任务 | 本人提交的任务 | 本人提交的任务 |

- 数据范围在 service 层查询时强制过滤（规范 06 第 2 节），不依赖前端。
- `OWN` 用户按 ID 访问不属于自己的达人：**返回 403**（`AUTH_FORBIDDEN`），不返回 404。
- 每个接口都有越权测试（规范 05 必测清单"权限"一行）。

### 4.4 批量任务框架（`task` 模块）

- 表 `crm_task`：`task_type`（本任务只有 `KOL_IMPORT`，`CHECK` 约束只列已实现的类型，后续任务新增时加迁移）、`status`（`WAITING` / `RUNNING` / `SUCCESS` / `PARTIAL` / `FAILED`）、`total`、`done`、`failed`、`message`（失败原因，英文）、`started_at`、`finished_at` 及通用字段。不建 `params`、`retry_count`、`error_file_url`，用到时再加。
- 表 `crm_task_file`：`task_id`、`kind`（`INPUT` 上传原文件 / `ERROR` 错误文件）、`file_name`、`content_type`、`content BYTEA`、`create_time`。不使用对象存储或本地磁盘，避免新增部署配置。
- 执行：上传请求只做文件级校验（第 4.5 节）并保存原文件，创建 `WAITING` 任务后立即返回 **202** 与任务 ID；后台用有界线程池（核心 2、队列 20）异步执行，按行更新进度（每 100 行或结束时写一次）。队列已满时返回 429（新错误码 `TASK_QUEUE_FULL`）。
- 结束状态：全部成功 `SUCCESS`；部分失败 `PARTIAL`；全部失败或文件无法解析 `FAILED`。有失败行时生成错误文件。
- 应用启动时，把遗留的 `WAITING` / `RUNNING` 任务置为 `FAILED`，`message` 为 `Interrupted by restart`。
- `task` 是基础模块：业务模块通过 `task.api` 提交任务并提供"逐行处理"的实现；`task` 不依赖任何业务模块（ArchUnit 已有规则覆盖，确认通过）。

### 4.5 Excel 导入（规范 06 第 3 节）

- 依赖：**FastExcel**（设计说明书 D5，已批准），使用 Maven Central 上的最新正式稳定版并写死版本；许可证须在规范 07 白名单内，在 PR 中说明版本与许可证。如果最新版不满足规范 07，停下来报告。
- 文件级校验（同步，失败返回 400）：只接受 `.xlsx`；大小 ≤ 20MB（同时配置 Spring multipart 上限）；校验文件头为 ZIP 格式；数据行数 ≤ 2,000（达人设计容量 500）；表头与模板不一致时拒绝。
- 行级校验失败的行写入错误文件：原始各列 + 末尾"错误原因"列（中文，一行可有多个原因，用"；"分隔）。错误文件中以 `=`、`+`、`-`、`@` 开头的单元格加前缀 `'` 转义。
- 每行独立处理、独立事务：成功的行写入，失败的行不影响其他行。文件内重复（同一邮箱或同一编号出现多次）时，第二次及以后出现的行报错。
- 重复导入同一文件：已存在的邮箱、编号按"已存在"报错，不更新已有达人（本任务的导入只新增）。

**达人导入模板**（列名与顺序；列定义集中在一处，M0 确认后可能调整）：

| 列 | 必填 | 规则 |
|---|---|---|
| 达人编号 | 否 | 见 4.1 |
| 姓名 | 是 | 1～64 字符 |
| 邮箱 | 是 | 合法邮箱，≤ 128 字符，存储时转小写；未删除达人中唯一 |
| 国家 | 是 | ISO 3166-1 两位字母代码，大写存储 |
| 负责商务用户名 | 否 | 见 4.2；`OWN` 用户导入时忽略该列 |
| 默认佣金比例(%) | 否 | 0～100，最多 2 位小数，存为 `NUMERIC(6,4)` 小数（15.5 → 0.1550） |
| 社媒平台 | 否 | 1～32 字符；与社媒账号同时填写或同时为空 |
| 社媒账号 | 否 | 1～128 字符 |
| 粉丝数 | 否 | 非负整数；只有填写了社媒账号时才能填写 |
| 备注 | 否 | ≤ 500 字符 |

模板文件由后台按上述定义生成并通过接口下载，不提交静态 xlsx。

## 5. 接口

全部在 `/admin-api` 下，响应遵循规范 03；写操作加 `@AuditLog`；每个接口显式声明角色（规范 06 第 2 节）。

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/kol/kols` | 列表。参数：`keyword`（匹配编号、姓名、邮箱，`ILIKE` 时转义 `%`、`_`、`\`）、`country`、`status`、`bdUserId`（`OWN` 用户忽略）、`page`、`size`；按 `create_time` 倒序、`id` 兜底 |
| GET | `/kol/kols/{id}` | 详情，含商务归属历史 |
| POST | `/kol/kols` | 新增，返回新达人 |
| PUT | `/kol/kols/{id}` | 编辑（编号、商务不在此接口修改） |
| PUT | `/kol/kols/{id}/status` | 启用 / 停用 |
| PUT | `/kol/kols/{id}/bd-user` | 更换商务（可置空） |
| GET | `/kol/kol-imports/template` | 下载导入模板 |
| POST | `/kol/kol-imports` | 上传 Excel（multipart，字段名 `file`），返回 202 + `{taskId}` |
| GET | `/system/bd-users` | 可选商务列表（启用的 `OPERATOR + OWN`，返回 ID、用户名、昵称），供下拉选择；`SUPER_ADMIN`、`OPERATOR + ALL` 可调用 |
| GET | `/task/tasks/{id}` | 任务进度：类型、状态、`total`、`done`、`failed`、`message`、是否有错误文件、时间 |
| GET | `/task/tasks/{id}/error-file` | 下载错误文件（非 JSON 接口，`Content-Disposition: attachment`） |

达人 VO 字段：`id`、`kolCode`、`name`、`email`、`country`、`socialAccounts`（`[{platform, handle, url, followers}]`，最多 10 个；`url` 可空，填写时必须是 `https` 地址）、`bdUserId`、`bdUserName`、`defaultRate`（字符串，4 位小数）、`status`、`remark`、`createTime`、`updateTime`。错误码按 `{模块}_{原因}` 新增（如 `KOL_EMAIL_EXISTS`、`KOL_CODE_EXISTS`、`KOL_INVALID_BD_USER`、`TASK_QUEUE_FULL`、`TASK_FILE_INVALID`），并同步管理端的错误码文案映射。

## 6. 执行步骤

### 第 1 步：数据库迁移

新增迁移脚本（命名见规范 04 第 4 节，不得修改已有脚本）：`crm_kol`、`crm_kol_bd_history`、`crm_kol_code_seq`、`crm_task`、`crm_task_file`。

- 字段类型、命名、注释、约束遵循规范 04；表和每个字段都写注释，枚举列出全部取值。
- 唯一约束：`kol_code`（全表）；`lower(email)`（`WHERE is_deleted = FALSE`）；商务历史"每个达人最多一条当前记录"。
- 为列表查询条件建索引，并在 PR 中说明每个查询使用的索引（规范 04 第 3 节）。

提交：`feat(backend): add kol and batch task tables`

### 第 2 步：批量任务框架（`task` 模块）

按第 4.4、4.5 节实现：任务提交与异步执行、进度、错误文件、启动时清理遗留任务、任务查询与错误文件下载接口（含"仅本人或超管"的访问控制）。Excel 读写的通用部分（文件校验、表头校验、错误文件生成与公式转义）放在本模块。

提交：`feat(backend): add batch task framework with Excel import support`

### 第 3 步：达人管理（`kol` 模块）

按第 4.1～4.3、第 5 节实现达人接口、编号生成、商务归属与历史、数据范围过滤、Excel 导入的逐行处理；`/system/bd-users` 放在 `system` 模块。商务账号的校验、按 ID 批量取商务名称、按用户名查商务（导入用），通过 `system` 模块新增的 `api` 门面完成（返回只含 ID、用户名、昵称、是否为可用商务的 DTO）。不要使用 `security.api.AccountStore`：它是认证边界，带密码哈希。`kol` 不得访问 `system` 的 service、mapper、model。

提交：`feat(backend): add kol management and Excel import`

### 第 4 步：管理端页面

1. 菜单"达人管理"：`SUPER_ADMIN`、`OPERATOR`、`FINANCE` 可见。
2. 列表页：筛选（关键字、国家、状态、商务）、分页（`total` 按字符串接收再转数字）、新增 / 编辑对话框、启停、更换商务、详情抽屉（含商务归属历史）。
3. 按钮按第 4.3 节控制显示：`FINANCE` 只读；`OWN` 用户不显示商务筛选、商务选择与"更换商务"。界面隐藏只是体验，权限以后台为准。
4. 导入对话框：下载模板 → 上传 → 每秒轮询任务进度并显示进度条 → 结束后显示成功 / 失败行数，有错误文件时提供下载，关闭后刷新列表。
5. 佣金比例在界面上按百分比输入和显示，与接口的 4 位小数字符串互相转换（转换函数要有单元测试）。
6. 补充新错误码的中文文案。

提交：`feat(admin-ui): add kol management and import pages`

### 第 5 步：测试

按规范 05 编写，覆盖率不得低于门槛：

- 单元测试：编号生成（补零、扩位、跳过已占用）、各字段校验（正常 / 边界 / 非法）、比例换算、导入逐行处理（成功、每类非法数据、文件内重复、已存在）、错误文件公式转义、任务状态判定（SUCCESS / PARTIAL / FAILED）。
- 接口切片测试：第 4.3 节权限表的每一格，包括 `OWN` 用户访问他人达人返回 403、`FINANCE` 写操作返回 403、`OWN` 用户请求中带的 `bdUserId` 被忽略；任务与错误文件只能由提交人或超管访问。
- 集成测试（`*IT`，`@Tag("docker")`）：迁移脚本在 PostgreSQL 上执行；并发新建达人时编号不重复；商务更换后历史记录正确且只有一条当前记录；上传真实 xlsx 完成一次部分成功的导入，并下载错误文件核对内容。
- 前端（Vitest）：按角色显示按钮、导入轮询直到结束、`total` 字符串转换、比例换算。

### 第 6 步：文档与报告

1. 设计说明书：按第 2 节同步修改。
2. `docs/04-开发任务/README.md`：任务 04 行改为实际状态。
3. 执行报告 `docs/04-开发任务/04-达人管理与批量导入-执行报告.md`：结论；每步改动与验证结果；新增依赖（版本、许可证、理由）；每个列表查询使用的索引；CI 结果与 PR 链接；偏离与遗留事项；需要 M0 确认的事项（模板列、商务是否必填、是否已有达人编号等）。

## 7. 禁止事项

- 不实现第 1 节"明确不做"中的内容。
- 不修改 `docs/05-工程规范/`；对规范有异议时写进报告。
- 不修改已合并的迁移脚本；不降低覆盖率门槛，不放宽 ArchUnit 规则，不 `@Disabled` 测试。
- 不修改 `redirect/`、`kol-portal/`、`deploy/`。
- 不推送 `main`，不合并 PR，不 `push --force`，不改写已推送历史。

## 8. 停止条件

出现以下情况时停止，推送已完成的提交，以 `[WIP]` 标题创建 PR，并在报告中说明：

- FastExcel 没有满足规范 07 的稳定版本，或需要引入其他未批准的依赖；
- 第 4 节的设计决定与现有代码或规范冲突，无法按原样实现；
- 需要修改已合并的迁移脚本或 `security` 模块的认证逻辑；
- CI 在合理排查后仍无法通过。
