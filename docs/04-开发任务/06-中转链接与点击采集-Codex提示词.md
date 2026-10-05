# 任务 06：中转链接、Redis 链接索引与点击采集

> 本文件是交给编码代理执行的提示词。直接把本文件全文作为任务输入即可。
> **前置条件：任务 05（店铺、原商品与专属商品对应关系，PR #22）已合并到 `main`。** 未合并时停止，不要开始。

---

## 1. 背景

M1 里程碑的最后一个任务。本任务交付后，可以完整演示：录入达人 → 导入对应关系 → 在管理端拿到中转链接 → 打开链接跳转到微店专属商品 → 管理端看到点击计数。

已有的部分：

- 跳转服务 `redirect/`：读 `HASH link:{kolCode}`（field 为原商品编码，value 为 JSON `{"kolItemId","targetUrl","status"}`），命中则 302 并 `XADD stream:click`，未命中或非 JSON 则跳到回退地址 `TCRM_REDIRECT_FALLBACK_URL`（见设计说明书 §4.5、§7.4，`LinkEntry`、`RedirectService`、`ClickRedis`）。
- 对应关系 `crm_kol_item`（任务 05），达人状态、原商品状态。

本任务做的是**写入方与消费方**：后台 `link` 模块维护 Redis 链接索引、消费点击流写入点击日志、在管理端展示中转链接和点击数。

**本任务明确不做**（不要实现，也不要预留字段）：

- Google 表格（任务 07）；
- 点击汇总表与看板（统计任务）；
- `track_id`、在目标地址上追加任何参数（微店链接能否带参数待 M0 确认，见第 4.2 节）；
- `crm_kol_item.target_url` 列（目标地址由规则计算，不存库）；
- 跳转服务未命中时回源后台（见第 4.4 节，本任务删除该设想）；
- 达人端展示链接、导出链接。

## 2. 开始前必读

1. `AGENTS.md`
2. `docs/05-工程规范/` 全部文件，重点：01（模块依赖、Spring 事件）、02（定时任务必须加分布式锁）、04（分区表、索引）、05、06（配置必填、IP 只存哈希）
3. 设计说明书：§4.4 第 1 步、§4.5 点击跳转、§5.2 `crm_click_log`、§6.4、§7.4 链接索引、§9 定时任务
4. 现有代码：`redirect/` 全部；后台 `product`、`kol` 模块（任务 04、05），尤其 `kol.api`、对应关系的写入位置（导入与更换专属商品）、原商品与达人的状态修改；`shared.DistributedLocks`；`task.service.TaskFileRetentionJob`（定时任务写法参照）
5. 任务 05 执行报告中 1,000 万行对应关系的实测数据

设计说明书与规范冲突时以规范为准。完成后同步修改设计说明书 §4.4 第 1 步、§4.5、§5.2 `crm_click_log`、§6.2（链接相关接口）、§7.4、§9 中与本任务相关的内容。

## 3. 执行环境与交付

- 执行机不能运行 Docker；`*IT` 由 CI 和验证机运行，本地运行 `mvn verify`。
- 从最新的 `main` 创建分支 `task/06-links-and-clicks`，按下面的步骤分别提交。
- 完成后推送分支，`gh pr create` 按 PR 模板填写，`gh pr checks --watch` 等待 CI 全部通过。**不要合并 PR，不要推送 `main`。**
- 提交遵循规范 08，末尾加执行代理自己的 `Co-Authored-By` 行。

## 4. 已定的设计决定（不要自行改变）

### 4.1 中转链接

- 格式：`{TCRM_LINK_BASE_URL}/t/{达人编号}/{原商品编码}`，例如 `https://t.example.com/t/KOL0001/P000123`。
- 新增**必填**配置 `TCRM_LINK_BASE_URL`（https 地址，不带末尾斜杠）：缺失或格式不对时后台启动失败并提示变量名（规范 06 第 5 节）。同步加入 `deploy/.env.example`（附注释）和生产 compose 的 `crm-admin` 环境变量；本地开发与测试配置中给出示例值。
- 链接只对**当前有效的对应关系**存在；同一达人同一原商品的链接地址永远不变（更换专属商品后地址不变、目标变化，§4.9）。

### 4.2 目标地址

- 由专属商品 ID 计算：`https://weidian.com/item.html?itemID={weidian_item_id}`，模板写为常量，不存库、不做成配置项。
- 不追加 `track_id` 或任何其他参数。链接格式与"商品不存在"问题待 M0 确认（报价单"待确认事项"），确认后由后续任务调整常量。

### 4.3 Redis 链接索引（写入方）

