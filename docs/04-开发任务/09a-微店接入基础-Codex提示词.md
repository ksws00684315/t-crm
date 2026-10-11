# 任务 09a：微店接入基础（凭证加密、店铺授权、接口客户端与接口定义）

> 本文件是交给编码代理执行的提示词。直接把本文件全文作为任务输入即可。
> **前置条件：任务 06c 后端（PR #29）已合并到 `main`。**
>
> **修订说明（2026-10-12，第 2 版）**：第 1 版要求代理先自行核对官方文档。代理读不到文档站（页面内容由脚本加载），于是按停止条件提交了 [WIP] PR #31。项目负责人已核对官方接入指南，结论写在第 4.0 节，**以第 4.0 节为准，不再需要自行判断授权方式**。每个接口的参数和字段说明，可以通过第 2 节第 5 条的数据接口直接读取。
>
> **本任务的目标是按官方接入指南把代码写好、把接口定义好，不要求与真实微店联调**（目前没有真实账号）。所有测试都针对本地模拟服务。
>
> **并行说明**：本任务与《06d-中英文切换-后端》《06d-中英文切换-管理端》同时在不同工作树中执行。
> - **本任务只做后端，不改 `admin-ui/`。**
> - 06d 后端会改 `shared` 中的校验配置和导入相关代码。本任务尽量把改动放在 `integration` 包内，`shared` 只新增类，不改动与本任务无关的已有类。

---

## 1. 背景

客户要求新系统通过微店开放平台接口完成订单与退款同步、商品自动复制等功能。这些业务的规则还在等客户确认（第二轮确认清单第 3～10、13～15 题）。**本任务只做与业务规则无关、后续模块一定会用到的底层能力**：

1. **按公司加密保存第三方凭证**：设计说明书 §3.4"公司级配置与凭证"已定，本任务首次落地。
2. **两种授权模式的店铺接入**：自用型、服务型（第 4.0 节）。
3. **接口调用客户端**：请求格式、令牌、限流、重试、错误分类。
4. **业务接口的类型化定义**：订单、退款、商品。只定义请求和返回结构，并用官方示例做解析测试，不实现同步或复制业务。

客户现有系统中，每个店铺都有"去授权"按钮和"授权过期时间"（类似服务型）。但客户用的是哪种应用类型还未确认，**所以两种模式都要实现**，由每个店铺选择。

## 2. 开始前必读

1. `AGENTS.md`、`docs/05-工程规范/`（重点：01、03、04、06 第 4 节"第三方凭证"、07）
2. 设计说明书 §3.4（公司级配置与凭证）、§7.1、§9、§12；ADR-014
3. `docs/02-调研/微店API风险调研报告.md`
4. 现有代码：
   - `shared.TenantScope`、`CompanyTenantLineHandler`、`DistributedLocks`、`AuditSanitizer`；
   - `product` 模块的店铺（`ShopService`、`product.api`）；
   - `security.api.CurrentUserContext`；
   - `link.service.LinkIndexSyncJob`：定时任务、系统范围、锁的写法参照它。
5. **微店官方文档**：文档站 open.weidian.com 是单页应用，内容来自下面这些公开的 JSON 接口，不需要登录（请求时带 `Referer: https://open.weidian.com/`）：
   - 接入指南正文：`https://thor.weidian.com/openweb/doc.getById/1.0?param={"id":N}`（`param` 需要 URL 编码）。
     - 138：应用类型
     - 143：加密/解密规则
     - 144：API 协议
     - 145：获取 Token（授权流程）
     - 146：错误码
   - API 分类：`api.list/1.0?param={}`。
   - 某分类下的接口：`api.listByParentId/1.0?param={"parentId":P}`，例如 1 为商品管理、2 为订单管理。
     - 每项的 `idVersion` 形如 `115-1.2,1065-1.5,10668-1.6`，表示"文档 ID-版本"。**取版本号最高的一项**，除非下文另有指定。
   - 单个接口的说明：`api.getById/1.0?param={"id":"文档ID"}`。返回参数表、示例和字段说明。

## 3. 执行环境与交付

