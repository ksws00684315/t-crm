# 任务 06c（后端）：多公司底座

> 本文件是交给编码代理执行的提示词。直接把本文件全文作为任务输入即可。
> **前置条件：PR #26（多公司与中英文切换设计）已合并到 `main`。** 未合并时停止，不要开始。
>
> **并行说明**：任务 06c 拆成两份同时执行。本文件只负责后端（`backend/`）及相关文档；管理端由另一个代理在另一个工作树中按《06c-多公司底座-管理端》实现，它依赖第 4.8 节的接口约定。**接口的路径、字段名、类型和错误码必须与第 4.8 节完全一致，不得自行调整。**另有代理同时在改 `kol-portal/`。

---

## 1. 背景

签约时系统按一家公司设计。2026-10-11 客户提出：系统要支持客户旗下的多家公司，各公司数据完全隔离；同一个达人可能与多家公司合作，在每家公司各有一个账号。客户目前只有 1 家公司，要求以后增加公司时**系统不需要重构**。

设计已定稿：设计说明书 §3.4、ADR-014，规范 03、04、05、06 中标注"任务 06c 起执行"的条款。本任务把已完成的 M1 后端改造成多公司结构，并提供平台管理员的公司管理接口。**不增加其他业务功能。**

本任务完成后应达到：

1. 所有业务数据带所属公司，查询和修改自动限定在当前公司，取不到当前公司时报错；
2. 平台管理员能通过接口新建公司、为公司创建超级管理员，新公司的管理员登录后只能看到本公司的数据；
3. 现有数据全部归入默认公司，现有功能（含跳转与点击计数）行为不变。

## 2. 开始前必读

1. `AGENTS.md`、`docs/05-工程规范/` 全部文件（注意标注"任务 06c 起执行"的条款）
2. 设计说明书 §1.3、§2.2、§2.3、§3（全部，重点 §3.4）、§5.2、§6.2、§9、§11；ADR-014；ADR-008、ADR-013
3. 现有代码：
   - `security` 模块全部（`CurrentUserContext`、`AccountStore`、`AuthService`、`Role`）；
   - `system` 模块全部（`AccountStoreService`、`UserService`、`BootstrapAdmin`、`AuditAspect`、`AuditWriter`、`OperLogRetentionJob`）；
   - `shared.CommonConfiguration`、`shared.BaseEntity`；
   - 全部 `*Mapper.java` 中的手写 SQL；
   - 所有定时任务、`@Async` 方法与后台线程：`task.service.TaskQueue`、`ImportTaskService`、`InterruptedTaskCleaner`、`TaskFileRetentionJob`，`link.service` 的 `LinkIndexSyncJob`、`LinkRebuildService`、`ClickConsumer`、`ClickConsumerRunner`、`ClickPartitionJob`、各 `*Monitor`；
   - 集成测试 `KolIT`、`ProductIT`、`LinkIT`、`SystemIT` 的初始化与登录方式；`ArchitectureTest`。

## 3. 执行环境与交付

- 你在一个独立的 git 工作树中工作，分支 `task/06c-backend` 已基于最新的 `main` 检出，直接在此分支上提交。按第 5 节的步骤分别提交。
- **只修改本任务范围内的文件**：`backend/`、`docs/03-设计/`、`deploy/README.md`、`.env.example`、你的执行报告。**不要修改** `admin-ui/`、`kol-portal/`、`redirect/` 和 `docs/04-开发任务/README.md`（任务索引由项目负责人维护）。
- 迁移脚本版本号：创建 PR 前先合并最新的 `main`。如果 `main` 上已有版本号更大的迁移，就把你**尚未合并**的脚本改名为更晚的时间戳，否则已部署的环境会校验失败。
- 能运行 Docker 时，本地运行集成测试：在 `backend/` 下执行 `mvn -B verify -Pdocker-it`；不能运行时，`*IT` 由 CI 运行。
- 完成后推送分支，用 `gh pr create` 按 PR 模板创建 PR，并用 `gh pr checks --watch` 等待 CI 全部通过。**不要合并 PR，不要推送 `main`。**
- 提交遵循规范 08，末尾加执行代理自己的 `Co-Authored-By` 行。
- 规范 05：每条隔离规则先写测试，确认在修改前失败（例如：修改前，A 公司管理员能查到 B 公司的达人）。

