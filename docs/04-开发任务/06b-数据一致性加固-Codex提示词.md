# 任务 06b：数据一致性加固

> 本文件是交给编码代理执行的提示词。直接把本文件全文作为任务输入即可。
> **前置条件：PR #24（认证、会话与店铺上限加固）已合并到 `main`。** 未合并时停止，不要开始。

---

## 1. 背景

M1 完成后做了一轮横向安全审查（认证鉴权、事务、缓存一致性），并由多个模型交叉复核（见 PR #24 的评论）。认证与会话相关的问题已在 PR #24 修复；本任务处理剩余的数据一致性问题，**不增加业务功能**。

| # | 问题 | 现状 |
|---|---|---|
| A | 链接索引：全量重建会覆盖期间已写入的新映射 | 重建读到旧数据 → 增量写入新数据 → 重建 `RENAME` 把旧数据换回去，并一直保持到下次重建 |
| B | 链接索引：增量更新失败只记日志 | 没有重试与告警，最长约 24 小时不一致：停用的达人仍可跳转，新映射可能跳到回退页 |
| C | 链接索引：停用店铺在请求线程中同步刷新该店铺的全部链接 | 设计容量下可达千万级 field，请求会被长时间占用 |
| D | 原商品与专属商品"互斥"在并发下可被绕过 | 原商品导入与对应关系导入同时处理同一 `(店铺, 商品 ID)` 时，可能同时成功 |
| E | 重复点击判定依赖 60 秒 TTL 的 Redis 缓存 | 消费端暂停超过 60 秒时，实际 10 秒内的重复点击会被算作有效 |
| F | 操作日志没有 180 天清理 | 规范 06 第 4 节要求到期由定时任务清理 |
| G | `/t/` 没有限速 | 刷量会虚增点击，并可能让 Stream 近似裁剪掉尚未消费的真实点击 |
| H | IP 哈希盐只校验非空 | 弱盐泄露后，点击日志的 IP 哈希可被离线枚举 |
| I | 事件必须在事务内发布的约束没有固化 | `@TransactionalEventListener` 在事务外发布时会被静默丢弃 |

## 2. 开始前必读

1. `AGENTS.md`、`docs/05-工程规范/` 全部文件
2. 设计说明书 §3.3、§4.5、§5.2（`crm_kol_item`、`crm_click_log`）、§7.4、§9；ADR-012
3. 现有代码：`link` 模块全部（`LinkIndexService`、`LinkIndexListener`、`LinkIndexStore`、`LinkRebuildService`、`ClickConsumer`、`ClickStream`、`ClickStreamMonitor`）；`product.service.ProductImportProcessor`、`MappingImportProcessor`；`kol`、`product` 中发布 `*ChangedEvent` 的位置；`shared.DistributedLocks`；`system` 模块的操作日志；`redirect/` 的 `RedirectService`；`deploy/nginx/`
4. PR #24 的三条审查评论（问题来源与讨论）

## 3. 执行环境与交付

- 执行机不能运行 Docker；`*IT` 由 CI 和验证机运行，本地运行 `mvn verify`。
- 从最新的 `main` 创建分支 `task/06b-consistency-hardening`，按下面的步骤分别提交。
- 完成后推送分支，`gh pr create` 按 PR 模板填写，`gh pr checks --watch` 等待 CI 全部通过。**不要合并 PR，不要推送 `main`。**
- 提交遵循规范 08，末尾加执行代理自己的 `Co-Authored-By` 行。
- **每个问题先写能复现的测试，确认在修改前失败**（规范 05）；并发问题用行锁、咨询锁或 `pg_locks` 等待固定执行顺序（参照 `SystemIT.runAfterQueuedOnRowLock`、`KolIT.shouldRejectBdEdit_…`），不要依赖 `sleep` 碰运气。

## 4. 已定的设计决定（不要自行改变）

### 4.1 链接索引改为"事务内登记 + 后台可靠同步"（A、B、C）

**登记（outbox）**

- 新表 `crm_link_index_outbox`（`link` 模块）：`id`、`scope`（`KOL` / `PRODUCT` / `SHOP` / `MAPPING`）、`scope_id`、`product_id`（仅 `MAPPING` 使用，其余为空）、`attempts`、`next_attempt_at`、`last_error`（截断到 500 字符，英文）、`create_time`。只增删，省略修改字段与逻辑删除（表注释写明）。
- 现有四个 `*ChangedEvent` 的监听器改为 `@TransactionalEventListener(phase = BEFORE_COMMIT)`：只往 outbox 插一行，与业务数据在**同一事务**提交；业务回滚则登记一起回滚。`product`、`kol` 模块不感知 outbox，模块依赖方向不变。
- 不再在 `AFTER_COMMIT` 或请求线程中直接写 Redis。