- 你在独立的 git 工作树中工作，分支 `task/09a-weidian-foundation`。
  - 先合并最新的 `main`，以取得本修订版提示词，然后在此分支上继续提交；
  - **沿用已有的 PR #31**：完成后把标题中的 `[WIP]` 去掉，并按 PR 模板更新描述。
- 只修改：
  - `backend/`；
  - `deploy/.env.example`、`deploy/docker-compose.prod.yml`（只增加新环境变量的传入）、`deploy/README.md`；
  - `docs/03-设计/系统详细设计说明书.md`；
  - 新增的 `docs/06-架构决策/ADR-016-微店店铺授权与凭证加密.md`；
  - 你的执行报告。

  **不要修改** `admin-ui/`、`kol-portal/`、`redirect/` 和 `docs/04-开发任务/README.md`。
- 能运行 Docker 时本地运行 `mvn -B verify -Pdocker-it`；否则 `*IT` 由 CI 运行。
- 推送后用 `gh pr checks --watch` 等待 CI 通过。**不要合并 PR，不要推送 `main`。**
- **迁移版本号**：推送前先合并最新的 `main`。若 `main` 上已有版本号更大的迁移（并行任务 06d 后端也会新增迁移），把你**尚未合并**的脚本改名为更晚的时间戳。
- 提交遵循规范 08，末尾加执行代理自己的 `Co-Authored-By` 行。规范 05：先写测试。
- **测试只用本地模拟服务**：单元测试用 Spring 的 `MockRestServiceServer`；集成测试用 JDK 自带的 `com.sun.net.httpserver.HttpServer`。**不新增依赖。**

## 4. 已定的设计决定（不要自行改变）

### 4.0 官方接入事实（项目负责人已核对，2026-10-12）

| # | 事项 | 官方说明（文档 145、144、146、138） |
|---|---|---|
| 1 | 应用类型 | **自用型**：只能用于注册它的那一个店铺，用 appkey 和 secret 直接换取令牌，不需要店铺授权，仅支持商城版店铺。**服务型**：卖家授权后可用于多个店铺，单次授权 30 天，需要平台审核。 |
| 2 | 自用型取令牌 | `GET https://oauth.open.weidian.com/token?grant_type=client_credential&appkey=..&secret=..`。返回 `result.access_token`、`result.expire_in`（秒，示例 90000，即 25 小时）。**每天最多调用 20 次**。 |
| 3 | 服务型授权地址 | `https://oauth.open.weidian.com/oauth2/authorize?appkey=..&redirect_uri=..（URL 编码）&response_type=code&state=..`。**`state` 只能是 `a-zA-Z0-9`**。回调地址的域名必须与应用中设置的回调域名一致，发起授权的页面与回调地址必须同根域名。 |
| 4 | 回调参数 | 同意授权时跳转到 `redirect_uri?code=CODE&state=STATE`；拒绝时只带 `state`。`code` 只能用一次，5 分钟内有效。 |
| 5 | 授权码换令牌 | `GET https://oauth.open.weidian.com/oauth2/access_token?appkey=..&secret=..&code=..&grant_type=authorization_code`。返回 `access_token`、`expire_in`（秒）、`refresh_token`、`refreshExpireTime`（文档写"失效时间，单位毫秒"）、**`openid`（当前店铺 ID）**、`shop_name`、`scope`。 |
| 6 | 刷新令牌 | `GET https://oauth.open.weidian.com/oauth2/refresh_token?appkey=..&grant_type=refresh_token&refresh_token=..`。返回结构同第 5 项，但没有 `refreshExpireTime`。刷新令牌有效期为 30 天（与服务市场订购时长有关），失效后要重新授权。 |
| 7 | 令牌规则 | 访问令牌有效期 25 小时，以返回的 `expire_in` 为准；文档中字段名有 `expire_in` 和 `expires_in` 两种写法，两种都要支持。**重新获取令牌后，旧令牌 5 分钟后失效**；刷新期间新旧令牌短时间内都可用。官方要求由一个中心统一获取和刷新令牌（主动定时刷新，加上调用发现过期时的被动刷新），各处不得各自刷新。令牌字段至少保留 512 个字符。 |
| 8 | 接口调用 | 网关 `https://api.vdian.com/api`，建议用 POST。参数为 `public`（JSON：`method`、`access_token`、`format`=`json`、`version`）与 `param`（业务参数 JSON），**全部 URL 编码**。 |
| 9 | 返回结构 | `{"status":{"status_code":0,"status_reason":""},"result":{...}}`。`status_code` 为 0 表示成功。 |
| 10 | 错误码（节选，全表见文档 146） | 10000 数据库异常、10001 系统错误、10002 接口超时；10004 IP 请求频次超限、10005 用户请求频次超限；10006 需要授权、10009 接口权限受限；10013 access_token 无效、10016 不合法的 access_token、10021 未获得用户授权；10017 授权码无效或失效；10022 appkey 或 secret 错误；10023 刷新令牌错误或失效；10024 授权回调域名无效；10026 授权请求已过期；10028 非服务型应用无法授权；10029 应用不存在或已冻结；-1 其他错误 |
| 11 | 调用量 | 每个接口每天有调用次数上限；服务型应用上线后的上限远大于测试期。调研资料中自用型的 QPS 约为 5 次/秒。 |