## 4. 已定的设计决定（不要自行改变）

### 4.1 数据库迁移

新增迁移脚本，脚本名按规范 04，时间戳晚于 `V202610071400`。**已合并的脚本一律不改。**

**① 新建 `sys_tenant` 公司表**（设计说明书 §5.2）

| 字段 | 定义 |
|---|---|
| `tenant_code` | `VARCHAR(32) NOT NULL`，满足 `CHECK` `^[a-z0-9-]{2,32}$`；全表唯一 `uk_sys_tenant_tenant_code`；创建后不可修改 |
| `name` | `VARCHAR(64) NOT NULL` |
| `status` | `SMALLINT NOT NULL DEFAULT 1`，取值 1 启用 / 0 停用（`CHECK`） |
| `remark` | `VARCHAR(500) NULL` |
| 通用字段 | 按规范 04 的必备字段，含 `is_deleted`。本表自身没有 `tenant_id`；不提供删除功能 |

- 插入默认公司：`id = 1`，`tenant_code = 'default'`，`name = '默认公司'`，`status = 1`。
- 表和每个字段都写注释。

**② 给业务表加 `tenant_id`**

| 表 | `tenant_id` |
|---|---|
| `crm_kol`、`crm_kol_bd_history`、`crm_shop`、`crm_product`、`crm_kol_item`、`crm_task`、`crm_task_file`、`crm_click_log`（分区父表） | `BIGINT NOT NULL`。做法：先 `ADD COLUMN ... NOT NULL DEFAULT 1`，再 `ALTER COLUMN ... DROP DEFAULT`。PostgreSQL 17 中带常量默认值加列只改元数据，不重写表 |
| `sys_user` | `BIGINT NULL`。现有行全部设为 1；新增 `CHECK ck_sys_user_tenant ((role = 'PLATFORM_ADMIN') = (tenant_id IS NULL))` |
| `sys_oper_log` | `BIGINT NULL`。现有行全部设为 1（迁移时只有一家公司）；空值只表示平台级记录 |
| `crm_link_index_outbox` | **不加**（系统内部队列，设计说明书 §3.4 第 1 条） |

- 每个 `tenant_id` 字段都写注释："所属公司（`sys_tenant.id`）"；可为空的字段要写明空值的含义。
- 不加外键（规范 04）。

**③ 约束与索引**

| 对象 | 调整 |
|---|---|
| `sys_user.role` | `ck_sys_user_role` 增加 `PLATFORM_ADMIN` |
| `uk_crm_kol_email` | 改为 `(tenant_id, lower(email)) WHERE is_deleted = FALSE` |
| `idx_crm_kol_create_time` | 改为 `(tenant_id, create_time, id) WHERE is_deleted = FALSE` |
| `idx_crm_kol_country` | 改为 `(tenant_id, country) WHERE is_deleted = FALSE` |
| `uk_crm_product_product_code` | 改为 `(tenant_id, product_code)`，仍覆盖已删除的记录 |
| `idx_crm_product_status` | 改为 `(tenant_id, status, product_code) WHERE is_deleted = FALSE` |
| `idx_crm_product_category` | 改为 `(tenant_id, category, product_code) WHERE is_deleted = FALSE` |
| `sys_oper_log` | 新增 `idx_sys_oper_log_tenant_time (tenant_id, oper_time)`；原 `idx_sys_oper_log_time` 保留，清理任务跨公司使用 |
| 不变 | 以下标识保持全系统唯一：`uk_crm_kol_kol_code`、`uk_crm_shop_weidian_shop_id`、`uk_sys_user_username`。以下索引以达人、店铺、原商品 ID 开头，不改：`idx_crm_kol_bd_user`、`crm_kol_bd_history` 的索引、`crm_kol_item` 全部索引、`idx_crm_product_shop`、`crm_click_log` 索引、`crm_task` / `crm_task_file` 索引 |

- 同步更新表注释。`crm_shop` 的表注释改为"每家公司最多 3 个"；`crm_product.product_code` 的字段注释改为"公司内唯一"。
- 被改动的表都很小，`crm_kol_item` 与 `crm_click_log` 只加列、不建索引。PR 中说明锁表风险评估。

