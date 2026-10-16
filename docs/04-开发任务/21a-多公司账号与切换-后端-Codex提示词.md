# 任务 21a（后端）：一个管理员账号属于多家公司，登录后切换当前公司

> 本文件是交给编码代理执行的提示词，把全文作为任务输入即可。
>
> **前置与并行**：
> - **在 16b、20 合并之后开始**。这两个任务也会改用户停用、超管列表与 `system.api`，同时进行会大量冲突。
> - 与 21b（管理端）同时进行，按第 4.8 节的接口约定对接。
> - 迁移脚本版本号固定为 `V202610172300`。

---

## 1. 背景

现状（设计 §3.4、[ADR-014](../06-架构决策/ADR-014-多公司采用共享库与公司字段隔离.md)、[ADR-018](../06-架构决策/ADR-018-管理端改为超级管理员加可配置权限的子账号.md)）：

- 管理员账号只属于一家公司（`sys_user.tenant_id`），用户名全系统唯一；
- 同一个人为多家公司工作时，在每家各开一个账号；
- 界面上不能切换公司。

负责人 2026-10-16 决定做「多公司账号与切换」。调研见 [多租户切换方案调研](../02-调研/多租户切换方案调研.md)，采用其中的 **P2 模式**：

- 账号与公司之间建成员关系，角色与权限按公司分别授予；
- 当前公司保存在服务端的登录会话里，切换由服务端校验后更新；
- 不采用「前端在请求头里自报公司 ID」的做法。

本任务只改管理端的账号与会话。**达人端、平台管理员的业务隔离都不变**。

## 2. 开始前必读

1. `AGENTS.md`、`docs/05-工程规范/`（重点：01、03、04、06）。
2. 设计说明书：
   - §2.2；
   - §3.1 功能权限；
   - §3.2 数据隔离；
   - §3.3 会话与令牌；
   - §3.4 多公司（全节）；
   - §4.13 第 5 条（商务停用）；
   - §5.2 `sys_user`、`sys_user_permission`；
   - §6.2 认证、用户、平台接口。
3. ADR-014、ADR-018、ADR-017（登录验证码），以及调研报告第 2～4 节。
4. 现有代码（`sys_user` 的读写全部集中在 `system` 模块，逐个过一遍）：
   - `system`：
     - mapper：`SysUserMapper`、`SysUserPermissionMapper`；
     - service：`AccountStoreService`、`UserService`、`UserPermissionService`、`PermissionChangeApplier`、`BdUserService`、`PlatformService`、`BootstrapAdmin`、`ExternalAuditService`；
     - `system.api` 中 20 新增的超管列表方法；
   - `security`：`CurrentUserContext`（含令牌会话中的 `credentialVersion`）、`LoginTenantResolver`、`AuthService`、`AuthController`、`SecurityConfiguration`（角色与权限码来源）；
   - `shared.CompanyTenantLineHandler`、`TenantScope`。

## 3. 执行环境与交付

- 在独立的 git 工作树中工作，分支 `task/21a-multi-company-backend` 从**最新的** `main` 检出（确认已含 16b、20）。
- **只修改 `backend/`、你的执行报告，以及第 4.9 节列出的文档。**
- 新增错误码时，按规范 03 在 `admin-ui/src/locales/{zh-CN,en-US}.ts` 补文案，前端其他文件不改。
- JDK 21 下执行 `mvn spotless:apply`、`mvn verify`、`mvn verify -Pdocker-it`，全部通过。
- 推送分支，按 PR 模板创建 PR，用 `gh pr checks --watch` 等 CI 通过。**不要合并 PR，不要推送 `main`。**
- **不新增依赖。**

## 4. 已定的设计决定（不要自行改变）

### 4.1 数据模型与迁移 `V202610172300__multi_company_membership.sql`

1. **`sys_user` 改为「人」**，不再属于某家公司：
   - 保留 `id`、`username`（全系统唯一，不变）、`password_hash`、`nickname`、`email`、`status`（整个账号的启停，只有平台管理员能改）、`credential_version`、`last_login_time`；
   - `role` 只区分 `PLATFORM_ADMIN` 和 `MEMBER`（公司账号），CHECK 相应修改；
   - `tenant_id` 改名为 `last_tenant_id`（上次所在的公司，可空），只用于登录时选默认公司；
   - `data_scope`、`post_template` 迁到成员关系后删除；
   - `sys_user` 加入 `CompanyTenantLineHandler` 的始终忽略列表，在 ADR-022 中记录理由。