如果你读取官方文档时发现与上表**矛盾**，在报告中列出，按官方文档实现，并在 PR 描述中提醒项目负责人。

### 4.1 凭证加密（`shared`）

新增一个通用的凭证加密组件，例如 `shared.SecretCipher`：

| 项目 | 规则 |
|---|---|
| 算法 | AES-256-GCM，每次加密使用新的 12 字节随机 nonce，128 位认证标签 |
| 主密钥 | 环境变量 `TCRM_SECRET_KEY`：Base64 编码的 32 字节。**必填**，缺失或长度不对时应用启动失败，提示变量名（规范 06 第 5 节） |
| 密文格式 | `v1:` + Base64(nonce ‖ 密文 ‖ 标签)。遇到未知版本前缀或认证失败，直接抛异常 |
| 换密钥 | 本期只实现 `v1`，前缀为以后更换主密钥预留 |
| 日志与异常 | 明文、密文、主密钥一律不进日志和异常信息 |

同步更新：
- `deploy/.env.example`，写明生成方法，例如 `openssl rand -base64 32`；
- prod compose：给 crm-admin 传入该变量；
- `deploy/README.md`：必填说明，并写明"丢失主密钥意味着已保存的凭证全部要重新录入"；
- 集成测试使用随机密钥。

### 4.2 数据表（新增迁移）

两张表都带 `tenant_id`，并遵守规范 04：雪花主键、通用字段、表和字段注释、不加外键。

**`crm_weidian_app` 服务型应用（每家公司最多一份）**

| 字段 | 说明 |
|---|---|
| `tenant_id` | 唯一 `uk_crm_weidian_app_tenant` |
| `app_key` | VARCHAR(64)，明文 |
| `app_secret_cipher` | TEXT，密文 |

**`crm_weidian_shop_auth` 店铺接入（每个店铺最多一行）**

| 字段 | 说明 |
|---|---|
| `tenant_id` | |
| `shop_id` | `crm_shop.id`，唯一 `uk_crm_weidian_shop_auth_shop` |
| `auth_mode` | VARCHAR(16) + `CHECK`：`SELF_USE` 自用型、`SERVICE` 服务型 |
| `self_app_key`、`self_app_secret_cipher` | 自用型应用的 appkey（明文）和 secret（密文）；**只有 `SELF_USE` 时非空**（用 `CHECK` 约束） |
| `openid` | VARCHAR(64) NULL，服务型授权返回的店铺 ID |
| `access_token_cipher`、`refresh_token_cipher` | TEXT NULL，密文；自用型没有刷新令牌 |
| `access_expires_at`、`refresh_expires_at` | TIMESTAMPTZ(3) NULL |
| `status` | VARCHAR(16) + `CHECK`：`UNAUTHORIZED` 未接入、`AUTHORIZED` 正常、`EXPIRED` 需要重新授权或重新录入、`FAILED` 续期或校验失败 |
| `authorized_at`、`authorized_by` | 最近一次授权或录入成功的时间与操作人 |
| `last_refresh_at` | 最近一次取得令牌的时间 |
| `last_check_at`、`last_check_ok` | 最近一次连通性检查（BOOLEAN NULL） |
| `last_error` | VARCHAR(500) NULL，英文摘要：错误码、`status_reason` 截断。**不得包含令牌、secret 或授权码** |