- 结构不变（§7.4）：`HASH link:{kolCode}`，field = 原商品编码，value = `{"kolItemId":"<crm_kol_item.id>","targetUrl":"<目标地址>","status":1|0}`。跳转服务的解析规则不改。
- `status` 规则：达人启用、原商品在售、店铺启用时为 `1`；否则为 `0`（跳转服务按未命中处理，跳到回退地址）。**停用或下架时写 `0`，不删除 field**，避免跳转服务把它当成不存在的链接。
- 对应关系失效且该达人该原商品没有新的有效映射时，删除该 field。
- **增量更新**：以下变更提交后（`@TransactionalEventListener(phase = AFTER_COMMIT)`）更新受影响的 field：
  - 对应关系新增、更换专属商品、失效（`product` 模块）；
  - 原商品上下架（`product`）：影响该商品的全部有效映射（最多约 500 条）；
  - 店铺启停（`product`）：影响该店铺的全部有效映射；
  - 达人启停（`kol`）：影响该达人的全部有效映射（最多 2 万条，使用 pipeline）。
- 事件按规范 01 定义在发布方的 `api` 包中（如 `product.api.KolItemChangedEvent`、`kol.api.KolStatusChangedEvent`），`link` 依赖它们；`product`、`kol` 不得依赖 `link`。
- 增量更新失败（Redis 不可用等）只记 `ERROR` 日志，不影响业务事务；由第 4.4 节的全量重建兜底。
- **全量重建**：
  - 按达人逐个重建：在临时 key 中写完该达人的全部 field 后，用 `RENAME` 原子替换 `link:{kolCode}`；没有有效映射的达人删除其 key。不存在于数据库中的 `link:*` key 一并删除。
  - 每天 04:30（UTC）执行一次；`SUPER_ADMIN` 也可以通过接口手动触发（202；已有重建在执行时返回 409，新增错误码）。
  - 通过 `shared.DistributedLocks` 保证同一时间只有一个重建在执行；开始、结束、处理的达人数与 field 数写 `INFO` 日志。
  - 应用启动后**不**自动重建。

### 4.4 跳转服务不回源（删除原设想）

设计说明书 §4.5 写的"Redis 未命中时调用 `crm-admin` 内部接口回源并回填缓存"不实现：

- 索引由增量更新加每日全量重建保证完整，未命中即表示链接不存在；
- 回源会让任意随机路径都打到后台数据库，放大恶意请求，并引入新的内部接口与共享密钥。

本任务删除 `RedirectService` 中的 `TODO(task-01)`（改为说明未命中即跳到回退地址），修改设计说明书 §4.5，并新增 `docs/06-架构决策/ADR-012-跳转服务不回源后台.md`（背景、决定、理由、影响，格式参照已有 ADR）。

### 4.5 点击采集（消费方）

- **点击日志表 `crm_click_log`**（§5.2）：`id`、`click_time`、`kol_id`、`product_id`、`kol_item_id`、`ip_hash CHAR(64)`、`ua VARCHAR(512)`、`referer VARCHAR(512)`、`country VARCHAR(8)`、`is_bot BOOLEAN`、`is_dup BOOLEAN`。只追加，省略修改字段与逻辑删除（表注释写明）。
  - 按 `click_time` 声明式分区（`PARTITION BY RANGE`，每月一个分区，分区名 `crm_click_log_yyyymm`）；主键 `(id, click_time)`。
  - 索引：`(kol_item_id, click_time)`（第 5 节点击数查询使用），在分区父表上创建。
  - 迁移中创建当月和下月两个分区。
- **分区维护定时任务**：每天 00:10（UTC）执行（加分布式锁），确保当月与之后两个月的分区存在（幂等）；删除 13 个月之前的分区（先 `DETACH` 再 `DROP`）。设计说明书 §9 的"每月 1 日"改为"每天，幂等"。
- **消费**：
  - 消费组 `crm-admin`，常驻消费 `stream:click`，每次最多 500 条（§9），阻塞读取最长 2 秒；
  - 同一时间只有一个后台实例在消费（分布式锁；拿不到锁的实例定期重试）；
  - 写库成功后 `XACK`；处理异常的批次不 ACK，下次从待处理列表（PEL）重新读取。重复投递不得产生重复点击：`id` 由 Stream 消息 ID 确定性生成（例如毫秒时间戳与序号组合成正数 BIGINT），插入时 `ON CONFLICT DO NOTHING`；
  - `kolItemId` 查不到对应关系（数据异常）时：记 `WARN` 日志、ACK 该条、不写库。