2. **新表 `sys_user_tenant`（成员关系）**：
   - 字段：`id`、`user_id`、`tenant_id NOT NULL`、`role`（`SUPER_ADMIN` / `STAFF`）、`post_template`、`data_scope`、`status`（该公司内的启停）、`last_login_time`，加上通用字段与逻辑删除；
   - 部分唯一索引 `(user_id, tenant_id) WHERE is_deleted = FALSE`；
   - 原 `sys_user` 上关于角色与数据范围的 CHECK 约束搬到这里。
3. **`sys_user_permission` 加 `tenant_id NOT NULL`**：唯一键改为 `(user_id, tenant_id, permission_code)`。同一个人在不同公司的权限码互不影响。
4. **回填**：
   - 每个现有公司账号生成一条成员关系，`tenant_id`、`role`、`data_scope`、`post_template`、`status` 取自原 `sys_user`；
   - `sys_user_permission.tenant_id` 取该账号原来的公司；
   - 平台管理员没有成员关系。
5. 表与字段都有中文注释。在报告中评估锁表风险（账号表行数很少）。

### 4.2 当前公司与会话

1. **当前公司保存在 Sa-Token 令牌会话里**：属性名 `currentTenantId`，与已有的 `credentialVersion` 放在一起。同一浏览器的多个标签页共用同一个令牌，所以共用同一个当前公司。
2. **所有「当前公司」都从这里来**：
   - `LoginTenantResolver`、`CurrentUserContext.user()` 返回的 `AccountDTO.tenantId`；
   - 角色与权限码：取该公司成员关系的 `role` 与权限码。

   `AccountDTO` 的字段含义改为「当前公司中的身份」，形状尽量不变，另加 `companies`（见 4.8）。
3. **每次请求都要校验**：当前公司的成员关系启用、公司启用、账号启用。任何一项不满足时，结束这个令牌并返回 401 `AUTH_ACCOUNT_DISABLED`。只结束这个令牌，**不踢掉同一个人在其他公司的令牌**。
4. **兼容旧令牌**：上线前签发的令牌没有 `currentTenantId`。按账号的 `last_tenant_id`（即原来的公司）补上，不强制重新登录。

### 4.3 登录

1. 用户名、密码、验证码的流程不变（ADR-017）。
2. 登录成功后选当前公司：
   - 如果 `last_tenant_id` 对应的成员关系仍然可用，选它；
   - 否则选可用成员关系中公司名称排序第一的；
   - 一个可用的都没有时，返回 401 `AUTH_ACCOUNT_DISABLED`，不签发令牌。
3. 平台管理员登录不变，没有当前公司。
4. 更新 `sys_user.last_tenant_id` 和成员关系的 `last_login_time`。

### 4.4 切换公司

`POST /admin-api/auth/company`，请求体 `{tenantId}`：

1. 只能切到本人**可用**的成员关系所在的公司，否则返回 403 `AUTH_COMPANY_NOT_MEMBER`。
2. 成功后：
   - 更新令牌会话的 `currentTenantId`，以及 `sys_user.last_tenant_id`；
   - 在**目标公司**写一条操作日志「切换公司」，参数为来源公司 ID；
   - 返回与 `/me` 相同的结构。
3. 平台管理员调用返回 403 `AUTH_FORBIDDEN`。

### 4.5 防止「界面显示 A 公司，操作落在 B 公司」

1. 管理端在每个请求上带请求头 `X-Company-Id`，值为界面当前显示的公司。
2. 后端校验：带了这个头、但与令牌会话中的当前公司不一致时，返回 409 `AUTH_COMPANY_SWITCHED`，**不执行业务**。
3. 这个头**只用来核对**，绝不用来决定公司。没带这个头时不校验（兼容脚本与旧页面）。
4. `/admin-api/auth/**`、平台接口、对外复制接口不校验。
5. 用一个拦截器统一实现，并写单元测试。