`refreshExpireTime` 的含义（绝对时间戳还是时长）在官方文档中没有说清：值大于 10^12 时按"绝对时间戳（毫秒）"处理，否则按"时长（毫秒）"处理；最终结果不晚于"授权时间 + 30 天"。在报告中写明，留待 M0 用真实账号核实。

### 4.3 两种接入模式

**① 自用型（`SELF_USE`）**

1. 超管为店铺录入自用型应用的 appkey 和 secret（secret 加密保存）。
2. 立即取一次令牌（第 4.0 节第 2 项）。成功后执行连通性检查（第 ③ 部分），状态设为 `AUTHORIZED`。
3. 自用型没有刷新令牌：令牌快到期时，重新调用取令牌接口。

**② 服务型（`SERVICE`）**

1. 本公司先配置服务型应用（`crm_weidian_app`）。
2. 超管对店铺发起授权：
   - 生成 32 位随机的 `[A-Za-z0-9]` `state`，存入 Redis：键 `weidian:oauth:state:{state}`，10 分钟过期，值为 `{tenantId, shopId, userId}`；
   - 返回授权地址；
   - 未配置服务型应用时，返回 `WEIDIAN_APP_NOT_CONFIGURED`（422）。
3. 回调（公开接口，标注 `@PublicEndpoint`），路径为 `/admin-api/integration/weidian/oauth/callback`：
   - 用 `GETDEL` **一次性**取出 `state`，不存在、过期或已用过一律失败；
   - 没有 `code`（用户拒绝）也视为失败；
   - 在 `state` 记录的公司范围内，用授权码换取令牌；
   - **核对返回的 `openid` 等于 `crm_shop.weidian_shop_id`**，不一致即拒绝，不保存令牌；
   - 通过后加密保存令牌，记录 `openid`、有效期，状态设为 `AUTHORIZED`，写审计日志；
   - 最后 302 跳转到**相对路径** `/product/shops?weidianAuth=success`，失败时为 `?weidianAuth=failed&reason={原因码}`。原因码为固定的英文枚举，例如 `STATE_INVALID`、`ACCESS_DENIED`、`TOKEN_EXCHANGE_FAILED`、`SHOP_MISMATCH`；
   - 响应中不带令牌或微店返回的原文。
4. 回调完整地址为 `{TCRM_ADMIN_BASE_URL}/admin-api/integration/weidian/oauth/callback`。
   - `TCRM_ADMIN_BASE_URL` 是新增的**必填**环境变量（https、不带末尾斜杠），校验方式同 `TCRM_LINK_BASE_URL`；
   - README 写明：要在微店开放平台把 admin 域名登记为回调域名。

**③ 共同规则**

1. **令牌中心**：所有令牌的获取和刷新都只经过一个组件，在店铺锁 `weidian:token:{shopId}` 内执行（第 4.0 节第 7 项）。拿到新令牌后立即加密保存。
2. **主动续期**（定时任务）：
   - 每小时运行一次，系统范围，跳过已停用的公司；
   - 访问令牌在 3 小时内到期的店铺进行续期：自用型重新取令牌，服务型用刷新令牌；
   - 刷新令牌已过期或返回 10023，状态设为 `EXPIRED`；10022、10029 等应用问题设为 `FAILED`；
   - 每家公司、每个店铺的处理互相独立，一个失败不影响其他。
3. **被动刷新**：接口返回 10013 或 10016 时，在锁内续期一次后重试一次。
   - **同一店铺 10 分钟内最多被动刷新一次**（用 Redis 键防抖），避免触及自用型每天 20 次的上限；
   - 返回 10021 或 10026 时不刷新，直接设为 `EXPIRED`。
