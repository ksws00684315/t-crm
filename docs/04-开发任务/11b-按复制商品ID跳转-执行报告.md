# 任务 11b：跳转服务按复制商品 ID 跳转 `/i/{id}` 执行报告

## 执行结论

跳转服务新增 `GET /i/{weidianItemId}`（ADR-019 第 5 条、设计说明书 §4.5、§6.4、§7.4）。只改 `redirect/`，没有新增依赖，没有修改 `backend/`、`deploy/`。`/t/{kolCode}/{productCode}` 的行为不变，原有测试未改动全部通过。

## 改动

| 文件 | 改动 |
|---|---|
| `redirect/.../controller/RedirectController.java` | 新增路由 `GET /i/{weidianItemId}`，交给 `RedirectService.redirectItem` |
| `redirect/.../service/ClickRedis.java` | 新增 `lookupItem(id)`：`HGET linkitem:{nn} {id}`；分桶规则集中在 `itemKey(id)`：取 ID 末两位，不足两位左补 `0`（`7` → `linkitem:07`，`7849640325` → `linkitem:25`） |
| `redirect/.../service/RedirectService.java` | 原 `redirect` 中"查找 → 解析 → 302 → 写点击"的逻辑抽成私有方法 `resolve`，两条路由共用；新增 `redirectItem` |

行为：

- `weidianItemId` 必须是 1～20 位 ASCII 数字（`^[0-9]{1,20}$`，前导零按原样保留，不做规范化）；否则直接 302 到回退地址（带 `Cache-Control: no-store`），不访问 Redis、不写点击事件。
- 合法 ID：值的解析与校验复用 `LinkEntry.parse`（同一套 JSON 规则，未复制代码）。命中且 `status=1`、目标为合法 http(s) 地址 → 302 到 `targetUrl`、`no-store`，并写 `stream:click`；未命中、无法解析、`status=0`、目标不安全、Redis 查找异常 → 302 到回退地址，不写点击事件；写点击失败只记 `ERROR`，仍跳转到目标。
- 点击事件：`kolItemId`、`ts`、`ipHash`（加盐 SHA-256，客户端 IP 按可信代理规则解析）、`ua`（截断 512）、`ref`（截断 512）、`country`（`CF-IPCountry`，截断 8），与 `/t/` 的取值和截断规则完全相同，使用同一个 `ClickRedis.append`（同一 `MAXLEN ~`）。
- `/t/` 的点击事件仍是原来的 8 个字段（另含 `kolCode`、`productCode`）。`/i/` 拿不到这两个值，事件只有设计说明书 §4.5 列出的 6 个字段；后台 `ClickEvent.parse` 只读这 6 个字段（`kolCode` / `productCode` 注明"不入库"），消费端无需改动。
- 重构的唯一可见差异：事件 Map 由 `Map.of` 改为 `HashMap` 组装，字段集合与取值不变；Redis Stream 中字段的先后顺序本来就不固定（`Map.of` 迭代顺序随 JVM 随机），消费端按字段名读取。

## 测试

| 测试类 | 新增用例 |
|---|---|
| `ClickRedisTest` | 分桶规则：`7`、`0`、`10`、`05`、`7849640325`、20 位 ID；`lookupItem` 读 `linkitem:25` 的 `7849640325` |
| `RedirectServiceTest` | 非法 ID（空、21 位、含字母、`-1`、`+1`、`1.0`、前导空格、阿拉伯-印度数字、全角数字）→ 回退且不访问 Redis；合法 ID（`0`、`7`、`007`、10 位、20 位）按原样查找；查找异常 → 回退；事件 6 个字段、`ts`、IP 哈希（经可信代理）、UA/国家截断与 `/t/` 相同 |
| `RedirectControllerTest` | `/i/` 命中（302、`no-store`、事件字段与 IP 哈希）、未命中、`status=0`、非法 JSON / 不安全目标、非法 ID（含 URL 编码的非 ASCII 数字）、写点击失败仍跳转 |
| `RedirectIT`（`@Tag("docker")`，真实 Redis 7.4.5） | 命中写入真实 Stream 且字段集合正确；未命中、`status=0`、非 JSON、非法 ID 均 302 到回退地址且不产生 Stream |

本地结果（JDK 21.0.12.1，Homebrew `openjdk@21`；Maven，Docker 可用）：

- `mvn spotless:apply`：无遗留格式问题。
- `mvn verify`：通过，53 个单元 / 切片 / 架构测试（原 41 个 + 新增 12 个）；Spotless、Checkstyle、JaCoCo 门槛通过，单元测试行覆盖率 98.0%、分支覆盖率 94.4%。
- `mvn verify -Pdocker-it`：通过，53 个单元测试 + 11 个 Docker 集成测试（原 6 个 + 新增 5 个）。

## 偏离与遗留事项

1. **非法 ID 的响应与 `/t/` 不同（按任务要求）**：`/t/` 的非法路径返回 404（无响应体），`/i/` 按任务要求返回与未命中相同的 302 回退。任务原文同时写了"mirror the existing invalid-path behavior for `/t/`"，与现状（404）矛盾；本实现以"302 回退"为准。如需改为 404，只改 `RedirectService.redirectItem` 一行及对应测试。
2. **生产 Nginx 尚未放行 `/i/`（需另行处理）**：`deploy/nginx/templates/default.conf.template` 的 `t.<域名>` 只转发 `/t/` 与 `/healthz`，其余返回 404；`deploy/README.md` 也写明了这一点。本任务不改 `deploy/`，任务 11 也禁止改 `deploy/`。上线前须增加 `location /i/`（同样的 `limit_req` 限速与代理配置）并同步 `deploy/README.md`，否则表格中的中转链接在生产环境不可用。
3. `/i/` 依赖任务 11 写入的 `linkitem:{nn}`；在任务 11 合并并完成一次索引重建前，所有 `/i/` 链接都会跳回退地址。