**同步（worker）**

- `link` 模块新增同步任务：固定间隔 2 秒运行，通过 `shared.DistributedLocks` 保证全局只有一个实例在处理；每次取到期的（`next_attempt_at <= now`）最多 200 行。
- 先把本批合并到"受影响的达人"：`KOL` → 该达人；`MAPPING` → 该达人的这一个原商品；`PRODUCT` / `SHOP` → 在该原商品 / 店铺下有当前映射的全部达人（分页查询，不一次性载入）。
- **按达人处理**，每个达人：取得**该达人的锁**（Redisson，键名 `link:index:kol:{kolId}`）→ 在锁内**重新读取数据库最新状态** → 写 Redis → 释放锁。`MAPPING` 只刷新对应 field，其余刷新该达人的全部 field（与现有 `refreshKol` 相同，pipeline）。
- 成功后删除对应的 outbox 行；失败则 `attempts + 1`、写入 `last_error`、`next_attempt_at` 按 10 秒、30 秒、1 分、5 分、10 分退避（之后固定 10 分钟），**不丢弃**；每次失败记 `ERROR`。
- 应用启动后 worker 正常运行，重启前未处理的 outbox 行会继续处理。

**全量重建**

- `rebuildAll` 改为逐个达人：取得**同一把达人锁** → 在锁内读取该达人的最新映射 → 写临时 key → `RENAME` → 释放锁。这样重建与增量对同一个达人串行执行，重建不会再覆盖已提交的新数据。
- 其余行为不变（每天 04:30、超管手动触发、清理多余的 `link:*` 与 staging key）。

**监控**

- 现有每分钟的检查（`ClickStreamMonitor` 或新增同级组件）增加：outbox 中最早一条未处理记录的等待时间超过 1 分钟记 `WARN`，超过 10 分钟记 `ERROR`；任一行 `attempts >= 5` 记 `ERROR`（附 scope 与 id）。

**写入延迟**：业务提交后链接索引在数秒内更新（不再是同步写入）。在设计说明书 §7.4 和执行报告中写明；管理端无需改动。

**ADR**：新增 `docs/06-架构决策/ADR-013-链接索引采用事务内登记与后台同步.md`（背景：PR #24 审查发现的 A、B、C；决定；理由；影响，包括写入延迟与 outbox 积压监控），并在 ADR-012 的"影响"中补一句：索引遗漏或陈旧由 ADR-013 的机制保证在数秒到数分钟内修正，不再依赖每日重建。

### 4.2 原商品与专属商品互斥（D）

- 原商品导入新增商品、对应关系导入建立映射这两条写入路径，在各自的写事务中先取得同一把**事务级咨询锁**，键由 `(shop_id, weidian_item_id)` 确定（例如 `pg_advisory_xact_lock(hashtextextended('crm:shop-item:' || shop_id || ':' || weidian_item_id, 0))`），**取锁后在同一事务内重新检查**对方表（"已是原商品" / "已是当前专属商品"），再写入。原先在事务外的检查可以保留作为快速失败，但最终判断以锁内为准。
- 不使用触发器、外键或跨表约束（规范 04）。
- 键的格式在 `ProductRules`（或同等位置）集中定义，并在注释中登记"本项目的咨询锁键"清单（含 PR #24 的 `crm_shop:create`）。

### 4.3 重复点击判定（E）

- 规则不变（§4.5）：同一 `kol_item_id` + `ip_hash`，与上一次点击的事件时间相差小于 10 秒即为重复。
- Redis 中的 `click:last:*` 仅作加速。对本批中**缓存未命中**的键，用**一条**查询从 `crm_click_log` 取每个键在 `[本批最早事件时间 - 10 秒, 本批最晚事件时间]` 内的最近一次点击时间（按 `kol_id`、`product_id`、`click_time` 走现有索引，再按 `kol_item_id`、`ip_hash` 过滤），再与批内顺序结合判断。不逐条查询。
- 判定结果与消费暂停多久无关。

### 4.4 操作日志 180 天清理（F）

- `system` 模块新增定时任务：每天 03:45（UTC），加分布式锁，删除 `oper_time` 早于 180 天前的 `sys_oper_log`，每批最多 10,000 行、循环直到删完，每批独立事务；删除总数记 `INFO`。
- 保留天数写为常量（规范 06 第 4 节），不做成配置。

### 4.5 `/t/` 限速（G）

- `deploy/nginx`：为 `t.` 站点的 `location /t/` 增加按客户端 IP 的 `limit_req`（新 zone，`rate=30r/s`、`burst=60`、`nodelay`、`limit_req_status 429`）。阈值较宽松，是为了不误伤移动网络中大量用户共用出口 IP（CGNAT）的情况。
- `deploy/README.md` 写明：边缘层（Cloudflare 频率限制规则）应作为主要防线，Nginx 限速为兜底；阈值可按实际流量调整。