### 4.6 账号管理（公司超管，`/admin-api/system/users/**`）

1. **列表**：返回本公司的成员（成员关系 join 账号），每行增加 `companyCount`（这个人属于几家公司）。
2. **新建**：
   - 与现在相同，新建一个账号并生成本公司的成员关系；
   - 用户名已存在时仍返回 409 `USER_USERNAME_EXISTS`；
   - **公司超管不能把其他公司已有的账号拉进本公司**，只有平台管理员可以（4.7）。
3. **修改角色、岗位模板、数据范围、启停、权限码**：只作用于本公司的成员关系。
   - 「至少保留一个启用的超管」按公司计算；
   - 停用 STAFF 时发出的 `StaffDisabledEvent` 携带本公司 ID，只把本公司内他名下的达人改为未分配；
   - 停用后只结束这个人当前在本公司的令牌。
4. **姓名、邮箱、密码属于个人**：
   - `companyCount = 1` 时，公司超管仍可修改；
   - `companyCount > 1` 时返回 422 `USER_MULTI_COMPANY_LOCKED`，由平台管理员修改。
5. **「可作为商务」的判定**（`BdUserService` 等）：改为本公司成员关系的启用 + `STAFF` + `OWN` + 本公司权限码含 `kol:edit`，同时账号启用。
6. **超管列表**（20 的站内消息用到）：改为本公司启用的 `SUPER_ADMIN` 成员。

### 4.7 平台管理员（`/admin-api/platform/**`，仅 `PLATFORM_ADMIN`）

| 方法与路径 | 说明 |
|---|---|
| `GET /platform/users?username=` | 按用户名**精确**查一个账号：`id`、`username`、`nickname`、`status`、`companies:[{tenantId, tenantName, role, status}]`；不存在时返回空。不支持模糊搜索。 |
| `POST /platform/tenants/{id}/members` | `{userId, role, postTemplate?, dataScope?}`：把已有账号加入该公司。已是成员返回 409 `USER_ALREADY_MEMBER`。`STAFF` 按岗位模板预填权限码（与 ADR-018 新建账号相同）；`dataScope` 不填时用模板的默认值 |
| `PUT /platform/users/{userId}` | 修改姓名、邮箱（任意账号） |
| `PUT /platform/users/{userId}/password` | 重置密码（任意账号）：提升 `credential_version`，使该账号所有令牌失效 |
| `PUT /platform/users/{userId}/status` | 整个账号启停；停用时结束该账号所有令牌 |

- 现有的 `/platform/tenants/{id}/admins/**`（为公司创建超管、重置超管密码）保留，行为不变。
- 平台管理员仍然**看不到任何业务数据**，不能进入公司。

### 4.8 接口约定（与 21b 共用，不得改动）

```text
GET  /admin-api/auth/me                → 现有字段（tenantId、tenantName、role、postTemplate、dataScope、permissions 均为当前公司）
                                          + companies: [{ tenantId, tenantName, role }]   （本人可用的公司，按公司名称排序；平台管理员为 []）
POST /admin-api/auth/login             → 现有字段 + companies（同上）
POST /admin-api/auth/company           → 请求 { tenantId }；响应同 /me；403 AUTH_COMPANY_NOT_MEMBER
请求头 X-Company-Id                     → 与会话当前公司不一致时 409 AUTH_COMPANY_SWITCHED
GET  /admin-api/system/users           → 每行增加 companyCount
PUT  /admin-api/system/users/{id}、/{id}/password → companyCount > 1 时，改姓名、邮箱、密码返回 422 USER_MULTI_COMPANY_LOCKED
GET  /admin-api/platform/users?username=
POST /admin-api/platform/tenants/{id}/members
PUT  /admin-api/platform/users/{userId}、/{userId}/password、/{userId}/status
```

新增错误码：

| 错误码 | HTTP |
|---|---|
| `AUTH_COMPANY_NOT_MEMBER` | 403 |
| `AUTH_COMPANY_SWITCHED` | 409 |
| `USER_MULTI_COMPANY_LOCKED` | 422 |
| `USER_ALREADY_MEMBER` | 409 |

### 4.9 文档