4. **连通性检查**：调用 `vdian.item.list.get`（`page_num` 1、`page_size` 1）。
   - 调用成功即算连通；
   - 如果返回了商品并带有 `seller_id`，与 `crm_shop.weidian_shop_id` 比对，不一致时记录 `WARN` 并在 `last_error` 中说明。这只是提示，不阻断，因为 `seller_id` 是否等于店铺 ID 尚待 M0 核实；
   - 服务型以第 ② 部分的 `openid` 核对为准。

### 4.4 接口调用客户端（`integration`）

1. **入口**：例如 `WeidianClient.call(shopId, method, version, param)`，返回 `result` 节点。调用方处在公司范围内，客户端按 `shopId` 从令牌中心取令牌。
2. **请求**：按第 4.0 节第 8 项，POST，`application/x-www-form-urlencoded`，两个参数都做 URL 编码。
   - 网关和授权域名做成配置项 `crm.weidian.api-base-url`、`crm.weidian.oauth-base-url`，默认值为官方地址；
   - 测试中指向本地模拟服务。
3. **成功判断**：HTTP 200 且 `status.status_code` 为 0。
4. **错误分类与重试**：

| 类别 | 判断条件 | 处理 |
|---|---|---|
| 临时故障 | 网络异常、HTTP 5xx、10000、10001、10002 | 按 1 秒、2 秒、4 秒退避，最多重试 3 次 |
| 频率超限 | 10004、10005 | 同上 |
| 令牌失效 | 10013、10016 | 按第 4.3 节 ③ 第 3 条被动刷新 |
| 需重新授权 | 10021、10026 | 状态设为 `EXPIRED`，抛出明确异常 |
| 无权限 | 10006、10009 | 不重试，抛出明确异常（提示接口权限未开通） |
| 其他 | 其余错误码 | 不重试，抛出带错误码的业务异常 |

5. **限流**：按 appkey 用 Redisson 限流器控制 QPS，配置项 `crm.weidian.qps`，默认 5。拿不到许可时最多等待 10 秒，超时按频率超限处理。
6. **超时**：连接 5 秒，读取 30 秒。
7. **日志**：每次调用记录 `method`、店铺 ID、`status_code`、耗时、重试次数。**不记录令牌、secret、请求参数和返回内容。**
8. 订单的加密字段（买家信息）不解密，本任务不引入加解密 jar。

### 4.5 业务接口的类型化定义（`integration.api`）

按官方文档定义请求和返回结构，供后续的订单、退款、商品复制任务直接使用。**只实现"调用并解析"，不写任何同步或复制逻辑。**

| 分组 | 接口 |
|---|---|
| 订单 | `vdian.order.list.get` 订单列表、`vdian.order.get` 订单详情（含 `fans1_id`、`items`、`refund_info` 等，版本按调研报告取 2.0 加密版，如文档另有最新版本，取最新版并在报告中说明） |
| 退款 | `open.SellerQueryRefundList` 退款列表、`open.sellerQueryRefundDetail` 退款详情 |
| 商品读取 | `vdian.item.list.get` 店铺商品列表、`vdian.item.get` 单个商品、`vdian.item.getItemDetail` 商品详情、`weidian.cate.get.list` 店铺分类 |
| 商品写入 | `vdian.item.add` 添加商品、`vdian.item.saveItemDetail` 添加商品详情、`weidian.item.onSale` 上下架、`vdian.item.delete` 删除商品 |

要求：

1. 每个接口一个方法，请求和返回用 Java `record`；字段按官方"参数说明"和"返回字段说明"命名，Java 用驼峰，JSON 名用注解映射。
2. **金额用 `BigDecimal`，时间解析为 `Instant`**：文档中的时间是北京时间字符串，按 `Asia/Shanghai` 解析。
3. 文档未说明或含义不清的字段不要猜测：保留一个原始 JSON 字段（例如 `JsonNode raw`），在报告中列出。
4. 买家姓名、电话、地址等敏感字段**不建模**，原样留在 `raw` 中。日志中不得出现。
5. 每个接口都要有解析测试：用官方"返回结果示例"做测试样例，在测试中注明来源文档 ID 和版本。
6. 在报告中给出"接口清单"：方法名、文档 ID 和版本、所属权限分组（基础型或高级型，文档标题中有标注）。

