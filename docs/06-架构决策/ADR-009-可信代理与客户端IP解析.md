# ADR-009 可信代理与客户端 IP 解析

- 状态：已接受（2026-10-05）
- 决策人：项目负责人（评审答复：服务始终部署在代理之后——Cloudflare / Nginx）

## 背景

2026-10-05 的代码评审发现两处获取客户端 IP 的方式不一致且不安全：

- 跳转服务 `RedirectService.clientIp` 无条件采用请求头 `CF-Connecting-IP`，其次 `X-Forwarded-For` 的最左一项。任何能直连该服务（或绕过 Cloudflare 的）请求方都可以伪造这些头，污染点击记录的 `ipHash`，并绕过设计说明书 §4.5 的"同 IP 10 秒内重复点击"判定。
- 后台审计 `AuditAspect` 使用 `request.getRemoteAddr()`，在 Nginx 之后只会记录代理地址，丧失安全取证价值（规范 06 §4）。

## 决定

1. 两个工程各有一份**相同的** `ClientIpResolver`（后台 `shared` 包、跳转服务 `config` 包），行为如下：
   - 配置 `TCRM_TRUSTED_PROXIES`（逗号分隔的 IP 或 CIDR，支持 IPv4/IPv6），**默认为空，即不信任任何代理**，直接使用 TCP 对端地址。
   - 仅当 TCP 对端属于可信代理时才读取转发头；头名称由 `TCRM_CLIENT_IP_HEADERS` 指定，默认按顺序 `CF-Connecting-IP`、`X-Forwarded-For`。
   - 对每个头，把全部取值拆成跳数，**从右向左**跳过可信代理，取第一个非可信地址；遇到不是 IP 字面量的值立即放弃转发头，回退到对端地址（不会触发 DNS 解析）。
   - 返回规范化文本（IPv6 为完整展开形式），保证同一地址哈希一致。
   - 配置格式错误时启动失败（规范 06 §5）。
2. 后台审计 IP 与跳转服务的 `ipHash` 均通过该解析器取得；登录失败限流（ADR-008）也使用它。
3. 部署责任：Nginx 必须只接受来自 Cloudflare 的连接并用 `real_ip` 模块还原 IP 后再转发，或者只允许 `CF-Connecting-IP` 经 Cloudflare 传入；应用层只保证"不信任不可信对端"，不能替代网络层限制。

## 备选方案及放弃原因

| 方案 | 放弃原因 |
|---|---|
| Spring Boot 的 `server.tomcat.remoteip.*` | 无需自写代码，但默认内部代理范围较宽（含全部私网段），行为难以单元测试，也无法同时覆盖 `CF-Connecting-IP` 与 XFF 的优先级；跳转服务与后台需要同一套可测试的语义 |
| 抽成共享模块 | 规范 01 §1 规定跳转服务不与后台共享代码，且无法修改 CI 工作流增加模块构建；因此保留两份拷贝，并用 `ClientIpResolverParityTest`（两个工程各一份）在构建时校验两份源码除包名外完全一致 |
| 信任所有私网来源 | 容器网络中的其他服务同样是私网地址，缺少显式配置时会放大信任范围 |

## 影响

- 新环境变量 `TCRM_TRUSTED_PROXIES`、`TCRM_CLIENT_IP_HEADERS`（`.env.example`、README、部署模板需同步）。**未配置 `TCRM_TRUSTED_PROXIES` 时**，在代理之后部署会把所有请求视为来自代理 IP：点击去重失效、审计 IP 为代理地址——这是安全的默认值，但生产环境必须配置。
- 修改解析逻辑时须同时修改两份拷贝，否则 `ClientIpResolverParityTest` 失败。
