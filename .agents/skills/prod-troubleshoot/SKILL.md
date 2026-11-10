---
name: prod-troubleshoot
description: 正式环境出问题时使用：ops-check / 外部探测告警、页面 502 或报错、发布与回滚失败、备份 / WAL / 证书异常、微店复制商品或订单同步失败、表格脚本报错、佣金对账 ERROR。经 AWS SSM 在生产主机上只读排查，定位根因并写排障报告，变更必须负责人确认。Use when production alerts fire or production misbehaves.
---

# 正式环境排障

依据：[`deploy/README.md`](../../../deploy/README.md)「发布与回滚」「备份与恢复」「监控告警 → 告警处理指引」、[`deploy/RUNBOOK.md`](../../../deploy/RUNBOOK.md)、ADR-037、`AGENTS.md` 第 7、11 条。本 skill 只写**排障顺序、只读命令和判断方法**；装机、发布的完整步骤以上面两份文档为准，冲突时以它们为准。

## 什么时候用

- 收到 ops-check 飞书告警（`ALERT …`）、外部拨测或心跳告警；
- 负责人或客户反馈正式环境页面打不开、接口报错（带 traceId 更好）、数据不对；
- `release.sh deploy / rollback` 中途失败；
- 微店复制商品、订单同步、表格脚本、佣金对账等业务链路在正式环境报错。

不用于：本地 / 预览环境问题（用 `$preview-verify`），以及从零装机、首次上线（照 `deploy/RUNBOOK.md` 做）。

## 一、环境事实（2026-11-07 核实）

| 项 | 值 |
|---|---|
| 主机 | EC2，Name 标签 `SparkTrack-Prod`，**区域 `us-east-1`**（RUNBOOK 写的 `us-west-2` / `tcrm-prod` 与实际不符，以实际为准），Ubuntu 24.04 |
| 登录 | 不开 22 端口；经 SSM。本机 AWS profile `tcrm-prod`（排障用）、`tcrm-drill`（演练机） |
| 仓库 | `/opt/tcrm`，root，按版本标签检出 |
| 数据目录 | `/var/lib/tcrm/`（`certs`、`www`、`backups`、`pgbackrest-creds`、`secrets`、`ops-state`、`certbot`），见 RUNBOOK 第 0 章 |
| Compose | `docker compose -f deploy/docker-compose.prod.yml --env-file deploy/.env`，服务 `nginx`、`crm-admin`、`crm-redirect`、`postgres`、`redis` |
| 定时任务 | root crontab（时间 UTC）：凭证刷新每 15 分钟、ops-check 每 5 分钟、pgBackRest 02:00、逻辑备份 03:00、证书续期 04:17。整块见 RUNBOOK 第 15 章 |
| 日志 | 容器 json-file；`crm-admin` 每行带 `[traceId]`；证书续期 `/var/log/tcrm-cert.log`；发布记录 `deploy/releases.log` |
| 域名 | `admin.` / `upload.`（Let's Encrypt），`t.` / `kol.` / 根域名（Cloudflare 源站证书）；`upload.` 灰云直连 |

## 二、执行方式

所有主机命令经 [`scripts/prod-run.sh`](scripts/prod-run.sh) 执行（`aws ssm send-command`，以 root 在 `/opt/tcrm` 下运行，单条最长 5 分钟，输出超过 24000 字符会被截断）：

```bash
.agents/skills/prod-troubleshoot/scripts/prod-run.sh 'deploy/scripts/release.sh status'
```

- 实测（2026-11-07，v0.1.4）：`release.sh status` 与第四节总览快照经本脚本执行通过；第四节只读 SQL 的写法 2026-11-08 排查任务 132 时在真机验证通过。本机会话的权限检查可能拦下生产 SQL，即使负责人已同意也不要绕过，把要查的 SQL 交给负责人执行。
- 每次 SSM 调用是一个新 shell，变量不跨调用保留。命令里统一用 `C="docker compose -f deploy/docker-compose.prod.yml --env-file deploy/.env"` 开头。
- 第一次调用前先确认本机身份：`aws sts get-caller-identity --profile tcrm-prod`。报 `AccessDenied` 或凭证过期时停下，请负责人处理，不要换别的 profile 或自己建密钥。
- SSM 会在 AWS 侧留下命令记录，所以**命令文本里不得出现口令、令牌或个人信息**。

## 三、动作边界

**可以直接做（只读）**