- **字段来源**：`click_time` 取事件中的 `ts`（毫秒）；`kol_id`、`product_id` 由 `kolItemId` 查 `crm_kol_item` 得到（批内去重查询，不逐条查）；`ip_hash`、`ua`、`referer`(`ref`)、`country` 直接取自事件，按列长度截断。
- **机器人判定**（`is_bot`）：UA 为空，或（不区分大小写）包含以下任一关键字：`bot`、`spider`、`crawl`、`facebookexternalhit`、`facebookcatalog`、`twitterbot`、`slackbot`、`whatsapp`、`telegrambot`、`discordbot`、`linkedinbot`、`pinterest`、`tiktok`、`bytespider`、`preview`、`headless`。关键字列表写为常量，集中一处。
- **重复点击判定**（`is_dup`）：同一 `kol_item_id` + `ip_hash`，与上一次点击的 `click_time` 相差小于 10 秒。以事件时间而不是处理时间判断；上一次点击时间存 Redis（`click:last:{kolItemId}:{ipHash}`，TTL 60 秒），批内按 Stream 顺序处理。
- **积压监控**：每分钟检查一次消费组的积压长度（`XINFO GROUPS` 的 `lag`；不可用时用 `XLEN` 与 `XPENDING` 估算）。超过 `TCRM_CLICK_STREAM_MAXLEN` 的 50% 时记 `WARN`，超过 80% 时记 `ERROR`（站内消息由后续任务接入）。

### 4.6 规模与验收

- **Redis 内存（负责人已决定，不要修改 value 格式或 Redis 配置）**：
  - 负责人实测（Redis 7.4.5，本任务的 value 格式，每位达人一个 hash）：200 万个 field 占 328MB，1,000 万个 field 占 1.60GB，约每 100 万个 165MB。
  - 生产模板的 `maxmemory 1280mb` 可容纳约 700 万个 field（约 350 位达人）。合同规模 100 位达人（200 万个 field）远低于上限；超过约 350 位达人时，需要把 Redis 上限调到 2GB 以上并相应增加服务器内存。
  - 已评估并放弃的方案：去掉 `targetUrl`（1,000 万约 1.3GB）、每位达人拆成小 hash 使用 listpack 编码（约 1.18GB）。两者都不能把设计容量降到上限以内，还会改变跳转服务的读取规则。
  - 把以上容量线写入设计说明书 §7.4（替换原来"200 万条约占 400～600MB"的估算）。
  - 在报告中复核 200 万个 field 时的 `used_memory`（本地 Redis 写合成数据，或写成测试脚本交给验证机）。
- **Redis 内存监控**：第 4.5 节的积压检查同时读取 `INFO memory` 的 `used_memory` 与 `maxmemory`；占用超过上限的 70% 记 `WARN`，超过 85% 记 `ERROR`；`maxmemory` 为 0（未设置上限，如本地开发）时跳过。
- 全量重建 200 万个 field 的耗时写入报告（验证机测量亦可）。
- 点击消费吞吐：设计容量 100 万次/天（约 12 次/秒，峰值按 20 倍计约 240 次/秒）；单批 500 条的写库耗时写入报告。

### 4.7 权限

| 操作 | SUPER_ADMIN | OPERATOR + ALL | OPERATOR + OWN（商务） | FINANCE |
|---|:-:|:-:|:-:|:-:|
| 查看达人的中转链接与点击数 | ✓ | ✓ | 仅本人负责的达人 | ✓ |
| 手动触发全量重建 | ✓ | — | — | — |

商务需要把链接发给自己负责的达人，因此可以查看本人负责达人的链接；数据范围在 service 层强制过滤，越权返回 403（规范 06）。

## 5. 接口