### 4.6 管理接口

全部在 `/admin-api/integration/weidian/**` 下。回调以外的接口都要求本公司登录，平台管理员不可访问。

| 方法与路径 | 角色 | 说明 |
|---|---|---|
| `GET /app` | SUPER_ADMIN | 返回 `{ appKey: string\|null, secretConfigured: boolean, updateTime: string\|null }`，**不返回 secret** |
| `PUT /app` | SUPER_ADMIN | 请求 `{ appKey, appSecret }`，配置服务型应用 |
| `GET /shops` | SUPER_ADMIN、OPERATOR、FINANCE | 本公司全部未删除店铺的接入状态：`shopId`、`shopName`、`weidianShopId`、`authMode`、`status`（没有记录的店铺返回 `UNAUTHORIZED`）、各时间字段、`lastCheckOk`、`lastError` |
| `PUT /shops/{shopId}/self-use` | SUPER_ADMIN | 请求 `{ appKey, appSecret }`，录入自用型应用，并立即取令牌、执行检查（第 4.3 节 ①） |
| `POST /shops/{shopId}/authorizations` | SUPER_ADMIN | 服务型：返回 `{ authorizeUrl }` |
| `GET /oauth/callback` | 公开 | 第 4.3 节 ② 第 3 条 |
| `POST /shops/{shopId}/checks` | SUPER_ADMIN | 立即执行连通性检查，返回该店铺的最新状态行 |

- 其他公司的 `shopId` 一律返回 404。
- 新错误码登记在 `ErrorCode` 中，至少包括 `WEIDIAN_APP_NOT_CONFIGURED`、`WEIDIAN_SHOP_NOT_AUTHORIZED`、`WEIDIAN_CREDENTIAL_INVALID`（录入的自用型 appkey 或 secret 无法取得令牌）。HTTP 状态码按规范 03 选择。前端文案由后续界面任务添加。
- 审计日志中的 `appSecret` 必须脱敏：如果 `AuditSanitizer` 没有覆盖这个字段名，就补上并测试。

### 4.7 不在本任务范围

| 事项 | 归属 |
|---|---|
| 订单与退款同步、归因 | 订单任务，等客户确认 |
| 商品自动复制、Token 额度、复制池、自动清理 | 商品复制任务，等客户确认 |
| 微店接入的管理端界面 | 06d 管理端合并后的界面任务 |
| 加解密 jar、更换主密钥、站内消息通知 | 需要时另行评估 |

## 5. 执行步骤

1. 合并最新的 `main`；更新执行报告：删去第 1 版的停止说明，改为引用第 4.0 节。
2. `SecretCipher` 与 `TCRM_SECRET_KEY`（4.1）。
3. 迁移：两张表（4.2）。
4. 接口调用客户端：请求格式、错误分类、重试、限流（4.4）。
5. 令牌中心与两种接入模式、回调、连通性检查（4.3）。
6. 主动续期定时任务（4.3 ③ 第 2 条）。
7. 业务接口定义与解析测试（4.5）。
8. 管理接口（4.6）。
9. 文档、ADR 与执行报告（第 7 节）。

## 6. 测试要求

