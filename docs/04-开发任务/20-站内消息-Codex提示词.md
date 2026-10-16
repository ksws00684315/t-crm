# 任务 20（后端 + 管理端）：站内消息

> 本文件是交给编码代理执行的提示词，把全文作为任务输入即可。
>
> **并行说明**：
> - 与 15b、16b、18、19a、19b 同时进行。
> - 本任务新建 `notification` 模块；其他模块只新增「发布事件」的几行代码，不改它们的业务规则。
> - 消息点击后跳转的页面由 15b、16b、19a 实现，按第 4.6 节的路由约定拼链接，不要求它们已合并。
> - 迁移脚本版本号固定为 `V202610172000`。

---

## 1. 背景

设计里有 `crm_notification`，但一直没有实现。15a 把「通知商务和超管有收款账户提交」暂时改成了待审核列表。客户在需求补充和 10-14 会议中提到要通知的事项：

- 达人提交了收款账户，要通知所属商务和超级管理员；
- 以后还有提现申请、预付申请（第三批其他任务）。

本任务建立站内消息的基础，并接入目前已有的四类事件。管理端在页头放消息铃铛。达人端暂不做站内消息。

## 2. 开始前必读

1. `AGENTS.md`、`docs/05-工程规范/`（重点：01 模块边界、03、04、05、06）。
2. 设计说明书：
   - §2.2 应用模块；
   - §3.4 多公司（跨公司处理必须显式声明）；
   - §4.11 收款账户第 2 条；
   - §4.13 商务转移；
   - §5.2 `crm_notification`；
   - §6.2 消息接口；
   - §9 定时任务（「失败时发站内消息」）。
3. 现有代码：
   - 事件的写法：`kol.api.KolStatusChangedEvent`、`system.api.StaffDisabledEvent`、`shared.TransactionalEvents`；
   - `commission` 模块 `PayoutAccountService`（新建、修改）；
   - `order` 模块 `OrderSync`（失败处理）；
   - `kol` 模块 `BdTransferService`（执行时取消、停用兜底）；
   - `system.api.BdUserApi`；
   - 管理端 `layout/`（页头）、`router/routes.ts`、`locales/`。

## 3. 执行环境与交付

- 在独立的 git 工作树中工作，分支 `task/20-notifications` 已从最新的 `main` 检出。
- **只修改 `backend/`、`admin-ui/`、你的执行报告，以及第 4.8 节列出的文档。**
- 后端：JDK 21 下 `mvn spotless:apply`、`mvn verify`、`mvn verify -Pdocker-it` 全部通过。
- 管理端：`pnpm install --frozen-lockfile && pnpm lint && pnpm typecheck && pnpm test && pnpm build && pnpm notices:check` 全部通过。
- 推送分支，按 PR 模板创建 PR，用 `gh pr checks --watch` 等 CI 通过。**不要合并 PR，不要推送 `main`。**
- **不新增依赖。**

## 4. 已定的设计决定（不要自行改变）

### 4.1 迁移 `V202610172000__create_notification.sql`

表 `crm_notification`：

| 字段 | 说明 |
|---|---|
| `id`、`tenant_id NOT NULL` | 正值雪花主键 / 所属公司 |
| `user_id NOT NULL` | 接收人（`sys_user.id`） |
| `type VARCHAR(32)` | 消息类型，CHECK 列出第 4.3 节的四种 |
| `params JSONB NOT NULL` | 渲染文字用的参数，只放编号、数量、时间等非个人信息（见第 4.3 节） |
| `biz_type`、`biz_id` | 关联业务，可空 |
| `read_time` | 已读时间，未读为空 |
| `create_time` | 创建时间 |

- 只追加、不做逻辑删除。
- 索引：
  - `(user_id, read_time, create_time DESC)`，用于未读数与列表；
  - `(tenant_id, create_time)`，用于以后清理。
- 表与字段都有中文注释。

