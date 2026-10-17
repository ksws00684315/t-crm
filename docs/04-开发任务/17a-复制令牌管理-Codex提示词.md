# 任务 17a（后端 + 管理端）：复制令牌管理（原系统「TOKEN管理」）

> 本文件是交给编码代理执行的提示词，把全文作为任务输入即可。
>
> **执行环境**：本任务由**非本机**代理执行。自行 `gh repo clone ksws00684315/t-crm`，从最新的 `origin/main` 检出分支 `task/17a-copy-tokens`。
>
> **范围说明**：任务 17 拆为两部分。本任务（17a）只做令牌的生成、列表、复制、作废，以及给 17b 用的认证与扣减额度的内部接口。客户表格脚本调用的对外复制接口（`/admin-api/external/product-links/*`）属于 17b，要等客户确认微店复制流程（Q1）后再做，**本任务不做**。

---

## 1. 背景

原系统有一个「TOKEN管理」页：商务为达人生成 Token，交给运行客户表格脚本的人，脚本用它调用复制接口。新系统叫「复制令牌」（ADR-019）。负责人 2026-10-17 决定沿用原系统的用法：

- 独立的列表页，列出全部令牌；
- 令牌加密保存，有权限的人可以再次复制明文，每次复制记操作日志；
- 同一达人可以同时有多个有效令牌；
- 生成时填写额度和有效分钟数，有默认值。

原系统页面的列：Token、达人（名称 + 编号）、目标渠道（微店）、目标店铺（自动分配）、使用量（已用 / 额度）、有效分钟、过期时间、状态（有效 / 已用尽 / 已过期）。

## 2. 开始前必读

1. `AGENTS.md`、`docs/05-工程规范/`（重点 03、04、06）。
2. ADR-019 第 2 条（2026-10-17 修订）、ADR-016（`SecretCipher`）、ADR-018（权限码）。
3. 设计说明书：
   - §4.4「脚本复制」第 1 条；
   - §5.2 `crm_kol_copy_token`；
   - §6.2 复制令牌的四个接口。
4. 现有代码：
   - `security.api.Permission.KOL_COPY_TOKEN`（已存在）、`PostTemplate`（商务模板已包含该权限）；
   - `shared.SecretCipher`、`shared.AuditLog` 与 `AuditSanitizer`；
   - `kol` 模块的数据范围处理（商务只看名下达人）；
   - `shared/ApiCatalogConsistencyTest`（设计 §6.2 标记区间内的接口表必须与代码一致）；
   - 管理端的列表页写法（`DataTable`、`ListSearch`）、达人详情抽屉、`CopyText`。

## 3. 执行环境与交付

- 后端：JDK 21，`mvn spotless:apply`、`mvn verify`、`mvn verify -Pdocker-it` 全部通过。
- 管理端：Node 24，`pnpm install --frozen-lockfile && pnpm lint && pnpm typecheck && pnpm test && pnpm build && pnpm notices:check` 全部通过。
- 需要 JDK 21、Maven、Node 24 + pnpm、Docker。本机没有 Docker 时，在报告中写明哪些检查没跑，集成测试以 PR 上 CI 的 Backend verify 为准，CI 必须通过；浏览器验证需要本地起后端与数据库，做不到时在报告中说明，并改用 Vitest 组件测试覆盖第 5 节的界面场景。
- 本地起服务时用 `deploy/docker-compose.dev.yml`（Compose 项目名 `tcrm-17a`），验证完停掉进程并 `down -v`。测试账号与密码自己随机生成，不写入仓库和报告。
- 推送分支，按 PR 模板创建 PR，用 `gh pr checks --watch` 等 CI 通过。**不要合并 PR，不要推送 `main`。**
- **不新增依赖。** 迁移版本号固定为 `V202610181100`。

## 4. 已定的设计决定（不要自行改变）

### 4.1 表 `crm_kol_copy_token`（迁移 `V202610181100__create_kol_copy_token.sql`）