- `release.sh status`、`docker compose ps`、`docker compose logs`、`docker stats --no-stream`、`docker system df`、`df -h`、`free -m`、`uptime`、`git -C /opt/tcrm describe --tags`；
- `deploy/scripts/ops-check.sh` 本身不改服务，但会发飞书告警和恢复通知、写去重状态：要看检查结果时**置空通知并换临时状态目录**运行，不要冒充定时任务发通知，也不要打乱它的去重：
  `TCRM_ALERT_WEBHOOK_URL= TCRM_HEARTBEAT_URL= TCRM_OPS_STATE_DIR=$(mktemp -d) deploy/scripts/ops-check.sh`；
- 只读 SQL（见第四节的写法，强制只读事务）；
- `pgbackrest --stanza=tcrm info`、`openssl x509 -noout -dates -subject -issuer`、`crontab -l`、`tail` 日志文件；
- 本机：读仓库代码与文档、`gh` 查看 PR / Actions、`aws` 只读查询（describe / get / list）。

**必须先在对话里写明「做什么、影响什么、怎么回退」，等负责人明确同意后才做**

- 重启或重建任何容器（`up -d --no-deps`、`restart`）、`release.sh deploy / rollback`、打标签；
- 手动备份（`pg-backup.sh`、`pgbackrest-backup.sh`）、证书续期、凭证刷新脚本；
- 改 `deploy/.env`、crontab、Nginx 配置、主机文件；
- 任何写库操作（含「修数据」的 UPDATE / DELETE / INSERT），哪怕只有一行；
- 在 AWS 控制台或 CLI 上的任何修改（安全组、S3、IAM、EC2）。

**禁止**

- `docker compose down -v`、删除 `tcrm_pgdata` / `tcrm_redisdata` 卷、`docker system prune`、`docker volume rm`；
- `cat` / 输出 `deploy/.env`、`/var/lib/tcrm/secrets/*`、`pgbackrest-creds/*`、证书私钥；要确认某个变量是否设置，用 `grep -c '^TCRM_XXX=' deploy/.env` 只看有没有；
- 在生产主机上 `git checkout`、改代码、手工改迁移或 `flyway_schema_history`；
- 把日志原文、查询结果里的邮箱、手机号、收款账户、买家信息贴到 PR、issue、飞书或文档里（只写 ID、计数、traceId、错误码）。

## 四、通用只读命令

**总览快照**（每次排障先跑一次，结果写进报告）：

```bash
prod-run.sh 'C="docker compose -f deploy/docker-compose.prod.yml --env-file deploy/.env"; git describe --tags; deploy/scripts/release.sh status; $C ps; df -h / /var/lib/docker; free -m; uptime'
```

**日志**（按服务、时间窗、traceId 收窄，避免截断）：

```bash
prod-run.sh 'C="docker compose -f deploy/docker-compose.prod.yml --env-file deploy/.env"; $C logs --since 2h --no-color crm-admin | grep -E " (ERROR|WARN) " | tail -80'
prod-run.sh 'C="docker compose -f deploy/docker-compose.prod.yml --env-file deploy/.env"; $C logs --since 24h --no-color crm-admin | grep -F "[<traceId>]" | tail -60'
```

**只读 SQL**（超级用户经容器内本地连接；`PGOPTIONS` 让事务只读，误写会被数据库拒绝）：

```bash
prod-run.sh 'C="docker compose -f deploy/docker-compose.prod.yml --env-file deploy/.env"; $C exec -T -e PGOPTIONS="-c default_transaction_read_only=on -c statement_timeout=30s" postgres sh -c '"'"'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -P pager=off -c "select now()"'"'"''
```

SQL 引号嵌套容易出错。复杂查询先在本机写好，再把整条命令交给 `prod-run.sh`；只选排障需要的列，不选邮箱、手机号、收款账户、买家字段。

**操作日志**：接口报错时用户拿到的信封里有 `traceId`；`sys_oper_log` 记录写操作（`result`、`error_msg`、`request_uri`、`oper_time`），可按时间与 `request_uri` 找对应请求。

## 五、排障流程

1. **定界**：从告警文本或反馈里取出：时间（换算成 UTC）、受影响的入口（`admin.` / `kol.` / `t.` / `upload.`）、公司 / 店铺 / 达人 / 任务编号、traceId。说不清的先问负责人，不要猜。
2. **总览快照**：跑第四节的总览，先判断是「整站不可用」「单个服务异常」还是「单条业务失败」。
3. **按症状查表**：第六节（基础设施）或第七节（业务链路），按「先看」列逐项查。
4. **找到根因再动手**：要有证据（日志行、SQL 结果、配置项）指向原因。只观察到现象时写「疑似」，不要报成结论。
5. **止血**：需要重启、回滚、手动备份等，按第三节写明影响和回退方法，等负责人同意。
6. **修复**：代码或配置缺陷一律在本机按正常流程处理：先在任务索引（`docs/04-开发任务/README.md`）登记新编号，开 `task/{编号}-{名称}` 分支，先写复现测试，再提 PR，合并后负责人打标签发布。**不在生产主机上热修。**
7. **报告**：按第九节写排障报告。

