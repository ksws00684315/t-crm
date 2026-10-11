# 任务 06c（管理端）：平台管理员与公司管理页面

> 本文件是交给编码代理执行的提示词。直接把本文件全文作为任务输入即可。
> **前置条件：PR #26（多公司与中英文切换设计）已合并到 `main`。** 未合并时停止，不要开始。
>
> **并行说明**：任务 06c 拆成两份同时执行。后端由另一个代理在另一个工作树中按《06c-多公司底座-后端》实现，**你开发时后端接口还不存在**。请严格按照第 4.1 节的接口约定开发；测试一律使用模拟接口。约定与后端提示词第 4.8 节完全相同，不得自行调整。另有代理同时在改 `kol-portal/`。

---

## 1. 背景

系统要支持客户旗下的多家公司，数据按公司隔离（设计说明书 §3.4、ADR-014）。后端新增角色**平台管理员**（`PLATFORM_ADMIN`）：它不属于任何公司，只能管理公司，看不到任何业务数据。

本任务只改管理端（`admin-ui/`）：

1. 平台管理员登录后只看到"公司管理"；
2. 平台管理员能维护公司，并为公司创建超级管理员、重置超管密码；
3. 公司内用户在页头看到所属公司名称。

## 2. 开始前必读

1. `AGENTS.md`、`docs/05-工程规范/`（重点：02 第 7 节、03、05）
2. 设计说明书 §1.3、§3.1、§3.4、§6.2"平台"行；ADR-014
3. 现有代码：
   - `admin-ui/src/api/`：`http.ts`、`types.ts`、`error-codes.ts`、`auth.ts`、`system/user.ts`；
   - `admin-ui/src/stores/user.ts`、`router/`（`routes.ts`、`guard.ts`）、`utils/permission.ts`、`layout/index.vue`；
   - `views/system/user/`（列表、表单对话框、重置密码对话框），**新页面请沿用这里的写法**；
   - `views/home/`；
   - 以上目录中 `__tests__` 下的测试写法。

## 3. 执行环境与交付

- 你在一个独立的 git 工作树中工作，分支 `task/06c-admin-ui` 已基于最新的 `main` 检出，直接在此分支上提交。
- **只修改 `admin-ui/` 和你的执行报告。** 不要修改 `backend/`、`kol-portal/`、`redirect/`、`docs/03-设计/` 和 `docs/04-开发任务/README.md`（任务索引由项目负责人维护）。
- 在 `admin-ui/` 下执行 `pnpm install --frozen-lockfile && pnpm lint && pnpm typecheck && pnpm test && pnpm build`，全部通过。
- 完成后推送分支，用 `gh pr create` 按 PR 模板创建 PR，并用 `gh pr checks --watch` 等待 CI 全部通过。**不要合并 PR，不要推送 `main`。**
- 提交遵循规范 08，末尾加执行代理自己的 `Co-Authored-By` 行。
- **不新增依赖。**

## 4. 已定的设计决定（不要自行改变）

### 4.1 接口约定

响应使用统一信封；ID 为字符串，时间为 ISO 8601 UTC 字符串，`status` 为数字。

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

**⑤ 校验规则**（前端表单与后端一致）

| 字段 | 规则 |
|---|---|
| `tenantCode` | `^[a-z0-9-]{2,32}$` |
| `name`、`nickname` | 非空白，最多 64 个字符 |
| `remark` | 最多 500 个字符，可为 `null` |
| `status` | 0 或 1 |
| `username` | `^[A-Za-z0-9_.-]{3,64}$` |
| `password` | 10～72 个可打印 ASCII 字符 |
| `email` | 邮箱格式，最多 254 个字符，可为 `null` |

**⑥ 错误码**

| HTTP | 错误码 | 含义 |
|---|---|---|
| 400 | `COMMON_VALIDATION_FAILED` | 参数校验失败，带字段级列表 |
| 403 | `AUTH_FORBIDDEN` | 无权访问 |
| 404 | `COMMON_NOT_FOUND` | 公司或账号不存在 |
| 409 | `TENANT_CODE_EXISTS` | 公司编码重复（**新增**） |
| 409 | `USER_USERNAME_EXISTS` | 用户名重复 |

- 公司内的用户管理接口收到 `role = "PLATFORM_ADMIN"` 时，返回 `COMMON_VALIDATION_FAILED`。
- 公司被停用后，该公司用户的下一次请求返回 401，按现有的 401 处理（回到登录页）即可。

### 4.2 角色与权限

1. `api/types.ts` 的 `Role` 增加 `"PLATFORM_ADMIN"`，`UserVO` 增加 `tenantId`、`tenantName`。
2. `ROLE_LABELS` 增加"平台管理员"。
3. 公司内用户管理的角色下拉、筛选**不出现**平台管理员。现在 `ROLES` 同时用于显示和选择，请把"可选择的角色"与"所有角色"分开，避免以后误用。
4. 平台管理员**只能**访问：
   - 公司管理页；
   - 修改密码页、403 页、404 页。