| 字段 | 说明 |
|---|---|
| `id`、`tenant_id` | 雪花主键 / 所属公司 |
| `kol_id` | 达人 |
| `token_hash` | 明文的 SHA-256（十六进制），**全局唯一**，认证时按它查找 |
| `token_cipher` | `SecretCipher` 加密的明文，供再次复制 |
| `token_hint` | 脱敏显示值，例如 `TKN-7FFB****3CDF`（前 4 位 + `****` + 后 4 位） |
| `target_channel` | 目前固定 `WEIDIAN`，CHECK 约束 |
| `target_shop_id` | 目标店铺，可空，空表示「自动分配」（规则待 Q1） |
| `quota`、`used` | 额度与已用数量，`used >= 0`、`used <= quota` |
| `valid_minutes`、`expires_at` | 有效分钟数与过期时间 |
| `status` | `ACTIVE` / `REVOKED` |
| `last_used_at` | 最近一次使用时间 |
| 通用字段 | `create_by`、`create_time`、`update_by`、`update_time`、`is_deleted` |

- 索引：`(tenant_id, kol_id, create_time DESC)`，以及列表筛选需要的索引。
- 表和字段都有中文注释。

**显示状态**由数据推出，不入库：

- 已作废：`status = REVOKED`；
- 已过期：`expires_at <= now`；
- 已用尽：`used >= quota`；
- 有效：其余情况。

判断顺序就是上面的顺序。

### 4.2 令牌格式与安全

- 明文格式：`TKN-` 加 26 位大写 Base32（128 位随机数，`SecureRandom`）。客户脚本只检查令牌非空，不校验格式。
- **日志、操作日志、异常信息中不得出现明文。** 操作日志只记录令牌 ID 和脱敏值；`AuditSanitizer` 要能过滤响应和请求中的 `token` 字段。
- 列表接口只返回 `token_hint`，不返回明文。

### 4.3 默认值与范围

- 默认额度 1,000 个，默认有效分钟数 1,440。
- 写在 `application.yml`：`crm.copy-token.default-quota`、`crm.copy-token.default-valid-minutes`。设计中提到的按公司配置（`crm_config`）等佣金任务建表时再迁移过去，本任务**不建** `crm_config`。
- 范围：额度 1～100,000；有效分钟数 5～10,080（7 天）。

### 4.4 接口（权限 `kol:copy-token`）

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/admin-api/kol/copy-tokens` | 分页列表。筛选：`hint`（脱敏值的模糊匹配）、`kolId`、`targetChannel`、`status`（ACTIVE / EXHAUSTED / EXPIRED / REVOKED，按第 4.1 节的显示状态筛）。每行：`id`、`tokenHint`、`kolId`、`kolCode`、`kolName`、`targetChannel`、`targetShopId`、`targetShopName`（空表示自动分配）、`used`、`quota`、`validMinutes`、`expiresAt`、`displayStatus`、`lastUsedAt`、`createTime`。按页批量查询达人和店铺名称。默认按创建时间倒序 |
| POST | `/admin-api/kol/copy-tokens` | 生成：`{kolId, quota?, validMinutes?, targetShopId?}`，缺省取默认值。达人必须启用；`targetShopId` 必须是本公司启用的店铺。响应包含一次明文 `token` 和列表行字段。写操作日志 |
| POST | `/admin-api/kol/copy-tokens/{id}/reveal` | 取回明文 `{token}`，用于复制。写操作日志（记录 ID 与脱敏值，不记明文） |
| DELETE | `/admin-api/kol/copy-tokens/{id}` | 作废，状态改为 `REVOKED`；已作废的再次作废返回 409。写操作日志 |

**数据范围**：

- 「本人」范围的账号（商务）只能看到、生成、复制、作废名下达人的令牌；
- 超管和「全部」范围的账号可以操作本公司全部令牌；
- 别人的令牌返回 404。

**设计说明书同步**：实现后把这四个接口登记到设计 §6.2 的 `<!-- api-catalog:admin -->` 标记区间内（表格格式按区间内已有行），删掉区间外原来那几行说明，否则 `ApiCatalogConsistencyTest` 会失败。

### 4.5 给 17b 用的内部接口（`kol.api`）

```java
public interface CopyTokenApi {
  /** 按明文认证。只认有效令牌；无效、过期、用尽、作废或达人停用时抛出对应错误码。不需要登录态。 */
  CopyTokenGrantDTO authenticate(String token);

