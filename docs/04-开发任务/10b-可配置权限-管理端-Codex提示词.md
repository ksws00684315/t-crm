# 任务 10b（管理端）：按权限码显示菜单与按钮，用户管理改为子账号权限勾选

> 本文件是交给编码代理执行的提示词，把全文作为任务输入即可。
>
> **并行说明**：与 10a（后端）同时进行，按第 4.5 节的接口约定开发，不依赖 10a 已合并。联调时以 10a 的分支为准。

---

## 1. 背景

[ADR-018](../06-架构决策/ADR-018-管理端改为超级管理员加可配置权限的子账号.md) 把公司内的账号改为两类：

- **超级管理员**（`SUPER_ADMIN`）：拥有全部权限。
- **子账号**（`STAFF`）：权限由超级管理员按权限码逐项勾选；运营、商务只是新建子账号时选择的岗位模板。

`OPERATOR`、`FINANCE` 两个角色取消。管理端目前按角色名判断菜单和按钮，需要全部改为按权限码判断，并把用户管理改成「选岗位模板 + 勾选权限 + 数据范围」。

## 2. 开始前必读

1. `AGENTS.md`、`docs/05-工程规范/`（重点：02 第 7 节、05、06 第 3 节）。
2. ADR-018；设计说明书 §1.3、§3.1（权限码清单、岗位模板、超管专属操作）。
3. `docs/04-开发任务/06e-管理端布局与交互体验-执行报告.md` 第 2 节：后续页面必须使用的公共组件。
4. 现有代码：
   - `admin-ui/src/utils/permission.ts`：`kolAbilities`、`productAbilities`、`linkAbilities`、`buildMenu`、`canAccessRoute`；
   - `router/routes.ts`（`meta.roles`、`hideForOwn`）；
   - `stores/user.ts`、`api/types.ts`；
   - `views/system/user/`；
   - 各页面中按角色显示按钮的地方；
   - `locales/`。

## 3. 执行环境与交付

- 在独立的 git 工作树中工作，分支 `task/10b-permissions-admin-ui` 已从最新的 `main` 检出。
- **只修改 `admin-ui/` 和你的执行报告。**
- 在 `admin-ui/` 下执行 `pnpm install --frozen-lockfile && pnpm lint && pnpm typecheck && pnpm test && pnpm build && pnpm notices:check`，全部通过。
- 推送分支并按 PR 模板创建 PR，用 `gh pr checks --watch` 等 CI 通过。**不要合并 PR，不要推送 `main`。**
- **不新增依赖。**

## 4. 已定的设计决定（不要自行改变）

### 4.1 类型与登录态

1. `Role` 改为 `"PLATFORM_ADMIN" | "SUPER_ADMIN" | "STAFF"`；新增类型 `PermissionCode`（4.5 中的 18 个码）和 `PostTemplate`（`"OPERATOR" | "BD"`）。
2. `UserVO` 增加 `postTemplate: PostTemplate | null`、`permissions: PermissionCode[]`。
3. 用户状态里保存 `/auth/me` 返回的 `permissions`，提供：
   - `can(code)`：超管恒为真；
   - `isSuperAdmin`；
   - `isOwnScope`：`STAFF` 且 `OWN`。
4. 路由守卫和菜单只用这三个判断，页面里不再出现角色名比较；超管专属功能用 `isSuperAdmin`。

### 4.2 菜单与路由

1. 路由 `meta` 改用：
   - `permission?: PermissionCode`：拥有该码即可访问；
   - `superAdmin?: true`：超管专属；
   - `platformAdmin?: true`：平台管理员专属。

   删除 `meta.roles`。`hideForOwn` 保留语义，供对应关系使用。
2. 现有页面的映射：

   | 页面 | 访问条件 |
   |---|---|
   | 首页 | 公司账号 |
   | 达人 | `kol:view` |
   | 店铺 | `product:view` |
   | 原商品 | `product:view` |
   | 对应关系 | `product:view`，且 `OWN` 不显示 |
   | 用户管理、操作日志 | 超管专属 |
   | 公司管理 | 平台管理员专属 |

3. 用户的权限在会话中途被改小，接口返回 403 时：保留现有提示，并在下次调用 `/auth/me`（页面刷新、重新登录）后更新菜单。不需要实时推送。

### 4.3 页面中的按钮

把 `kolAbilities` / `productAbilities` / `linkAbilities` 改为按权限码计算，**函数名和返回字段可以保留**，以减少页面改动：

| 能力 | 条件 |
|---|---|
| 达人新增、编辑、启停、导入 | `kol:edit` |
| 更换商务 | 超管 |
| 新建、导入达人时指定商务 | 超管，或有 `kol:edit` 且数据范围为全部的子账号（`OWN` 新建的达人归本人，后端已如此处理） |
| 达人列表按商务筛选 | `kol:view` 且不是 `OWN` |
| 店铺新增、编辑、启停 | 超管 |
| 原商品编辑、导入 | `product:edit` |
| 原商品上架、下架（含 06 之后的批量） | `product:status` |
| 对应关系查看 | `product:view` 且不是 `OWN` |
| 对应关系导入 | `product:edit` |
| 链接查看 | `product:view` |
| 链接索引重建 | 超管 |
| 导出按钮 | `data:export` |

### 4.4 用户管理