5. 现在没有声明 `roles` 的路由（如"首页"）对任何已登录用户开放。必须改成平台管理员访问不到，二选一：
   - 给业务路由显式声明角色；
   - 在权限函数中把平台管理员单独处理。

   要求：平台管理员登录后、访问 `/` 或"首页"时，都跳到公司管理页，不出现任何会调用业务接口的页面。
6. 业务角色（超管、运营、财务）**看不到**公司管理菜单；直接访问公司管理页时进入 403。
7. 侧边菜单与路由守卫继续使用同一份路由元数据（`buildMenu` 与 `canAccessRoute` 保持一致）。

### 4.3 公司管理页

- **路由**：`/platform/tenants`，菜单标题"公司管理"，`meta.roles: ["PLATFORM_ADMIN"]`。
- **API 文件**：`api/platform/tenant.ts`，写法与 `api/system/user.ts` 一致。

**页面内容**

1. **列表**：公司编码、名称、状态（启用 / 停用标签）、备注、创建时间、操作列。数据量小，不分页，不需要筛选。
2. **新建 / 编辑公司**（对话框）：
   - 新建时填编码、名称、备注；编辑时编码只读，只能改名称和备注；
   - 表单校验按第 4.1 节 ⑤；编码输入框提示"小写字母、数字、连字符，2～32 位，创建后不可修改"。
3. **启用 / 停用**：二次确认。停用的确认文字说明后果："停用后，该公司所有管理员将无法登录，已登录的会在下一次操作时退出；中转链接不受影响。"
4. **超级管理员**（每行一个"超级管理员"操作，打开抽屉或对话框）：
   - 列出该公司的超管：用户名、昵称、邮箱、状态、最近登录时间；
   - "新建超级管理员"：用户名、昵称、邮箱、初始密码，校验同第 4.1 节 ⑤。可以复用 `UserFormDialog`，也可以新写，但不要显示角色和数据范围；
   - 每行"重置密码"：复用或参照 `ResetPasswordDialog`；成功后提示"该管理员已在所有设备上退出"。
5. **错误提示**：按 `errorCode` 映射（规范 02）。`error-codes.ts` 增加 `TENANT_CODE_EXISTS: "公司编码已存在"`。字段级错误显示在对应表单项上，沿用现有对话框的处理方式。

### 4.4 页头

- 公司内用户：在用户名旁显示所属公司名称（`tenantName`）。
- 平台管理员：显示"平台管理"字样。
- `tenantName` 为空时不显示公司名称，也不报错，以兼容后端尚未合并的情况。

### 4.5 界面文字

本任务仍按现状写中文。**中英文切换由任务 06d 统一改造，本任务不要提前引入国际化。**

## 5. 执行步骤

1. 类型、角色与权限函数（第 4.2 节），并更新测试。
2. 路由与守卫：平台管理员的访问范围与跳转。
3. 公司管理 API 与页面（第 4.3 节）。
4. 页头（第 4.4 节）。
5. 执行报告与 PR。

## 6. 测试要求

用 Vitest 和模拟接口，至少覆盖以下场景：

| 场景 | 期望 |
|---|---|
| `buildMenu`：平台管理员 | 只有"公司管理" |
| `buildMenu`：超管 | 不含"公司管理"，其余菜单与现在相同 |
| 路由守卫：平台管理员访问 `/`、`/home`、`/kol/kols`、`/system/users` | 跳到公司管理页或 403，不进入业务页面 |
| 路由守卫：超管访问 `/platform/tenants` | 403 |
| 公司页：加载列表 | 正确显示 |
| 公司页：新建公司，编码校验失败 | 不调用接口 |
| 公司页：新建公司，返回 `TENANT_CODE_EXISTS` | 显示"公司编码已存在" |
| 公司页：停用 | 弹出二次确认，确认后调用 status 接口 |
| 超级管理员 | 列表加载、新建、重置密码三个流程 |
| 用户管理的角色下拉 | 不含平台管理员 |
| 页头 | 显示公司名称；平台管理员显示"平台管理"；`tenantName` 缺失时不报错 |

现有测试全部通过，不得删除或弱化。

## 7. 文档与报告

执行报告写在 `docs/04-开发任务/06c-多公司底座-管理端-执行报告.md`，内容包括：

- 每条设计决定的落实位置；
- 路由权限方案的选择及理由（第 4.2 节第 5 条）；
- 测试清单与结果；
- 截图（可选，用本地开发服务器配合模拟数据截取）；
- 偏离与遗留事项，至少写明：与后端联调由项目负责人在两个 PR 都合并后进行。

## 8. 禁止事项与停止条件

**禁止**：

- 修改 `admin-ui/` 以外的代码，或修改任务索引；
- 新增依赖；
- 调整第 4.1 节的接口约定；
- 删除或弱化现有测试；
- 推送 `main` 或合并 PR；使用 `push --force`、`reset --hard`。

**遇到以下情况停下来**：推送已完成的提交，以 `[WIP]` 标题创建 PR，在执行报告中说明后结束。

- 接口约定无法满足页面需求；
- 设计说明书、本提示词、规范之间存在冲突；
- 验证在合理排查后仍然失败。