  /** 原子扣减额度；剩余不足时不扣，返回 false。同时更新 last_used_at。 */
  boolean consume(long tokenId, int count);
}
```

- `CopyTokenGrantDTO` 包含：`tokenId`、`tenantId`、`kolId`、`targetChannel`、`targetShopId`、`remaining`。
- `authenticate` 在没有登录态的请求中调用，公司由令牌决定：先在系统作用域按 `token_hash` 查到行（写明跨公司的理由），之后的操作在该公司作用域中进行。
- `consume` 用一条带条件的 `UPDATE` 实现（`status = 'ACTIVE' AND expires_at > now AND used + :count <= quota`），并发下不能超额。
- 错误码：`COPY_TOKEN_INVALID`（401）、`COPY_TOKEN_EXPIRED`（401）、`COPY_TOKEN_QUOTA_EXCEEDED`（429）、`KOL_DISABLED`（403，没有就新增）。登记在 `ErrorCode`，管理端语言包补文案。
- 本任务没有调用方，只写单元测试和集成测试。

### 4.6 管理端页面

1. **菜单与路由**：放在「商品」分组下（与原系统「商品管理 > TOKEN管理」的位置一致），名称「复制令牌」，路由 `/product/copy-tokens`，权限 `kol:copy-token`。
2. **筛选**：令牌（脱敏值）、达人（按编号或名称搜索选择）、目标渠道、状态。
3. **列**：
   - 令牌：脱敏值，旁边有「复制」按钮，点击调用 `reveal` 再写入剪贴板；
   - 达人：名称 + 编号标签；
   - 目标渠道、目标店铺（空显示「自动分配」）；
   - 使用量 `已用 / 额度`；
   - 有效分钟、过期时间（本地时区）；
   - 状态标签：有效 / 已用尽 / 已过期 / 已作废；
   - 操作：作废（二次确认；只对有效、已用尽、已过期的显示）。
4. **生成令牌对话框**（工具栏「生成令牌」按钮）：
   - 达人（搜索选择，必填）、额度、有效分钟数（预填默认值）、目标店铺（下拉，默认「自动分配」）；
   - 成功后弹出结果框，显示完整令牌和「复制」按钮，并提示「可在列表中再次复制」。
5. **达人详情抽屉**：增加「生成复制令牌」入口，打开同一个对话框，达人已选好。
6. 中英文语言包都要有；英文术语用 Creator（任务 24）。

## 5. 测试要求

| 场景 | 期望 |
|---|---|
| 生成 | 默认值与范围校验；达人停用、店铺不属于本公司时拒绝；明文格式正确；库中只有哈希与密文 |
| 列表 | 四种显示状态的推导与筛选；只返回脱敏值；批量查询名称 |
| 复制与作废 | `reveal` 返回的明文可以通过 `authenticate`；作废后认证失败；重复作废 409 |
| 数据范围 | 商务看不到、动不了其他商务名下达人的令牌（404）；跨公司隔离 |
| 认证 | 无效、过期、用尽、作废、达人停用分别返回对应错误码 |
| 扣减 | 剩余不足时不扣；并发扣减（如 20 个线程同时扣）不超额（集成测试） |
| 日志 | 操作日志、应用日志中没有明文（测试中检查操作日志记录） |
| 接口清单 | `ApiCatalogConsistencyTest` 通过 |
| 管理端 | 列表、筛选、复制（调用 `reveal`）、作废确认、生成对话框与结果框、达人详情入口、权限隐藏 |

**浏览器验证**（写进报告并附截图）：

- 超管生成令牌、复制、作废；
- 商务只能看到名下达人的令牌；
- 状态显示（可以手动改数据库造出「已用尽」「已过期」）；
- 英文界面。

## 6. 执行报告

写在 `docs/04-开发任务/17a-复制令牌管理-执行报告.md`：迁移、接口与示例、安全处理（明文只在哪里出现）、测试清单、浏览器验证与截图、偏离与遗留（含给 17b 的说明）。

## 7. 禁止事项与停止条件

**禁止**：

- 实现对外复制接口（17b）；
- 新增依赖，新建 `crm_config`；
- 在日志、操作日志、异常、列表接口中出现令牌明文；
- 修改已合并的迁移脚本；
- 删除或弱化测试，降低覆盖率门槛；
- 推送 `main`、合并 PR；
- 使用 `push --force`、`reset --hard`、改写已推送历史。

**遇到以下情况停下来**（推送已完成的提交，以 `[WIP]` 标题创建 PR，在报告中说明）：

- 设计说明书、ADR-019 与本提示词冲突；
- 数据范围规则在现有代码中无法按第 4.4 节实现；
- 合理排查后验证仍失败。

完成后回复一段总结：PR 链接、做了什么、测试结果、遗留。