1. **列表**：增加「账号类型」（超级管理员 / 子账号）和「岗位」（运营 / 商务 / —）两列，以及「权限数」列，例如「9 项」，悬停时显示权限名称列表。
2. **新建**：
   - 先选账号类型。
   - 选「子账号」时必须选岗位模板（运营 / 商务）。选中后，按 `GET /system/permissions` 返回的模板勾选权限，并设置数据范围（运营为全部，商务为本人）。之后可以逐项增减。
   - 选「超级管理员」时隐藏权限区，提示「拥有全部权限」。
3. **权限区**：
   - 按分组（达人、商品、订单、资金、看板、其他）显示复选框；
   - 每组有「全选」；
   - 每个权限显示中文 / 英文名称和一句说明（来自语言包，键为 `permission.<code>.name` / `.desc`）。
   - 权限码以后会增加，界面按接口返回的清单渲染，未知码显示原码，不报错。
   - 下方单列「超管专属，不可授予」的说明：用户与权限、系统配置、操作日志、审批预付、标记打款、转移达人。
4. **编辑**：
   - 基本信息与权限分开保存。权限用单独的「权限设置」对话框，调用 `PUT /system/users/{id}/permissions`。
   - 改岗位模板只改显示，不自动改已有权限；对话框内提供「按模板重置」按钮。
5. 数据范围用单选：全部数据 / 仅本人负责的达人。超管不显示该项。

### 4.5 接口约定（与 10a 共用，不得改动）

```text
GET  /admin-api/auth/me                      → data: UserVO + tenantId/tenantName + postTemplate + permissions: string[]
GET  /admin-api/system/permissions           → data: { permissions: [{code, group}], templates: [{code, permissions, dataScope}] }
GET  /admin-api/system/users                 → 分页 UserVO（含 postTemplate、permissions）
POST /admin-api/system/users                 ← {username, password, nickname, email?, role: SUPER_ADMIN|STAFF, postTemplate?, dataScope?, permissions?}
PUT  /admin-api/system/users/{id}            ← {nickname, email?, role, postTemplate?, dataScope?}
GET  /admin-api/system/users/{id}/permissions → data: {permissions: string[], dataScope}
PUT  /admin-api/system/users/{id}/permissions ← {permissions: string[], dataScope}
group 取值：KOL | PRODUCT | ORDER | FUNDS | DASHBOARD | OTHER
新错误码：USER_PERMISSION_INVALID（400）
```

18 个权限码：

```text
kol:view, kol:edit, kol:register-link, kol:copy-token,
product:view, product:edit, product:status,
order:view, order:manage,
commission:view, commission:freeze, payout-account:review, withdrawal:review, prepay:apply,
dashboard:shop, dashboard:team, dashboard:kol,
data:export
```

各码的含义见设计说明书 §3.1，中英文名称和说明按 06d 的国际化约定写入两份语言包。

### 4.6 不在本任务范围

- 后端（任务 10a）。
- 后续新功能页面（注册链接、资金等）。
- 动态菜单。

## 5. 执行步骤

1. 类型、用户状态与 `can` / `isSuperAdmin` / `isOwnScope`。
2. 路由 `meta` 与菜单。
3. 各页面按钮条件（4.3）。
4. 用户管理：列表、新建、权限对话框。
5. 语言包、测试、执行报告。

## 6. 测试要求

| 场景 | 期望 |
|---|---|
| 菜单 | 超管看到全部；只有 `kol:view` 的子账号只看到首页和达人；平台管理员只看到公司管理 |
| 路由守卫 | 直接访问无权限的路由进入 403；`OWN` 访问对应关系进入 403 |
| 按钮 | 4.3 每一行至少一条「有码显示 / 无码隐藏」 |
| 模板预填 | 选运营 / 商务后勾选项与数据范围与接口返回的模板一致；之后可增减 |
| 超管 | 新建超管时不显示权限区与数据范围 |
| 保存 | 新建请求体与 4.5 一致；权限对话框整体替换；按模板重置 |
| 未知权限码 | 接口返回未知码时按原码显示，不报错 |
| 国际化 | 新键在两份语言包中一致；残留中文测试通过 |

现有测试不得删除或弱化；因角色改名必须修改的断言，在报告中逐条说明。

**浏览器验证**（结果写进报告并附截图）：连接 10a 分支的后端（或本地起 10a 分支），用超管新建一个运营、一个商务，分别登录，核对菜单与按钮。截图：用户新建对话框（选了商务模板）、权限设置对话框、商务登录后的菜单。

## 7. 文档与报告

执行报告写在 `docs/04-开发任务/10b-可配置权限-管理端-执行报告.md`：路由与按钮的权限对照、用户管理改动、测试清单、浏览器验证结果与截图、偏离与遗留。

## 8. 禁止事项与停止条件

**禁止**：修改 `admin-ui/` 以外的文件；新增依赖；改变 4.5 的接口约定；删除或弱化测试；推送 `main` 或合并 PR；使用 `push --force`、`reset --hard`。

**遇到以下情况停下来**（推送已完成的提交，以 `[WIP]` 标题创建 PR，在报告中说明）：

- 某个页面的按钮在 4.3 中找不到对应条件，也无法从设计 §3.1 推出；
- 4.5 的接口与 10a 的实际实现不一致；
- 验证在合理排查后仍失败。