### 4.2 公司范围与自动过滤

**① 当前公司的来源**，按以下顺序确定：

1. 显式设置的范围：
   - **公司范围**：在指定公司内执行，例如批量导入任务按任务行的 `tenant_id` 执行；
   - **系统范围**：跨公司执行，不加过滤。
2. 否则，取已登录管理员账号的 `tenant_id`。
3. 都取不到时**抛出异常**，不执行这条 SQL。

平台管理员没有所属公司，因此平台管理员调用任何业务数据访问都会失败。业务接口本来就拒绝平台管理员（第 4.4 节），这是第二道防线。

**② 实现方式**

- 使用 MyBatis-Plus 的 `TenantLineInnerInterceptor`，所需依赖 `mybatis-plus-jsqlparser` 已在项目中，**不新增依赖**。它必须排在 `PaginationInnerInterceptor` 之前。
- 忽略表：`sys_tenant`、`crm_link_index_outbox`。
- 系统范围内，所有表都忽略过滤（`ignoreTable` 返回 true）。这时 INSERT 不会自动填 `tenant_id`，代码必须显式赋值；漏赋值时由 `NOT NULL` 约束报错。
- 显式范围的 API 由你设计，要求：
  - 用 `try/finally` 恢复进入前的状态，可以嵌套；
  - 系统范围必须传入一个说明原因的字符串（写入 `DEBUG` 日志即可）；
  - 不得把范围泄漏到线程池中的下一个任务。
- 依赖方向必须满足 `ArchitectureTest`：`shared` 不依赖其他模块。建议把范围持有类和"当前公司解析接口"放在 `shared`，由 `security` 提供基于登录态的实现。

**③ 必须在系统范围执行的位置**（逐一处理，PR 中列表说明）：

| 位置 | 原因 |
|---|---|
| `AccountStore` 的全部查询 | 登录前还不知道公司；按 ID 重读账号时，公司本身就要从这次查询中得到。不放在系统范围会递归 |
| `BootstrapAdmin` | 启动阶段，没有登录态 |
| `AuditWriter`（`@Async`） | 异步线程没有登录态。`tenant_id` 由 `AuditAspect` 在请求线程中确定后随记录传入：取操作人账号的公司；登录请求取所填用户名对应账号的公司；都没有时为空 |
| `OperLogRetentionJob`、`TaskFileRetentionJob`、`InterruptedTaskCleaner`、`ClickPartitionJob` | 全表维护 |
| `LinkIndexSyncJob`、`LinkRebuildService`（含手动触发的重建） | 按 ID 处理全部公司的链接索引；`link:*` 多余 key 的清理也要跨公司 |
| `ClickConsumer` | 按 `kolItemId` 查对应关系。写入点击时 `tenant_id` 取对应关系的 `tenant_id` |
| `*Monitor` | 只读统计，或只访问 Redis |
| 全系统唯一性检查：达人编号（`KolMapper.existsCode`）、微店店铺 ID、管理员用户名 | 唯一索引是全局的，检查也必须是全局的；否则先查本公司显示"不存在"，插入时又被唯一索引拒绝 |

- `ImportTaskService` 在队列线程中执行任务时，先进入该任务行 `tenant_id` 对应的公司范围（与设计说明书 §3.3"按提交时身份执行"一致）。
- 上表以外的位置一律走自动过滤。如果你认为还有别的位置必须用系统范围，在 PR 中说明理由。

**④ 手写 SQL 逐条核对**：全部 `@Select` / `@Update` / `@Insert` / `@Delete` 都会经过插件解析。

- 要确认插件改写后的 SQL 语义正确，并有测试覆盖。需要特别核对的写法：`JOIN` 后每张表都加了条件、`FOR UPDATE`、`COUNT(*) FILTER (...)`、`ON CONFLICT`、`FROM pg_advisory_xact_lock(...)`、`<foreach>` 生成的 `OR` 和 `IN`、`nextval`。
- 插件无法解析的语句（如分区 DDL、`COMMENT ON`），只能放在系统范围执行，或用 `@InterceptorIgnore(tenantLine = "true")` 标注，并写明原因。**不得为通过测试而整体关闭插件。**

