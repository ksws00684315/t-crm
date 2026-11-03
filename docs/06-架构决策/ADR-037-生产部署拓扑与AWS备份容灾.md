# ADR-037：生产部署拓扑与 AWS 备份容灾

日期：2026-11-01；2026-11-03 修订。状态：负责人已确认按方案执行（客户使用 AWS EC2）；恢复目标由负责人 2026-11-03 定为先行目标，客户答复待确认清单第 35 项后如有变化再修订。

## 背景

项目准备上线。原设计把备份放在 Cloudflare R2，服务器厂商未定。审查生产模板时发现：`upload.<域名>` 灰云却使用浏览器不信任的 Cloudflare 源站证书；`admin.<域名>` 是否走代理，设计说明书与部署文档相反；Nginx 只在启动时解析上游；生产模板缺日志轮转，并在服务器上构建镜像；备份不加密且最多丢失一天数据。客户确定使用 AWS，区域沿用美国西部（us-west-2）。

## 决定

1. **拓扑**：单台 AWS 主机上用 Docker Compose 运行 nginx、crm-admin、crm-redirect、postgres、redis。中间件容器化，不使用 K8s，不做主从，不使用 RDS。主机用 EC2（负责人 2026-11-03 确定），4 核 8G 非突发机型，沿用原内存规划。备份等访问 AWS 的操作使用实例角色，主机上不保存 AWS 访问密钥；强制 IMDSv2、hop limit 为 1，容器读不到实例凭证。pgBackRest 的 S3 凭证由主机定时取实例角色临时凭证，写入只读挂进 postgres 容器的配置目录（任务 106）。
2. **前端**：不单独起容器，两个前端的静态文件由同一个 Nginx 托管，用 `current` 软链接切换版本，发布前端不重启 Nginx。
3. **域名与证书**：Cloudflare 继续管 DNS 和代理。`t.`、`kol.` 走代理，用 Cloudflare 源站证书；`admin.` 默认走代理；`upload.` 灰云。`admin.`、`upload.` 使用 Let's Encrypt 公开证书（DNS-01），这样 `admin.` 将来改灰云时无须换证书。
4. **源站保护**：`upload.` 灰云会暴露源站 IP，安全组无法只放行 Cloudflare 回源段。`t.`、`kol.`、`admin.` 启用 Authenticated Origin Pulls，由 Nginx 校验 Cloudflare 客户端证书。
5. **备份**：备份目标由 R2 改为 AWS S3。pgBackRest 做全量、差异备份和 WAL 连续归档，客户端加密；保留每日加密 `pg_dump` 作为逻辑备份；S3 跨区域复制到另一区域并启用 Object Lock；云快照只用于恢复系统环境。Redis 只在本机保留 AOF，不做异地备份，丢失后通过「重建链接索引」恢复。
6. **恢复目标**（负责人 2026-11-03 定为先行目标）：最多丢失约 5 分钟数据，整机故障 2 小时内恢复。上线前做一次完整恢复演练，此后每季度一次。
7. **发布**：GitHub Actions 按版本标签构建镜像并推送到 GHCR，生产模板不再 `build:`。后台单实例，发布时有短暂中断，设计说明书的「滚动重启」作废。
8. **监控**：不部署 Prometheus、Grafana；外部探测加主机定时检查脚本，告警走 Webhook。

## 备选方案

- 传统方式安装 PostgreSQL、Redis：性能在本规模下差别可以忽略，但环境与预览不一致，换机更慢。
- RDS：省运维，但增加成本，轻量主机也未必有内网互通。客户要求托管时可只迁 PostgreSQL。
- 全部域名共用 Let's Encrypt：只维护一种证书，但续期失败会让推广链接一起失效；源站证书有效期长，`t.` 不承担这个风险。
- 给 `upload.` 第二个 IP：同样能隔离，但要改 Compose 监听和主机网络。Authenticated Origin Pulls 改动更小。
- 继续只用每日 `pg_dump`：人工归因、提现付款等操作无法从微店补拉，最多丢一天不可接受。
- 第三份副本放另一家云厂商：Object Lock 加跨区域复制已能防误删和勒索；同一 AWS 账号被完全接管的风险记录在案，客户有要求时再加。

## 影响

- Zone 级 Authenticated Origin Pulls 使用 Cloudflare 共享证书，只能证明请求来自 Cloudflare，不能证明来自本账号，能挡住绕过 Cloudflare 直连源站的请求。
- 恢复目标是内部执行目标，客户书面确认前不写入对外承诺。
- 选用 EC2 而非 Lightsail：可使用实例角色，按需扩容；成本高于报价时按 Lightsail 2 核 8G 的估算。
- 设计说明书 §2.1、§9、§10、§12 和 `deploy/README.md` 由任务 93～96 同步。
- 第三方新增：pgBackRest（MIT）、certbot（Apache-2.0）。只进运维镜像，不进应用依赖。