**消息文字不入库**：只存类型和参数，由管理端按当前语言渲染。这样中英文切换后，历史消息也能正确显示。设计 §5.2 原来写的 `title`、`content` 字段，按本节修改。

### 4.2 模块与写入方式

1. 新建模块 `com.tcrm.admin.notification`，按规范 01 分包。
2. **其他模块不直接调用 notification**。它们在各自的 `api` 包中定义事件，在业务事务中发布（沿用 `TransactionalEvents`）。
3. `notification` 模块用 `@TransactionalEventListener(phase = AFTER_COMMIT)` 监听，在新事务中写入。
   - 写入时用事件中显式携带的 `tenantId` 设置公司范围，因为定时任务发布的事件也要能正确写入；
   - 写入失败只记 `WARN`，不影响原业务。
4. **接收人的解析在 notification 模块完成**：
   - 「本公司全部启用的超管」通过 `system.api` 新增的只读方法取得；
   - 「达人当前的商务」通过 `kol.api` 取得。

### 4.3 消息类型与触发点

| 类型 | 发布方与时机 | 接收人 | `params` | 点击跳转 |
|---|---|---|---|---|
| `PAYOUT_ACCOUNT_SUBMITTED` | `commission`：达人新建账户，或修改后重新提交（回到 `PENDING`） | 达人当前的商务（为空时不发）+ 全部超管 | `kolCode`、`accountType` | `/fund/payout-accounts?id={accountId}` |
| `ORDER_SYNC_FAILED` | `order`：店铺同步从「成功」变为「失败」时发一次。上次已失败、本次仍失败时不再发；恢复成功后再次失败时重新发 | 全部超管 | `shopName` | `/order/orders` |
| `BD_TRANSFER_CANCELLED` | `kol`：预约转移在执行时被取消（「执行时归属已变化」「目标商务不可用」） | 全部超管 | `kolCode`、`reason` | `/kol/bd-transfers?kolCode={kolCode}` |
| `KOLS_UNASSIGNED` | `kol`：商务停用后，名下达人转为未分配（人数 > 0 时） | 全部超管 | `bdName`、`count` | `/kol/kols`（达人列表目前没有「未分配」筛选，不在本任务中增加） |

- `params` 只放上表列出的字段，不放邮箱、收款账号、钱包地址等个人信息。
- 同一接收人只收一条：商务本人同时是超管的情况不存在，不需要处理。
- 发布事件的代码，在各自模块中各加一个单元测试。

### 4.4 接口（管理端，登录即可，只能读写本人的消息）

| 方法与路径 | 说明 |
|---|---|
| `GET /admin-api/notification/notifications` | 分页；`unread=true` 只看未读；按创建时间倒序 |
| `GET /admin-api/notification/notifications/unread-count` | `{count}` |
| `PUT /admin-api/notification/notifications/{id}/read` | 标记一条已读；别人的消息返回 404 |
| `PUT /admin-api/notification/notifications/read-all` | 全部标记已读 |

- 平台管理员没有所属公司，调用返回 403。
- 这四个接口不写操作日志。

### 4.5 管理端界面

1. **页头铃铛**（公司账号可见，平台管理员不显示）：
   - 未读数徽标，超过 99 显示「99+」；
   - 登录后立即取一次，之后每 60 秒刷新，浏览器标签页不可见时暂停，回到前台时立即刷新。
2. **点击铃铛**：弹出最近 10 条消息。
   - 每条显示图标、按语言包渲染的文字、相对时间；
   - 未读的加粗；
   - 底部有「全部已读」和「查看全部」。
3. **点击一条消息**：标记已读，并按第 4.3 节跳转。如果当前账号没有目标页面的权限，只标记已读，提示「你没有查看该页面的权限」。
4. **消息页**：路由 `/notifications`，所有公司账号可访问，不进侧边菜单。
   - 「未读 / 全部」切换、分页；
   - 「全部已读」。