**⑤ 店铺上限按公司计算**：`ShopMapper.lockForCreate` 的咨询锁键加上公司 ID（例如 `hashtextextended('crm_shop:create:' || 公司ID, 0)`，公司 ID 用参数绑定）。`countAlive` 由自动过滤限定在本公司。

**⑥ 实体**

- 带 `tenant_id` 的实体能读到 `tenantId`，供系统范围内显式赋值使用。
- 任何 UPDATE 都**不得写 `tenant_id`**（例如 `@TableField(updateStrategy = FieldStrategy.NEVER)`）。
- 请求对象、命令对象中不出现公司 ID。

### 4.3 登录、会话与公司状态

1. `AccountDTO` 增加 `tenantId` 与"公司是否启用"。`AccountStoreService` 在系统范围内读取账号及其公司的状态（可以用一条关联查询，也可以分两次查询）。
2. `CurrentUserContext.user()`：公司停用（或公司不存在）时，与账号停用一样处理：注销该账号，抛出 `AUTH_ACCOUNT_DISABLED`（401）。`roleNames` 在公司停用时返回空列表。
3. `AuthService.login`：公司停用时，与账号停用、密码错误返回**完全相同**的失败（`AUTH_INVALID_CREDENTIALS`），也执行一次 BCrypt，并计入失败次数（规范 06 第 1 节）。
4. 登录返回的 `user` 与 `GET /admin-api/auth/me` 增加 `tenantId`、`tenantName`，平台管理员为 `null`（第 4.8 节）。
5. 令牌、会话、凭据版本的机制不变（设计说明书 §3.3）。

### 4.4 平台管理员

1. `Role` 增加 `PLATFORM_ADMIN`。它的 `data_scope` 固定为 `ALL`，`tenant_id` 为空。
2. **所有业务接口都不允许平台管理员访问。** 平台管理员只能访问：
   - `/admin-api/platform/**`；
   - `/admin-api/auth/logout`、`/admin-api/auth/me`、`/admin-api/auth/password`。

   核对每个 `@SaCheckRole`，确保 `PLATFORM_ADMIN` 只出现在上述接口上。
3. **初始化**：`BootstrapAdmin` 改为检查是否存在启用的平台管理员。没有时，用原有变量 `TCRM_BOOTSTRAP_ADMIN_USERNAME` / `TCRM_BOOTSTRAP_ADMIN_PASSWORD` 创建平台管理员，校验规则不变。
   - 如果该用户名已被其他账号占用（例如已有环境里，同名用户已迁移为默认公司的超管），启动失败，并明确提示"该用户名已被占用，请为平台管理员设置另一个 `TCRM_BOOTSTRAP_ADMIN_USERNAME`"。
   - 已存在启用的平台管理员时，不要求设置这两个变量。