## 六、基础设施症状

ops-check 告警类型与阈值见 `deploy/README.md`「主机检查脚本」，处理口径见同文「告警处理指引」。下表补充只读定位命令和已知的坑。

| 症状 | 先看 | 判断与处理 |
|---|---|---|
| `container`：服务非 running / unhealthy / 5 分钟内重启 | `$C ps`；`$C logs --since 30m <服务> \| tail -100` | 发布后紧跟的一对「重启过 → RECOVERED」属预期（README 已说明）。`crm-admin` 反复重启：先搜日志 `applied migration not resolved locally`，是 Flyway 严格模式拒绝旧镜像，按 README「发布与回滚」第 9 项处理，**不要改 `flyway_schema_history`** |
| 整站或接口 502 | 总览；`$C logs --since 30m nginx \| tail`；`$C logs --since 30m crm-admin \| tail` | 发布期间 30～60 秒 502 是预期；超过就看 `crm-admin` 是否健康、是否在启动失败循环 |
| 发布 / 回滚失败 | 脚本打印的「当前状态和下一步」；`deploy/releases.log` 末尾；`release.sh status` | 脚本不自动回滚数据库。`crm-admin` 已替换但不健康时，恢复方式是 `release.sh deploy <上一个版本>`（需负责人同意）。拉镜像失败通常是 GHCR 登录或标签工作流没跑完：本机 `gh run list --workflow Release` |
| `disk` | `df -h`；`docker system df`；`du -sh /var/lib/tcrm/* 2>/dev/null` | ≥ 85% 会写满 WAL 与备份，优先处理。清理镜像、日志需负责人同意，禁止 `prune` 卷 |
| `memory` | `free -m`；`docker stats --no-stream` | 持续 < 10% 按 README「资源与 Redis 容量」评估扩容 |
| `cert` | `openssl x509 -in /var/lib/tcrm/certs/public.pem -noout -dates -subject`；`tail -50 /var/log/tcrm-cert.log` | 续期失败多为 Cloudflare 令牌失效或权限不是 600；重跑续期需负责人同意 |
| `pgbackrest`：备份过旧 / WAL stalled | `$C exec -T -u postgres postgres pgbackrest --stanza=tcrm info`；`$C logs --since 2h postgres \| grep -i -E "archive\|error" \| tail` | TLS 证书校验失败见案例 116；S3 不可用按 README「S3 不可用时」处理，恢复后要立即全量 |
| 备份凭证未刷新 | `ls -l --time-style=+%FT%T /var/lib/tcrm/pgbackrest-creds/`（只看时间、属主、权限，不看内容）；`crontab -l \| grep refresh` | 目录必须 755、文件 600 属主 uid 999；实例角色取不到凭证时查 IMDSv2 与角色（README「备份与恢复」） |
| `backup`：逻辑备份过旧 | `ls -lt /var/lib/tcrm/backups \| head`；`crontab -l` | gpg 口令文件权限、磁盘；手动备份需负责人同意 |
| `applog`：ERROR 出现 | 告警里的 logger 名和 traceId → 第四节按 traceId 查日志 | 先判断是不是误报（案例 125），再按第七节对应链路查 |
| `capacity`：Redis 内存 / 点击积压 WARN | `$C logs --since 2h crm-admin \| grep -E "Redis used_memory\|Click stream backlog" \| tail` | 按 README「资源与 Redis 容量」安排扩容，不要等 ERROR |
| 心跳停了 | `crontab -l`；`tail` ops-check 的 cron 输出；`uptime` | 脚本没执行（权限、cron 丢失，见案例 117）或主机挂了 |
| 外部拨测失败、主机内一切正常 | 本机 `curl -sS -o /dev/null -w '%{http_code}\n' https://<入口>`；Cloudflare 状态 | 多为 DNS、Cloudflare 回源认证或证书问题；Cloudflare 控制台操作交给负责人 |

## 七、业务链路症状