- **新建 ADR-022「管理员账号多公司成员关系与会话内切换」**，写明：
  - 背景；
  - 采用 P2 而不采用 P3 / P4 的理由；
  - 4.1～4.7 的决定；
  - 后果。
- **ADR-014、ADR-018**：状态注明「部分被 ADR-022 修订」，并列出被修订的条目：
  - 「每家各开一个账号」改为「一个账号可属于多家公司」；
  - 权限码改为按公司授予。
- **设计说明书**：
  - §3.1、§3.3：会话中的当前公司；
  - §3.4：「公司与账号」表、隔离机制第 2 条（当前公司来自会话，可在本人的成员关系之间切换）；
  - §4.13 第 5 条：停用按公司计算；
  - §5.2：`sys_user`、`sys_user_tenant`、`sys_user_permission`；
  - §6.2：上述接口。
- 达人端的规则（§3.4「达人端登录」）不改。

## 5. 执行步骤

1. 迁移与回填；在本地已有数据的库上验证一遍。
2. 账号读取：`AccountStoreService` 按「账号 + 当前公司」组装 `AccountDTO`。同时调整令牌会话中的当前公司、`LoginTenantResolver`，以及角色与权限码的来源。
3. 登录选公司、切换接口、`X-Company-Id` 校验。
4. 账号管理、商务判定、超管列表、停用事件改为按成员关系。
5. 平台接口。
6. 测试、文档、执行报告。

## 6. 测试要求

| 场景 | 期望 |
|---|---|
| 迁移 | 回填后每个公司账号恰好一条成员关系；权限码带上公司；平台管理员没有成员关系；旧令牌按 `last_tenant_id` 补上当前公司继续可用 |
| 登录 | 只有一家公司直接进入；多家公司进入上次的公司；上次的公司已停用或成员关系已停用时进入另一家；全部不可用时 401 |
| 切换 | 切到自己的公司成功，角色与权限码随之变化（A 公司是超管、B 公司是商务）；切到非成员公司 403；两个标签页共用会话 |
| 隔离 | 切换后读写的都是新公司的数据；`X-Company-Id` 与会话不一致时 409 且业务未执行；这个头不能用来进入其他公司 |
| 账号管理 | 列表只有本公司成员并带 `companyCount`；停用只影响本公司（另一家公司的令牌仍可用）；多公司账号改姓名、邮箱、密码返回 422；新建同名账号 409 |
| 商务与事件 | 「可作为商务」按本公司成员关系判定；停用事件只把本公司的达人改为未分配；超管列表按本公司 |
| 平台 | 按用户名精确查找；加入成员时按模板预填权限；重复加入 409；改密码使所有令牌失效；整个账号停用；平台接口仍然看不到业务数据 |
| 权限 | 每个新接口的角色检查；平台管理员不能调用切换接口 |
| 回归 | 现有全部测试通过；`SystemIT` 中的公司字段清单按新表结构更新，不得删减断言 |

集成测试至少覆盖：一个账号属于两家公司，在两家公司分别有不同的角色与权限，切换前后访问达人列表看到的是不同公司的数据；`X-Company-Id` 的 409。

## 7. 文档与报告

执行报告写在 `docs/04-开发任务/21a-多公司账号与切换-后端-执行报告.md`：迁移与回填、会话与当前公司、登录与切换、`X-Company-Id` 校验、账号管理与平台接口的变化、测试清单、偏离与遗留。

## 8. 禁止事项与停止条件

**禁止**：

- 修改已合并的迁移脚本；
- 新增依赖；
- 让请求头或请求体**决定**当前公司；
- 让平台管理员看到业务数据；
- 改变达人端的登录与公司规则；
- 修改 `admin-ui/`（语言包除外）、`kol-portal/`、`redirect/`、`deploy/`；
- 删除或弱化测试；
- 推送 `main` 或合并 PR；
- 使用 `push --force`、`reset --hard`。

**遇到以下情况停下来**（推送已完成的提交，以 `[WIP]` 标题创建 PR，在报告中说明）：

- 发现 `system` 模块以外还有直接读写 `sys_user` 角色或公司字段的代码；
- 设计说明书、ADR 与本提示词冲突；
- 验证在合理排查后仍失败。