4. **平台接口**（设计说明书 §6.2；字段与类型以第 4.8 节为准），类上标注 `@SaCheckRole("PLATFORM_ADMIN")`，每个写操作加 `@AuditLog(module = "Platform", ...)`：

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/admin-api/platform/tenants` | 公司列表，不分页，按创建时间排序 |
| POST | `/admin-api/platform/tenants` | 新建公司：编码、名称、备注。编码重复返回新错误码 `TENANT_CODE_EXISTS`（409） |
| PUT | `/admin-api/platform/tenants/{id}` | 修改名称、备注；编码不可改 |
| PUT | `/admin-api/platform/tenants/{id}/status` | 启用 / 停用。停用后，该公司账号的下一次请求即失效（第 4.3 节） |
| GET | `/admin-api/platform/tenants/{id}/admins` | 该公司角色为 `SUPER_ADMIN` 的账号：用户名、昵称、状态、最近登录时间 |
| POST | `/admin-api/platform/tenants/{id}/admins` | 为该公司创建超级管理员。字段与校验沿用现有的新建用户，只是去掉角色和数据范围（角色固定 `SUPER_ADMIN`，范围固定 `ALL`）。用户名全系统唯一，冲突返回 `USER_USERNAME_EXISTS` |
| PUT | `/admin-api/platform/tenants/{id}/admins/{userId}/password` | 重置该公司超级管理员的密码。`userId` 不属于该公司，或不是 `SUPER_ADMIN` 时，返回 404。凭据版本加 1，并注销该账号（与现有重置密码相同） |

   - 公司不存在时返回 404。
   - 平台接口内对 `sys_user` 的读写在系统范围执行，并**显式**带上路径中的公司 ID 作为条件或写入值。
   - 本任务不提供管理其他平台管理员的接口，也不提供删除公司的接口。
5. **公司内的用户管理**（`/admin-api/system/users`）：
   - 列表、修改自动限定在本公司；
   - 新建或修改用户时，角色不得为 `PLATFORM_ADMIN`，违反时返回参数校验失败；
   - "至少保留一个启用的超管"按公司判断：`lockEnabledSuperAdmins` 经自动过滤后只锁本公司。用测试证明：B 公司有超管时，A 公司仍不能停用自己唯一的超管。

### 4.5 唯一性与业务规则

| 规则 | 实现 |
|---|---|
| 达人邮箱公司内唯一 | 同一邮箱可以在 A、B 两家公司各建一个达人。应用层的重复检查经自动过滤后自然只查本公司 |
| 原商品编码公司内唯一 | `ProductMapper.existsCode` 改为只查本公司，即自动过滤（仍覆盖已删除记录）。系统生成的编码仍来自全局序列 |
| 达人编号、微店店铺 ID、用户名全系统唯一 | 检查在系统范围执行（第 4.2 节 ③）。与其他公司冲突时，返回与本公司内冲突相同的错误码 |
| 每家公司最多 3 个店铺 | 第 4.2 节 ⑤ |
| 跨公司按 ID 访问 | 返回 404，与"不存在"相同。不得返回 403，否则会暴露 ID 存在 |
| 跳转与点击 | 跳转服务、Redis 链接索引的 key 和 value 格式、点击事件格式**都不变**；`redirect/` 工程不改 |

### 4.6 管理端

由并行任务《06c-多公司底座-管理端》实现，本任务不改 `admin-ui/`。新增的错误码 `TENANT_CODE_EXISTS` 只在后端登记，前端映射由管理端任务添加。

### 4.7 不在本任务范围

| 事项 | 归属 |
|---|---|
| 达人端登录，包括多公司的公司选择（设计说明书 §3.4"达人端登录"） | 达人端登录任务；本任务只保证数据模型支持它（邮箱公司内唯一） |
| 公司级配置与凭证加密（`crm_config`、凭证表、`TCRM_SECRET_KEY`） | 第一个需要保存凭证的任务（Google 表格或微店同步） |
| 中英文切换 | 任务 06d |
| 数据库行级安全（RLS）、跨公司汇总、按公司区分品牌、删除公司 | 不做（ADR-014） |
| 链接索引手动重建的权限 | 不变：仍由任一公司的超管触发，重建范围为全系统；重建只修复索引、不返回数据 |

### 4.8 接口约定（与管理端任务共用，不得改动）

响应使用统一信封；ID 为字符串，时间为 ISO 8601 UTC 字符串，`status` 为数字（规范 03）。

**① 当前用户**

登录返回的 `user`，以及 `GET /admin-api/auth/me` 的 `UserVO`，增加以下字段；其余字段不变。

| 字段 | 类型 | 说明 |
|---|---|---|
| `tenantId` | string \| null | 所属公司 ID；平台管理员为 `null` |
| `tenantName` | string \| null | 所属公司名称；平台管理员为 `null` |

`role` 的取值增加 `"PLATFORM_ADMIN"`。

**② `TenantVO`**

| 字段 | 类型 |
|---|---|
| `id` | string |
| `tenantCode` | string |
| `name` | string |
| `status` | 0 \| 1 |
| `remark` | string \| null |
| `createTime` | string |

**③ `TenantAdminVO`**

| 字段 | 类型 |
|---|---|
| `id` | string |
| `username` | string |
| `nickname` | string |
| `email` | string \| null |
| `status` | 0 \| 1 |
| `lastLoginTime` | string \| null |
| `createTime` | string |

**④ 平台接口**（只允许 `PLATFORM_ADMIN`）

| 方法与路径 | 请求体 | 返回 `data` |
|---|---|---|
| `GET /admin-api/platform/tenants` | — | `TenantVO[]`，按 `createTime` 升序，不分页 |
| `POST /admin-api/platform/tenants` | `{ tenantCode, name, remark? }` | `TenantVO` |
| `PUT /admin-api/platform/tenants/{id}` | `{ name, remark? }` | `null` |
| `PUT /admin-api/platform/tenants/{id}/status` | `{ status }` | `null` |
| `GET /admin-api/platform/tenants/{id}/admins` | — | `TenantAdminVO[]`，按 `createTime` 升序 |
| `POST /admin-api/platform/tenants/{id}/admins` | `{ username, password, nickname, email? }` | `TenantAdminVO` |
| `PUT /admin-api/platform/tenants/{id}/admins/{userId}/password` | `{ password }` | `null` |

**⑤ 校验规则**（前后端一致）

| 字段 | 规则 |
|---|---|
| `tenantCode` | `^[a-z0-9-]{2,32}$` |
| `name`、`nickname` | 非空白，最多 64 个字符 |
| `remark` | 最多 500 个字符，可为 `null` |
| `status` | 0 或 1 |
| `username` | `^[A-Za-z0-9_.-]{3,64}$` |
| `password` | 10～72 个可打印 ASCII 字符 |
| `email` | 邮箱格式，最多 254 个字符，可为 `null` |

这些规则沿用现有的 `CreateUserRequest` 与 `ResetPasswordRequest`。

**⑥ 错误码**

| HTTP | 错误码 | 含义 |
|---|---|---|
| 400 | `COMMON_VALIDATION_FAILED` | 参数校验失败，带字段级列表 |
| 403 | `AUTH_FORBIDDEN` | 非平台管理员调用平台接口；平台管理员调用业务接口 |
| 404 | `COMMON_NOT_FOUND` | 公司不存在；`userId` 不属于该公司，或不是超管 |
| 409 | `TENANT_CODE_EXISTS` | 公司编码重复（新增） |
| 409 | `USER_USERNAME_EXISTS` | 用户名重复 |

公司内的用户管理接口（`/admin-api/system/users`）收到 `role = "PLATFORM_ADMIN"` 时，返回 `COMMON_VALIDATION_FAILED`，字段为 `role`。

## 5. 执行步骤

每步一个或多个提交；每步结束时 `mvn verify` 通过。

1. **迁移**：第 4.1 节。加集成测试：查询 `information_schema`，断言除例外表外，每张业务表都有 `tenant_id`，可空性符合第 4.1 节 ②。分区子表可以按父表判断。
2. **公司范围与自动过滤**：第 4.2 节 ①、②。单元测试覆盖：
   - 未设置范围且没有登录态时抛异常；
   - 嵌套范围的恢复；
   - 线程池任务之间范围不泄漏。
3. **系统范围逐处改造与手写 SQL 核对**：第 4.2 节 ③、④、⑤、⑥。
4. **登录、会话与公司状态**：第 4.3 节。
5. **平台管理员与平台接口**：第 4.4 节，含 `BootstrapAdmin` 的单元测试（创建、已存在、变量缺失、用户名被占用）。
6. **集成测试改造与隔离测试**：第 6 节。
   - 现有 IT 原来用初始化账号直接作为超管登录。现在初始化账号是平台管理员，请提取共用的测试辅助方法：用平台管理员为默认公司创建超管，再用这个超管登录；
   - 再提供"创建第二家公司及其超管"的辅助方法。
7. **文档与执行报告**：第 7 节。

## 6. 测试要求

在规范 05 的基础上，至少包含以下测试。

**公司隔离**（集成测试，建 A、B 两家公司，各有超管；B 公司准备达人、店铺、原商品、对应关系，以及一个导入任务）：

| 场景 | 期望 |
|---|---|
| A 公司超管查列表：达人、原商品、店铺、对应关系、用户、操作日志 | 都看不到 B 公司的数据 |
| A 公司超管用 B 公司的 ID 调用 GET / PUT / 状态修改：达人、原商品、店铺、用户、任务、任务错误文件 | 404 |
| A 公司超管用 B 公司的达人编号查链接列表 | 404 |
| A 公司导入的对应关系引用 B 公司的达人编号或原商品编码 | 该行失败，提示达人或原商品不存在 |
| 同一邮箱在 A、B 各建一个达人；同一原商品编码在 A、B 各建一个原商品 | 都成功 |
| B 公司填写 A 公司已用的达人编号，或新建店铺时使用 A 公司已用的微店店铺 ID | 与本公司内冲突相同的错误码 |
| A 公司已有 3 个店铺时，B 公司新建店铺 | 成功 |
| B 公司提交导入任务 | 任务在 B 公司范围内执行，写入的行 `tenant_id` 为 B |
| A、B 各有一条对应关系，经过 outbox 同步与一次全量重建 | 两家公司的链接都能跳转；点击消费写入的 `tenant_id` 分别正确 |
| 平台管理员 | 遍历所有业务接口都返回 403。用 `RequestMappingHandlerMapping` 枚举接口，不要手写列表，这样以后新增的接口也会被覆盖 |
| 公司超管访问 `/admin-api/platform/**` | 403 |
| 停用 B 公司 | B 公司超管的下一次请求返回 401；B 公司超管登录失败，响应与密码错误相同；B 公司的中转链接仍能跳转。重新启用后可以登录 |
| A 公司唯一的超管 | 即使 B 公司有启用的超管，也不能停用或降级 |
| 平台管理员为 B 公司重置超管密码 | B 超管的旧令牌失效；用 A 公司账号的 `userId` 调用返回 404 |

**单元测试**：

- 公司范围（第 5 节步骤 2）；
- `CurrentUserContext` 的公司停用分支；
- `AuthService` 的公司停用分支；
- `BootstrapAdmin`；
- `AuditAspect` 的 `tenant_id` 归属；
- 平台接口的参数校验。

**回归**：现有 `KolIT`、`ProductIT`、`LinkIT`、`SystemIT`、`RedirectIT` 和全部单元测试通过；覆盖率门槛不降低。

## 7. 文档与报告

1. **设计说明书**：
   - §2.2"当前接口"表补上平台接口；初始化说明改为现状（不再写"任务 06c 起"）；
   - §3.4"达人端登录"小节标注"由达人端登录任务实现，任务 06c 只保证数据模型"；
   - §5.2 各表的索引与约束改为实际结果；
   - 删除本任务已落实条款中的"任务 06c 起 / 改为"等过渡措辞。
2. **`deploy/README.md` 与 `.env.example`**：
   - 说明初始化变量现在创建的是平台管理员；
   - 首次部署流程：平台管理员登录 → 为默认公司创建超管；
   - 已有环境的升级注意事项：初始化用户名不能与已有用户同名。
3. **执行报告** `docs/04-开发任务/06c-多公司底座-后端-执行报告.md`：
   - 每条设计决定的落实位置；
   - 系统范围的全部位置及理由（第 4.2 节 ③ 的完整清单）；
   - 手写 SQL 的核对结果（每条语句改写后的形态，或不经插件处理的理由）；
   - 迁移的锁表评估；
   - 测试清单与结果；
   - 偏离与遗留事项。

## 8. 禁止事项与停止条件

**禁止**：

- 修改已合并的迁移脚本；
- 降低覆盖率门槛，放宽 ArchUnit 规则，`@Disabled` 或弱化测试；
- 整体关闭租户插件，或用"取不到公司就不过滤"的方式让测试通过；
- 新增依赖；
- 修改 `redirect/`、`admin-ui/`、`kol-portal/` 工程，或任务索引 `docs/04-开发任务/README.md`；
- 推送 `main` 或合并 PR；使用 `push --force`、`reset --hard`。

**遇到以下情况停下来**：推送已完成的提交，以 `[WIP]` 标题创建 PR，在执行报告中说明后结束。

- 插件对某条 SQL 的改写结果不正确，且改写 SQL 会改变业务语义；
- 插件与分页、乐观锁或其他插件冲突，无法在不新增依赖的前提下解决；
- 设计说明书、本提示词、规范之间存在冲突；
- 验证在合理排查后仍然失败。