| 症状 | 先看 | 判断与处理 |
|---|---|---|
| 表格脚本复制商品失败 | `crm_copy_job`（`job_code`、`status`、`error_message`、计数）→ `crm_copy_job_item`（`source_product_id`、`status`、`error_message`、`target_item_id`）；按任务创建时间查 `crm-admin` 日志中的 `Weidian API error <code>` | 任务级错误多为店铺未授权（查 `crm_weidian_shop_auth`）。200056 是笼统的参数错误码，不能只凭数字码下结论；v0.1.5 起看错误信息和 WARN 日志里的微店原因（案例 134），再查本表历史案例（案例 127 的结论已更正，见案例 130）。新原因要对照微店开放平台文档，在报告里写明来源 |
| 明细报「多个店铺都有该商品」 | 源商品是否已登记在多个店铺（`crm_product.weidian_item_id`）；日志里每个商品是否对每个启用店铺都有 `vdian.item.get` 且 `statusCode=0` | 已登记在多个店铺：数据问题，交负责人处理。未登记、所有店铺都探测成功：微店 `item.get` 不校验归属，v0.1.10 起按 `seller_id` 确认（案例 137）；修复后仍报，说明 `seller_id` 与店铺微店 ID 不是同一套编号 |
| 复制任务一直「处理中」 | `crm_copy_job.status`、明细是否都还是 `pending`；日志里有没有这个任务的 `vdian.item.get` | 明细从未被处理是派发问题（案例 131，v0.1.7 已修）；重启 `crm-admin` 会续跑 `processing` 的任务，重启需负责人同意 |
| 明细报「来源商品信息不完整」 | 源商品是否已登记到 `crm_product`；日志里 `item.get` 之后有没有 `item.add` | 自动登记取不到名称或价格。多规格不同价见案例 132；其他情况看 `item.get` 返回里缺了哪项 |
| 商品图片不显示 | `crm_product.image_url` 是否有值、是否 https；本机 `curl -sI https://admin.<域名>/ \| grep -i content-security` | `image_url` 为空：案例 131 之前登记的商品没有图片，由负责人手工补。有值但不显示：先看 CSP 的 `img-src`（案例 133），再在浏览器里看图片请求的状态码 |
| 表格脚本报「错误请求」或连不上 | 客户脚本属性 `TALENT_CRM_API_BASE_URL` 是否被断行出空格；默认地址是否为正式域名 | 见案例 121、124；`deploy/sheet-script/README.md` |
| 表格脚本「选择平台」没有 SparkTrack 中转 | — | 案例 126：负责人 2026-11-07 决定停用，属预期 |
| 订单没同步、站内消息「订单同步失败」 | `crm_order_sync_state`（每店一行：`last_success_at`、`last_error`、游标）；`crm_order_sync_failure`（`kind`、`error`、`create_time`） | 同步每 5 分钟一次（UTC）。`last_error` 是英文安全摘要；令牌问题转下一行 |
| 微店店铺授权失效 | `crm_weidian_shop_auth`（`status`、`access_expires_at`、`refresh_expires_at`、`last_check_ok`、`last_error`）；不要查 `*_cipher` 列 | `EXPIRED` / `FAILED` 需要在管理端重新接入，由负责人或客户操作；续期任务每小时一次 |
| 结算 / 释放没动 | `crm_order_sync_state.settlement_pull_at`、`settlement_backfill_at`；订单的计佣状态 | 微店真实结算字段仍待 M0 核实（遗留清单），核实前不计提、不释放属预期，不要报成缺陷 |
| 佣金对账 ERROR `withdrawal occupation exceeds wallet balance` | 日志里的公司、达人、余额、占用；`crm_commission_wallet` 与 `crm_commission_ledger` 合计 | 余额为负且占用为 0 是预期（预付抵扣，案例 125 已修）；占用 > 0 且超过余额才需要人工介入 |
| 导入任务卡住或失败 | `crm_task`（`task_type`、`status`、`total/done/failed`、`message`、`started_at`、`finished_at`） | `RUNNING` 很久不动先看 `crm-admin` 是否在任务期间重启过 |
| 中转链接 / 点击异常 | `crm_link_index_outbox`（积压行数、`attempts`、`last_error`）；`crm-redirect` 日志 | outbox 成功即删除，积压且 `attempts` 增长说明 Redis 写入失败 |
| 微店开放平台域名验证失败 | 本机 `curl -sS https://<根域名>/weidian_open.json` | 案例 119：根域名需解析并走代理，文件在 `TCRM_STATIC_DIR` 根目录 |

## 八、上线后已处理的案例

先对照这里，避免重复排查。

