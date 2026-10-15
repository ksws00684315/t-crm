# 任务 10a（后端）：超级管理员 + 可配置权限的子账号

> 本文件是交给编码代理执行的提示词，把全文作为任务输入即可。
>
> **并行说明**：与 10b（管理端）、11（达人档案扩展）、12a（达人账号）同时进行。本任务改动面最大：几乎所有 controller 的权限注解都要改。请**尽早**提交 PR，其他任务合并时以本任务为准做 rebase。

---

## 1. 背景

客户在 2026-10-14 会议上确认：每家公司只有**超级管理员**（兼财务）、**运营**、**商务**三种人，财务工作由超级管理员完成；同意「像千牛那样，主账号为子账号逐项勾选可用功能」。

负责人据此做了决定，见 [ADR-018](../06-架构决策/ADR-018-管理端改为超级管理员加可配置权限的子账号.md)：

- 账号分为平台管理员、超级管理员（`SUPER_ADMIN`）和子账号（`STAFF`）；
- 子账号的功能权限按**权限码**逐项授予；
- 运营、商务是新建子账号时选择的**岗位模板**，只用于预填权限，不是角色；
- 取消 `OPERATOR`、`FINANCE` 两个角色。

现有实现用 `@SaCheckRole` 写死角色（35 处），服务层另有 9 处按 `Role.OPERATOR` / `Role.FINANCE` 判断。

## 2. 开始前必读

1. `AGENTS.md`、`docs/05-工程规范/`（重点：03、04、05、06）。
2. ADR-018；设计说明书 §1.3、§3.1（权限码清单与岗位模板）、§3.2、§3.3、§5.2 中的 `sys_user_permission`。
3. 现有代码：
   - `security/api`：`Role`、`DataScope`、`AccountDTO`、`CurrentUserContext`；
   - `security/config/SecurityConfiguration`；
   - `system` 模块：`UserController`、`UserService`、`BdUserService`、`CompanyRole` 等；
   - 所有带 `@SaCheckRole` 的 controller；
   - `grep -rn "Role\.\(OPERATOR\|FINANCE\)"` 列出的服务层判断；
   - 测试中的 `ArchitectureTest`、`CompanySessions`、`TenantIsolationIT`。

## 3. 执行环境与交付

- 在独立的 git 工作树中工作，分支 `task/10a-permissions-backend` 已从最新的 `main` 检出。
- **只修改 `backend/`、本任务的执行报告，以及第 4.8 节列出的文档。**
- 在 JDK 21 下执行 `mvn spotless:apply`、`mvn verify`、`mvn verify -Pdocker-it`，全部通过。
- 完成后推送分支，按 PR 模板创建 PR，用 `gh pr checks --watch` 等 CI 通过。**不要合并 PR，不要推送 `main`。**
- 提交遵循规范 08，末尾加执行代理自己的 `Co-Authored-By` 行。

## 4. 已定的设计决定（不要自行改变）

### 4.1 角色与权限码

1. `Role` 改为 `PLATFORM_ADMIN`、`SUPER_ADMIN`、`STAFF`。
2. 新增枚举 `security.api.Permission`，集中定义下表**全部**权限码（含后续任务才用到的），每项带分组；中英文名称由管理端语言包提供，后端不存名称。
3. 新增枚举 `PostTemplate`：`OPERATOR`、`BD`，各自给出默认权限集合与默认数据范围。

| 权限码 | 分组 | OPERATOR 模板 | BD 模板 |
|---|---|:-:|:-:|
| `kol:view` | KOL | ✓ | ✓ |
| `kol:edit` | KOL | | ✓ |
| `kol:register-link` | KOL | | ✓ |
| `kol:copy-token` | KOL | | ✓ |
| `product:view` | PRODUCT | ✓ | ✓ |
| `product:edit` | PRODUCT | ✓ | |
| `product:status` | PRODUCT | ✓ | |
| `order:view` | ORDER | ✓ | ✓ |
| `order:manage` | ORDER | ✓ | |
| `commission:view` | FUNDS | | ✓ |
| `commission:freeze` | FUNDS | | |
| `payout-account:review` | FUNDS | | ✓ |
| `withdrawal:review` | FUNDS | | ✓ |
| `prepay:apply` | FUNDS | | ✓ |
| `dashboard:shop` | DASHBOARD | ✓ | |
| `dashboard:team` | DASHBOARD | | ✓ |
| `dashboard:kol` | DASHBOARD | ✓ | ✓ |
| `data:export` | OTHER | ✓ | ✓ |

默认数据范围：OPERATOR 为 `ALL`，BD 为 `OWN`。

### 4.2 数据库迁移

新增 `V202610171000__configurable_permissions.sql`（版本号固定，不要改；与并行任务 11、12a 的迁移按版本号排序）。已合并的脚本一律不改。

1. 新表 `sys_user_permission`：
   - 字段：`id`（雪花，正值）、`tenant_id NOT NULL`、`user_id NOT NULL`、`permission_code VARCHAR(48) NOT NULL`、`create_by`、`create_time`；
   - 唯一索引 `(user_id, permission_code)`；
   - 表和字段都要有中文注释；只追加和删除，省略修改类字段。