全部在 `/admin-api` 下，遵循规范 03；每个接口显式声明角色。

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/link/links` | 中转链接列表。**`kolCode` 必填**（缺失返回 400）；可选 `productCode`、分页；按原商品编码升序。每行返回：原商品编码与名称、专属商品 ID、中转链接、目标地址、状态（可用 / 不可用及原因：达人停用、商品下架、店铺停用）、近 30 天点击数（全部 / 有效 = 排除 `is_bot` 与 `is_dup`） |
| POST | `/link/index-rebuilds` | 手动触发全量重建，202；正在执行时 409 |

点击数只统计当前页的链接（按 `kol_item_id IN (...)` 与 `click_time` 范围查询，使用第 4.5 节的索引），不对整个达人做全量聚合。

## 6. 执行步骤

### 第 1 步：数据库迁移

新增迁移脚本（不修改已有脚本），一个脚本只做一件事：`crm_click_log` 分区父表、索引与当月 / 下月分区。

提交：`feat(backend): add partitioned click log table`

### 第 2 步：链接索引写入

`product.api` / `kol.api` 中的变更事件及发布、`link` 模块的增量更新与全量重建、`TCRM_LINK_BASE_URL` 配置、手动重建接口。

提交：`feat(backend): maintain Redis link index from mappings`

### 第 3 步：点击采集

点击流消费、机器人与重复判定、分区维护任务、积压监控。

提交：`feat(backend): consume click stream into partitioned click log`

### 第 4 步：跳转服务与 ADR

删除 `TODO(task-01)`；新增 ADR-012；修改设计说明书 §4.5。跳转服务的行为不变，现有测试应继续通过。

提交：`docs: record that the redirect service never falls back to the backend`（若包含 `redirect/` 代码注释改动，用 `refactor(redirect): ...` 单独提交）

### 第 5 步：管理端页面

1. 在达人管理页的操作列增加"链接"，打开该达人的中转链接列表（抽屉或独立页面均可，带 `kolCode` 参数）；角色与数据范围按第 4.7 节。
2. 列表：原商品编码、名称、专属商品 ID、中转链接（带"复制"按钮）、状态、近 30 天点击数（全部 / 有效）；按原商品编码筛选；分页。
3. `SUPER_ADMIN` 在对应关系页或系统页面提供"重建链接索引"按钮（确认后调用接口，提示"已开始重建"）。
4. 补充新错误码的中文文案。

提交：`feat(admin-ui): show kol links with click counts`

### 第 6 步：部署配置

`deploy/.env.example` 与 `deploy/docker-compose.prod.yml` 加入 `TCRM_LINK_BASE_URL`；部署说明中写明它应为 `https://t.<域名>`。

提交：`feat(deploy): pass link base URL to the backend`

### 第 7 步：测试

按规范 05 编写，覆盖率不得低于门槛：

- 单元测试：链接与目标地址拼装；`status` 规则（达人、商品、店铺状态的组合）；机器人关键字；重复判定的时间边界（9,999 毫秒与 10,000 毫秒）；Stream 消息 ID 到点击 `id` 的转换；分区名与需要创建 / 删除的分区计算（含跨年）；积压阈值与 Redis 内存阈值（含 `maxmemory` 为 0）。
- 接口切片测试：第 4.7 节权限表每一格；`kolCode` 缺失返回 400；重建进行中返回 409。
- 集成测试（`*IT`，`@Tag("docker")`，PostgreSQL + Redis）：
  - 导入对应关系后 Redis 中出现对应 field，JSON 与跳转服务的 `LinkEntry` 解析规则一致（可直接用相同格式断言）；
  - 达人停用、商品下架、店铺停用后 `status` 变为 0，启用后恢复；更换专属商品后 `targetUrl` 更新、链接 field 不变；
  - 全量重建能修复被手动删除或篡改的 field，并删除多余的 `link:*` key；
  - 向 `stream:click` 写入事件（含机器人 UA、10 秒内重复、重复投递的同一消息 ID），消费后 `crm_click_log` 的行与标记正确、无重复行、消息已 ACK；
  - 分区维护创建未来分区、删除 13 个月前的分区。
- 前端（Vitest）：链接列表按角色显示、复制按钮、重建按钮仅超管可见。

### 第 8 步：文档与报告

1. 设计说明书：按第 2 节同步。
2. `docs/04-开发任务/README.md`：任务 06 行改为实际状态。
3. 执行报告 `docs/04-开发任务/06-中转链接与点击采集-执行报告.md`：结论；每步改动与验证结果；第 4.6 节的测量数据；CI 结果与 PR 链接；偏离与遗留事项；需要 M0 确认的事项（至少包括：微店商品链接格式与能否带参数；机器人关键字是否需要客户补充；"商品暂不可用"回退页面的内容与地址）。

## 7. 禁止事项

- 不实现第 1 节"明确不做"中的内容。
- 不修改 `docs/05-工程规范/`；对规范有异议时写进报告。
- 不修改已合并的迁移脚本；不降低覆盖率门槛，不放宽 ArchUnit 规则，不 `@Disabled` 测试。
- 不修改跳转服务的跳转与点击写入行为（第 4 步只删 TODO 和改注释）；不修改 `kol-portal/`。
- 不推送 `main`，不合并 PR，不 `push --force`，不改写已推送历史。

## 8. 停止条件

出现以下情况时停止，推送已完成的提交，以 `[WIP]` 标题创建 PR，并在报告中说明：

- 第 4 节的设计决定与现有代码或规范冲突，无法按原样实现；
- 复核的 Redis 内存与第 4.6 节的实测数据相差超过 20%；
- 需要新增依赖或修改已合并的迁移脚本；
- CI 在合理排查后仍无法通过。