### 4.6 IP 哈希盐强度（H）

- 跳转服务启动时要求 `TCRM_IP_HASH_SALT` 至少 32 个字符，否则启动失败并提示生成方式（`openssl rand -hex 32`）。
- `deploy/.env.example` 的注释同步写明长度要求与生成方式。

### 4.7 事件只能在事务内发布（I）

- `shared` 新增一个发布工具（例如 `TransactionalEvents.publish(ApplicationEventPublisher, Object)`）：当前没有活动事务（`TransactionSynchronizationManager.isActualTransactionActive()` 为 false）时抛出 `IllegalStateException`。
- 把现有 `*ChangedEvent` 的发布改为经过它。
- `ArchitectureTest` 增加规则：除该工具外，任何类不得直接调用 `ApplicationEventPublisher.publishEvent`。

## 5. 执行步骤

| 步骤 | 内容 | 提交 |
|---|---|---|
| 1 | 迁移：`crm_link_index_outbox`（一个脚本只做一件事） | `feat(backend): add link index outbox table` |
| 2 | 第 4.7 节事件发布工具与架构规则 | `refactor(backend): publish change events only inside transactions` |
| 3 | 第 4.1 节 outbox 登记、worker、达人锁、重建改造、监控、ADR-013 | `fix(backend): sync the link index through a transactional outbox` |
| 4 | 第 4.2 节互斥锁 | `fix(backend): serialize original and exclusive item claims` |
| 5 | 第 4.3 节去重回查 | `fix(backend): fall back to the click log for duplicate detection` |
| 6 | 第 4.4 节操作日志清理 | `feat(backend): purge operation logs after 180 days` |
| 7 | 第 4.5、4.6 节部署与跳转服务 | `fix(deploy): rate-limit redirects` / `fix(redirect): require a strong IP hash salt` |
| 8 | 文档与报告 | `docs: ...` |

## 6. 测试要求

按规范 05，覆盖率不得低于门槛。至少包括：

- **A**（`LinkIT`）：固定顺序复现"重建读到旧映射 → 增量提交新映射 → 重建替换"，修改后最终 Redis 为新映射；修改前该测试失败。
- **B**：Redis 写入失败（可用故障注入或临时让写入抛异常）后 outbox 行保留、`attempts` 增加、`next_attempt_at` 退避；恢复后被处理并删除；业务事务回滚时不产生 outbox 行。
- **C**：停用店铺的接口在 outbox 登记后立即返回，不在请求线程写 Redis；worker 处理后该店铺下的链接 `status=0`。
- **D**（`ProductIT`）：用咨询锁或行锁固定顺序，原商品导入与对应关系导入并发处理同一 `(店铺, 商品 ID)`，最终只有一方成功；修改前两方都成功。
- **E**：两条事件时间相差 3 秒的点击分两批消费，中间删除 `click:last:*`（模拟超过 60 秒的暂停），第二条仍为重复；修改前为有效。
- **F**：早于 180 天的日志被删除，180 天内的保留（时间边界）。
- **H**：盐短于 32 个字符时跳转服务启动失败。
- **I**：事务外调用发布工具抛异常；架构规则能拦住直接调用 `publishEvent`。
- **性能**：2 万行对应关系导入的耗时，以及导入完成到链接索引全部可用的时间，写入执行报告（验证机测量亦可）；后者应在 1 分钟内。

## 7. 文档与报告

1. 设计说明书：§4.5（去重回查）、§7.4（outbox、写入延迟、达人锁、监控）、§9（新增 worker 与操作日志清理任务）、§5.2（新表）。
2. `docs/04-开发任务/README.md`：任务 06b 行改为实际状态。
3. 执行报告 `docs/04-开发任务/06b-数据一致性加固-执行报告.md`：结论；每个问题的复现与修复；第 6 节性能数据；CI 结果与 PR 链接；偏离与遗留事项。

## 8. 禁止事项与停止条件

- 不增加业务功能；不修改 `docs/05-工程规范/`；不修改已合并的迁移脚本；不降低覆盖率门槛，不放宽 ArchUnit 规则，不 `@Disabled` 测试。
- 不推送 `main`，不合并 PR，不 `push --force`，不改写已推送历史。

出现以下情况时停止，推送已完成的提交，以 `[WIP]` 标题创建 PR，并在报告中说明：

- 第 4 节的设计决定与现有代码或规范冲突，无法按原样实现；
- 导入完成到链接索引全部可用的时间明显超过 1 分钟；
- 需要新增依赖；
- CI 在合理排查后仍无法通过。