2. `sys_user` 新增 `post_template VARCHAR(16) NULL`，`CHECK (post_template IN ('OPERATOR','BD'))`，注释为「创建时选择的岗位模板，仅用于显示」。
3. 改约束：
   - `ck_sys_user_role` 改为 `IN ('PLATFORM_ADMIN','SUPER_ADMIN','STAFF')`；
   - `ck_sys_user_own` 改为 `data_scope = 'ALL' OR role = 'STAFF'`；
   - 同步更新 `role`、`data_scope` 两列的注释。
4. 转换已有数据（要先转换数据、再加新约束；脚本要能在已有数据的开发库和测试库上执行）：

   | 原账号 | 转换后 |
   |---|---|
   | `OPERATOR` + `ALL` | `STAFF`，`post_template = 'OPERATOR'`，授予 OPERATOR 模板 |
   | `OPERATOR` + `OWN` | `STAFF`，`post_template = 'BD'`，授予 BD 模板 |
   | `FINANCE` | `STAFF`，`post_template` 为空，`data_scope = 'ALL'`，授予 `kol:view`、`product:view`、`order:view`、`commission:view`、`dashboard:shop`、`dashboard:team`、`dashboard:kol`、`data:export` |

   迁移里的 `id` 用 `nextval` 序列或其他办法生成正值且不冲突，并在报告中说明做法。

### 4.3 运行时授权

1. `AccountDTO` 增加 `permissions`（`Set<String>`）和 `postTemplate`。
   - `AccountStoreService` 读取账号时一起读出权限；
   - 仍然走现有的请求级缓存（`CurrentUserContext.account(id)`），每个请求只查一次，测试中断言 SQL 次数不增加超过 1 次；
   - 权限与账号状态一样每次请求重读，修改后立即生效。
2. `StpInterface.getPermissionList`：
   - `admin` 类型：`SUPER_ADMIN` 返回 `Permission` 的全部权限码，`STAFF` 返回已授予的码，`PLATFORM_ADMIN` 返回空；
   - 其他类型返回空。
3. 改注解：
   - 业务接口一律改为 `@SaCheckPermission`；
   - 超管专属接口改为 `@SaCheckRole("SUPER_ADMIN")`；
   - 只要求登录的接口（`/auth/logout`、`/auth/me`、`/auth/password`、任务进度）改为 `@SaCheckRole(value={"PLATFORM_ADMIN","SUPER_ADMIN","STAFF"}, mode=OR)`，或公司账号对应的角色组合；
   - 现有接口的对照见 4.4。
4. 服务层所有 `Role.OPERATOR`、`Role.FINANCE` 判断改掉：
   - 「商务本人范围」统一为 `role == STAFF && dataScope == OWN`，提取为一个公共方法，不要在各模块重复；
   - 原来「财务只读」的分支删掉，只读由接口权限码保证。
5. `ArchitectureTest`：允许 controller 方法或类上的 `@SaCheckPermission` 作为「已声明」；规则名与失败信息同步改。

### 4.4 现有接口的权限对照

| 接口 | 改为 |
|---|---|
| 达人列表、详情 | `kol:view` |
| 达人新建、编辑、启停、导入模板下载、导入 | `kol:edit` |
| `PUT /kol/kols/{id}/bd-user`（更换商务） | 超管专属（设计 §3.1；以后由任务 16 的转移接口取代） |
| `GET /system/bd-users` | `kol:view` |
| 店铺列表 | `product:view` |
| 店铺新建、编辑、启停 | 超管专属 |
| 原商品列表、详情 | `product:view` |
| 原商品编辑、导入模板下载、导入 | `product:edit` |
| 原商品上架 / 下架 | `product:status` |
| 对应关系查询 | `product:view`；数据范围 `OWN` 的账号仍然拒绝（保持现状，`KolItemService` 原有判断改写为本人范围方法） |
| 对应关系导入模板下载、导入 | `product:edit` |
| 中转链接列表 | `product:view`（`OWN` 范围规则不变） |
| 链接索引手动重建 | 超管专属 |
| 微店接入：店铺接入状态列表 | `product:view` |
| 微店接入：其余 | 超管专属 |
| 用户管理、操作日志 | 超管专属（不变） |
| 任务进度、错误文件 | 公司账号登录即可，保留现有的「提交人或超管」判断 |
| 平台管理 | `PLATFORM_ADMIN`（不变） |

### 4.5 用户与权限接口

1. 创建、编辑用户（`POST/PUT /admin-api/system/users`）：
   - `role` 只接受 `SUPER_ADMIN`、`STAFF`；
   - `STAFF` 必须带 `postTemplate`（`OPERATOR` / `BD`）；
   - `dataScope` 可选，不填用模板默认值；
   - 可带 `permissions` 数组，不带时按模板授予；
   - `SUPER_ADMIN` 不保存权限行，`post_template` 为空；
   - `OWN` 只能用于 `STAFF`。