| 任务 | 现象 | 根因 | 处理 |
|---|---|---|---|
| 116 | v0.1.0 上线后 pgBackRest 无法验证 S3 TLS 证书，备份与 WAL 归档失败 | postgres 镜像缺 `ca-certificates` | 镜像补装，v0.1.1 |
| 117 | root crontab 调用 `ops-check.sh` 报 Permission denied | 仓库中文件模式为 100644 | 补可执行位 |
| 119 | 微店发布应用时域名验证 `UnknownHostException` | 平台从根域名取 `weidian_open.json`，根域名无解析、无 server 块 | Nginx 增加根域名 server 块 |
| 121 / 124 | 表格脚本接口地址不对 / 「错误请求」 | 默认地址是预演域名；脚本属性被断行成空格 | 默认改正式域名；先去掉空白再校验 |
| 125 | 每天 02:30 UTC 佣金对账报 ERROR | 用 `balance - funding < 0` 判断，把合法负余额当异常 | 只在有提现占用且超过余额时报 |
| 127 | 复制商品失败，`Weidian API error 200056` | 当时判断为同店复制时商家编码重复。**已更正**：任务 134 透出微店原因后，真实原因是「SKU标题不能为空」，见案例 130 | 复制一律不传 `merchantCode`，v0.1.4；没有解决这次报错 |
| 134 | 微店报错只有数字码，无法判断原因 | `WeidianClient` 丢弃了 `status_reason` | 异常消息带上原因，WARN 日志记录方法、版本、店铺、请求参数（不含令牌），v0.1.5。原登记为任务 128，与 #223 重号后改号 |
| 130 | 复制仍报 200056，原因「SKU标题不能为空」 | `vdian.item.add` 没有带源商品的规格（`attrList`、SKU `attrIds`） | 带上源商品规格，v0.1.6 |
| 131 | 复制任务一直「处理中」，明细 `pending` 从未处理；自动登记的商品没有图片 | `convert` 在事务提交前就把任务交给工作线程，线程读不到任务；自动登记源商品时没有保存图片 | 事务提交后再派发；登记时取第一张图（http 转 https），v0.1.7。服务启动时会续跑 `processing` 的任务 |
| 132 | 续跑后明细失败，「来源商品信息不完整」，日志里只有 `item.get`、没有 `item.add` | 多规格商品各规格价格不同，取不到单个商品价格 | 取不到时用各 SKU 最低价，v0.1.8。已是 `failed` 的任务不会重跑，要在表格里重新提交 |
| 133 | 管理端、达人端商品图片都不显示，`image_url` 有值 | 两个前端的 CSP 是 `img-src 'self' data:`，拦截所有外部图片 | 改为 `img-src 'self' data: https:`，v0.1.9。Nginx 配置要在发版后手动 `nginx -t` 和 `nginx -s reload` 才生效 |
| 137 | 接入第二个店铺后，复制任务全部报「多个店铺都有该商品」 | 源商品未登记时逐店探测 `item.get`，而微店不校验商品归属，两店都命中 | 比对 `seller_id` 与店铺微店 ID，确认的店铺优先，无确认时与原逻辑相同。已失败的任务要重新提交 |
| 144 | 店铺「刷新容量」报 ERROR（traceId b6eb3b50…，2026-11-10 02:11 UTC）：`vdian.item.list.get` statusCode=108 `page_size param illegal!` | `countOnSaleItems` 固定传 `page_size=100`，微店拒绝大分页（确切上限未知，1/5 实测可行） | 改为每页 5 条（已验证可行值），`total_num` 有值时直接返回 |
| — | 2026-11-10：客户重复提交同一批 2206 个商品，前后两条复制任务共 217 条明细报 `DuplicateKeyException` / `uk_crm_product_shop_item` | 两条任务并行处理同一来源商品；`ensureProduct` 先查后插且未拿店铺商品咨询锁，后插入的事务撞唯一索引 | `ensureProduct` 与复制前加 `ProductRules.shopItemLockKey` 事务锁，第二条任务复用已登记商品与已复制结果。这类失败明细通常已被另一条任务复制成功，先在商品任务明细核对再决定是否重交 |)

新案例处理完后，把一行补进本表（与修复 PR 一起提交）。

## 九、排障报告

写在对话里交给负责人，需要留档时再放进 `docs/04-开发任务/{编号}-…-执行报告.md`。内容：

1. **现象**：时间（UTC 与北京时间）、入口、影响范围、告警原文（去掉个人信息）。
2. **总览快照**：版本、各服务状态、磁盘、内存。
3. **排查过程**：执行过的命令（原文）与关键输出（截取，不含个人信息），每一步的判断。
4. **根因**：结论和证据；只有现象、没有证据时写「疑似」，并写还缺什么证据。
5. **已做的处理**：只读查询以外的每个动作都要写负责人同意的时间点。
6. **建议**：止血方案、修复任务（拟定编号与范围）、是否需要登记遗留问题（`docs/04-开发任务/遗留问题清单.md`）、本 skill 是否要补案例。