| 场景 | 期望 |
|---|---|
| 加密 | 加密后解密得到原文；同一明文两次加密结果不同；篡改密文或使用错误密钥时抛异常；未知版本前缀抛异常；密钥缺失或长度错误时启动失败 |
| 凭证保存 | 任何接口都不返回 secret 或令牌；数据库中为密文；审计日志中 `appSecret` 已脱敏；公司之间互相隔离 |
| 自用型 | 录入 → 取令牌 → 检查 → `AUTHORIZED`；appkey 或 secret 错误（10022）时返回 `WEIDIAN_CREDENTIAL_INVALID`，不保存为 `AUTHORIZED` |
| 服务型：正常流程 | 发起 → 模拟授权码 → 回调 → 令牌加密保存，`openid` 一致 → `AUTHORIZED` → 302 到成功地址；授权地址中的 `state` 只含字母数字 |
| 服务型：异常 | `state` 无效、过期或重复使用；没有 `code`；`openid` 与店铺不一致；用 A 公司的 `state` 不能写入 B 公司的店铺 |
| 回调响应 | 不含令牌，也不含微店返回的原文 |
| 客户端 | 请求格式符合第 4.0 节第 8 项（模拟服务器断言 `public`、`param` 已编码）；各类错误按第 4.4 节处理；退避时间可注入，测试中不真实 sleep；日志中没有令牌 |
| 被动刷新 | 返回 10013 时刷新并重试一次；10 分钟内对同一店铺只刷新一次；返回 10021 时设为 `EXPIRED` 且不刷新 |
| 限流 | 同一 appkey 的并发调用不超过配置的 QPS |
| 续期 | 两种模式都按时续期；刷新令牌失效时设为 `EXPIRED`；一家公司失败不影响另一家；已停用公司被跳过；续期与被动刷新互斥，不会用旧令牌覆盖新令牌 |
| `refreshExpireTime` | 绝对时间戳与时长两种取值都按第 4.2 节换算；最终结果不晚于授权时间 + 30 天 |
| 接口定义 | 第 4.5 节每个接口都用官方示例做解析测试；金额为 `BigDecimal`，时间按北京时间解析 |
| 权限 | 每个接口的角色限制；平台管理员 403；其他公司的 `shopId` 404 |

现有测试全部通过，不得删除或弱化。覆盖率门槛不降低。

## 7. 文档与报告

1. **ADR-016**《微店店铺授权与凭证加密》：记录两种接入模式、加密方案、`state` 设计、令牌中心与续期策略，以及放弃的方案（例如把令牌放在环境变量、明文存库、引入配置中心、只支持一种模式）。
2. **设计说明书**：
   - §3.4 凭证一段改为现状；
   - §5.2 新增两张表；
   - §6.2 新增接口；
   - §7.1 写明两种接入模式、客户端、错误分类、限流与续期；
   - §9 加入令牌续期任务；
   - §12 加入两个新环境变量。
3. **`deploy/README.md`**：
   - 两个新变量的说明；
   - 服务型需要登记回调域名；
   - 主密钥的备份要求。
4. **执行报告** `docs/04-开发任务/09a-微店接入基础-执行报告.md`：
   - 第 4.0 节与官方文档的核对结果（如有矛盾，逐项列出）；
   - 每条设计决定的落实位置；
   - 第 4.5 节的接口清单，以及未建模、保留在 `raw` 中的字段；
   - 系统范围的使用位置及理由；
   - 迁移锁表评估；
   - 测试清单与结果；
   - **拿到真实账号后需要做的冒烟测试步骤**（M0 用），包括核实 `refreshExpireTime` 的含义、`openid` 与店铺 ID 是否一致、`seller_id` 与店铺 ID 是否一致；
   - 偏离与遗留事项。

## 8. 禁止事项与停止条件

**禁止**：

- 编造第 4.0 节和官方文档以外的接口地址、参数或错误码；
- 把令牌、secret、授权码、主密钥写进日志、异常、响应或代码仓库；
- 新增依赖；
- 修改已合并的迁移；降低覆盖率门槛，放宽 ArchUnit 规则，`@Disabled` 或弱化测试；
- 修改 `admin-ui/`、`kol-portal/`、`redirect/` 或任务索引；
- 推送 `main` 或合并 PR；使用 `push --force`、`reset --hard`。

**遇到以下情况停下来**：推送已完成的提交，保持 PR 标题中的 `[WIP]`，在执行报告中说明后结束。

- 第 2 节第 5 条的文档数据接口无法访问，导致第 4.5 节的接口无法定义。此时第 4.1～4.4、4.6 节照常完成，只把 4.5 中缺失的接口列为遗留；
- 设计说明书、本提示词、规范之间存在冲突；
- 验证在合理排查后仍然失败。