2. `GET /admin-api/system/permissions`（超管专属）返回 `{permissions:[{code, group}], templates:[{code, permissions:[...], dataScope}]}`，顺序与枚举一致。
3. 子账号权限：
   - `GET /admin-api/system/users/{id}/permissions`、`PUT /admin-api/system/users/{id}/permissions`（超管专属）；
   - 请求 `{permissions:[codes], dataScope}`，整体替换；
   - 不认识的权限码返回 400，`errorCode` 为 `USER_PERMISSION_INVALID`（登记到 `shared.ErrorCode`，并补前端错误码映射测试需要的中英文文案）；
   - 目标不是 `STAFF` 时返回 400；跨公司的用户 ID 返回 404。
4. `UserVO` 与 `/auth/me` 增加 `postTemplate`、`permissions`。`/auth/me` 对超管返回全部权限码，对平台管理员返回空数组。
5. 审计：修改权限的接口用 `@AuditLog` 记录，参数中要包含修改前后的权限码集合。
6. 停用、降级超管时「至少保留一个超管」的规则不变。
7. `BdUserService` 的可选商务改为：同公司、启用、`STAFF`、`OWN`、拥有 `kol:edit` 的账号。

### 4.6 不在本任务范围

- 管理端界面（任务 10b）。
- 新功能接口（注册链接、复制令牌、资金等），由后续任务按本任务的权限码声明。
- 动态菜单、自定义角色。

### 4.7 与 10b 共用的接口约定（不得改动）

```text
GET  /admin-api/auth/me                      → data: UserVO + tenantId/tenantName + postTemplate + permissions: string[]
GET  /admin-api/system/permissions           → data: { permissions: [{code, group}], templates: [{code, permissions, dataScope}] }
GET  /admin-api/system/users                 → 分页 UserVO（含 postTemplate、permissions）
POST /admin-api/system/users                 ← {username, password, nickname, email?, role: SUPER_ADMIN|STAFF, postTemplate?, dataScope?, permissions?}
PUT  /admin-api/system/users/{id}            ← {nickname, email?, role, postTemplate?, dataScope?}
GET  /admin-api/system/users/{id}/permissions → data: {permissions: string[], dataScope}
PUT  /admin-api/system/users/{id}/permissions ← {permissions: string[], dataScope}
group 取值：KOL | PRODUCT | ORDER | FUNDS | DASHBOARD | OTHER
```

### 4.8 需要同步的文档

- 设计说明书 §2.2「当前接口」表：去掉 `OPERATOR` / `FINANCE`，写成实施后的状态，并删掉 2026-10-16 加的「ADR-018 实施后……」一句。
- 设计说明书 §6.2：用户、权限接口两行，与 4.7 一致。

## 5. 执行步骤

1. 迁移脚本与 `Permission` / `PostTemplate` 枚举。
2. 读取账号带权限、`StpInterface`。
3. 逐个 controller 改注解，处理服务层的角色判断。
4. 用户与权限接口、`/auth/me`。
5. `ArchitectureTest`、测试辅助类（`CompanySessions` 等能按权限码造子账号）。
6. 文档与执行报告。

## 6. 测试要求

| 场景 | 期望 |
|---|---|
| 迁移 | 在含 `OPERATOR`（`ALL` / `OWN`）、`FINANCE` 账号的库上执行后，角色、模板、权限行与 4.2 一致 |
| 权限码 | 4.4 表中每个权限码至少一条「缺少该码的子账号访问返回 403」，以及一条「拥有该码可访问」 |
| 超管专属 | 拥有全部权限码的子账号访问超管专属接口仍返回 403 |
| 立即生效 | 撤销权限后，同一令牌的下一次请求即返回 403，不需要重新登录 |
| 数据范围 | 商务（`STAFF` + `OWN`）在达人、对应关系、链接上的本人范围过滤与原来一致 |
| 用户接口 | 模板默认值、显式权限、非法码 400、对超管设权限 400、跨公司 404 |
| SQL 次数 | 带权限读取后，每个请求的账号查询次数与改动前相比最多多 1 次 |
| 平台管理员 | 所有业务接口仍拒绝 |
| 架构测试 | 删除某接口的注解时 `ArchitectureTest` 失败 |

现有测试不得删除或弱化；因角色改名必须修改的断言，在报告中逐条列出。

## 7. 文档与报告

执行报告写在 `docs/04-开发任务/10a-可配置权限-后端-执行报告.md`，内容包括：迁移做法与转换结果；接口与权限码对照（实际落实）；服务层改动清单；测试清单；偏离与遗留。

## 8. 禁止事项与停止条件

**禁止**：修改已合并的迁移脚本；新增依赖；修改 `admin-ui/`、`kol-portal/`、`redirect/`、`deploy/`；删除或弱化测试；推送 `main` 或合并 PR；使用 `push --force`、`reset --hard`。

**遇到以下情况停下来**（推送已完成的提交，以 `[WIP]` 标题创建 PR，在报告中说明）：

- 设计说明书、ADR-018、本提示词之间存在冲突；
- 某个现有接口的权限归属在 4.4 中找不到，也无法从设计 §3.1 推出；
- 验证在合理排查后仍失败。