5. **文字模板**：放在语言包 `notification.type.{TYPE}`，中英文都要有。例如：
   - 中文「达人 {kolCode} 提交了 {accountType} 收款账户，待审核」；
   - 英文「Creator {kolCode} submitted a {accountType} payout account for review」。

### 4.6 页面路由约定（与 15b、16b、19a 共用，不得改动）

```text
/fund/payout-accounts?id={accountId}   15b：打开该账户详情
/order/orders                          19a：订单列表
/kol/bd-transfers?kolCode={kolCode}    16b：以该达人编号筛选
/kol/kols                              已有：达人列表
```

对应页面尚未合并时，跳转到不存在的路由会显示 404，这是正常的；在报告中说明。

### 4.7 不在本任务范围

- 达人端站内消息。
- 邮件、短信等其他通知渠道。
- 提现、预付、高退款预警、复制任务失败等类型（等相应功能实现时，由对应任务新增）。
- 消息清理（数据量很小；以后需要时，按 `(tenant_id, create_time)` 清理）。

### 4.8 文档

- 设计说明书：
  - §2.2：`notification` 模块；
  - §4.11 第 2 条：收款账户提交改为发站内消息，待审核列表与数量接口保留；
  - §5.2：`crm_notification` 的实际字段（类型与参数，不存文字）；
  - §6.2：消息接口；
  - §9：订单同步失败的消息规则。

## 5. 执行步骤

1. 迁移、模块骨架、写入与接收人解析。
2. 四个事件的定义与发布（各模块）。
3. 接口。
4. 管理端铃铛、下拉、消息页、语言包。
5. 测试、文档、执行报告。

## 6. 测试要求

| 场景 | 期望 |
|---|---|
| 收款账户 | 新建与重新提交各发一次，接收人是商务与全部超管；达人未分配时只发超管；业务事务回滚时不发 |
| 订单同步失败 | 成功→失败发一次；连续失败不重复；恢复后再失败再发；在定时任务的线程中也能写入正确公司 |
| 转移 | 执行时取消发消息；手动取消不发；停用兜底的人数大于 0 时发 |
| 接口 | 只能看和标记本人的消息；别人的消息返回 404；平台管理员返回 403；未读数正确 |
| 隔离 | 消息带正确的 `tenant_id`；其他公司的用户看不到 |
| 管理端 | 徽标与 99+；轮询在标签页不可见时暂停；点击后标记已读并跳转；无权限时只标记已读；语言切换后文字随之改变 |
| 国际化 | 新键在两份语言包中一致；残留中文测试通过 |

**浏览器验证**（写进报告并附截图）：

- 本地后端：达人用接口提交一个收款账户，商务和超管的铃铛出现未读，点击跳转（15b 未合并时显示 404，在报告中说明）；
- 停用一个有达人的商务，超管收到「达人转为未分配」消息；
- 截图：铃铛下拉（中文、英文）、消息页。

## 7. 文档与报告

执行报告写在 `docs/04-开发任务/20-站内消息-执行报告.md`：迁移、模块与事件、各类型的触发规则与接收人、接口与示例、界面、测试清单、浏览器验证与截图、偏离与遗留。

## 8. 禁止事项与停止条件

**禁止**：

- 修改已合并的迁移脚本；
- 新增依赖；
- 让其他模块直接依赖 `notification` 模块；
- 改变收款账户、订单同步、商务转移的业务规则；
- 在消息参数中写入邮箱、账号、地址等个人信息；
- 修改 `kol-portal/`、`redirect/`、`deploy/`；
- 删除或弱化测试；
- 推送 `main` 或合并 PR；
- 使用 `push --force`、`reset --hard`。

**遇到以下情况停下来**（推送已完成的提交，以 `[WIP]` 标题创建 PR，在报告中说明）：

- 事件在事务提交后监听的方式，与现有某处写法冲突，需要修改 `shared` 中的公共组件；
- 设计说明书与本提示词冲突；
- 验证在合理排查后仍失败。
