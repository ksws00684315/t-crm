# 任务 57：表格脚本脱离旧系统（后端短链解析 + SparkTrack 版脚本）

> **执行环境**：本机。工作树 `/Users/hh-mini/Public/dev/t-crm/.worktrees/57-sheet-script-standalone`，分支 `task/57-sheet-script-standalone`，已从最新 `main` 检出。后端端口 `TCRM_ADMIN_PORT=18571`。**不新增迁移**：系统配置的默认值在代码 `SystemConfigDefinition` 中定义，`sys_config` 只保存修改过的值。
>
> **先完整阅读 [`本机任务通用要求.md`](本机任务通用要求.md)**，以及 [ADR-019](../06-架构决策/ADR-019-兼容客户表格脚本的商品复制接口.md)、规范 03（接口）、规范 06（安全）。

## 1. 背景与负责人决定（2026-10-23）

本系统与客户的旧系统没有任何关联，交付给客户的表格脚本不能再调用旧系统。

客户脚本原件在 `/Users/hh-mini/Public/dev/t-crm/docs/00-需求/客户补充资料-1015/prod5(2)/`，这个目录被 git 忽略：
- `3.0.5.gs`
- `submitFrom.html`、`buyLinkConvertForm.html`、`weidianOriginalForm.html`

**只读**这个目录，不要修改原件。只把复制接口地址换成本系统的版本，在同目录的 `sparktrack/` 子目录中，供参考。

架构负责人梳理出脚本对外部服务的调用如下：

| 外部接口 | 调用位置 | 是否可达 |
|---|---|---|
| `https://panda.welcometopanda.com/system/shortUrlResolver/get?shortUrl=`（旧系统短链解析，返回 `{msg: 真实地址}`） | `syncGetRequest`。「一键替换链接(V2)」在链接中取不到商品 ID 时调用（约第 1433 行）；另有 3 个遗留函数调用 | **可达** |
| `https://panda.welcometopanda.com/weidianCallBack/saveWdProductV4`（旧版复制） | 公式函数 `GET_PRODUCT_ID`、`GET_CHANNEL_PRODUCT_ID`，写在单元格里使用 | **可达**：客户仍在使用 |
| `${baseUrl}/system/shortUrlResolver/getProductPrice`（旧系统查价格） | `fetchPriceFromAPI` ← `priceWithdrawal` | 不可达（遗留代码） |
| `https://api.xcnbuy.com/urlShortChainsInfo/generateShortChains`（第三方生成短链） | `requestShortLink` ← `convertToShortUrl` | 不可达（遗留代码） |

负责人决定：
1. 本系统实现短链解析；
2. 两个公式函数保留，改走本系统现有的复制接口；
3. 可解析的短链域名默认包含代购平台和微店的域名，平台管理员可以在系统配置中修改；
4. 交付一份不再依赖旧系统、也不依赖其他第三方服务的「SparkTrack 版」脚本。

## 2. 后端：短链解析接口（`product` 模块，与 `ProductLinkController` 同类）

- 接口：`GET /admin-api/external/short-urls/resolve?url=…`，公开接口（`@PublicEndpoint`），不需要令牌，复用 `ProductLinkRateLimiter` 按 IP 限流。返回统一信封，`data` 为 `{ "url": "最终地址" }`。
- 行为：
  1. 只接受 `http`、`https`，地址长度不超过 2048；
  2. 主机必须在白名单中（完全匹配，或是白名单域名的子域名），否则返回明确的错误码，在 `shared.ErrorCode` 中登记，例如 `SHORT_URL_HOST_NOT_ALLOWED`；
  3. **手动跟随重定向**（不要让 HTTP 客户端自动跟随），最多 5 跳；每一跳的目标主机都要再次检查白名单；
  4. **防服务器端请求伪造（SSRF）**：每次请求前解析域名，任一地址是回环、内网、链路本地、组播或保留地址时拒绝；连接后核对实际连接的地址，防止 DNS 重绑定；
  5. 连接超时 3 秒、读取超时 5 秒；只读响应头，不读取响应体（或最多读取少量字节后立即关闭）；用 `HEAD`，遇到 405 时改用 `GET`；
  6. 返回最后一跳的地址。原始地址没有发生重定向时，原样返回；
  7. 日志只记录主机名和结果，不记录完整地址的查询参数（可能含邀请码）。
- **白名单配置**：在 `SystemConfigDefinition` 中新增 `link.short-url.allowed-hosts`（分组按现有 `url` 或 `business` 选，类型 `string`，逗号分隔，最长 2048）。默认值包括：
  - 脚本中 29 个代购平台的域名（见脚本第 534～562 行的列表与 `getTemplateUrl` 一类函数）；
  - 微店相关域名：`weidian.com`、`vdian.com`、`youshop10.com`、`koudai.com`。
  
  读取时去空格、转小写、去掉开头的 `*.`。平台管理员在「系统配置」页修改后立即生效。任务 53 已把这一页改成键值表，新配置项会自动出现在表中，前端不用改。
- 测试：
  - 白名单匹配（完全匹配、子域名，以及 `evilweidian.com` 不能误匹配）；
  - 非 http(s) 协议、超长地址；
  - 内网与回环地址被拒；
  - 重定向链中途跳到白名单外被拒；
  - 超过 5 跳被拒；
  - 没有重定向时原样返回；
  - 超时；
  - 配置修改立即生效；
  - 控制器的限流与错误码。
  
  用本地桩服务器或模拟 HTTP 客户端，**测试不得访问外网**。同步设计说明书 §6 的接口表（`ApiCatalogConsistencyTest` 会检查），以及系统配置清单。

## 3. SparkTrack 版脚本（仓库新目录 `deploy/sheet-script/`）

以客户原件 `3.0.5.gs` 和 3 个 HTML 文件为基础，生成 `deploy/sheet-script/SparkTrack-3.0.5.gs` 和同名的 3 个 HTML 文件，并写一份 `deploy/sheet-script/README.md`（安装步骤、需要设置的脚本属性、与原件的差异清单）。修改范围：

1. **接口地址集中到一处**：`PRODUCT_LINK_API_DEFAULT_BASE_URL` 默认改为 `https://admin.river0413.top/admin-api`，并在 README 中写明正式环境要改成正式域名，或者设置脚本属性 `TALENT_CRM_API_BASE_URL`。删除变量 `baseUrl`（旧系统地址）。
2. **`syncGetRequest`**：改为调用 `productLinkApiBaseUrl_() + "/external/short-urls/resolve?url=" + encodeURIComponent(…)`，按本系统信封（`code` 为 0 时取 `data.url`）解析。失败时的返回文字沿用原来的「❌ …」格式，调用方的逻辑不变。
3. **`GET_PRODUCT_ID`、`GET_CHANNEL_PRODUCT_ID`**：保留函数名和参数个数，客户表格中已有的公式不用改；内部改为调用本系统复制接口，复用脚本中 V2 流程已有的 `productLinkApiFetch_` 等工具函数：
   - 用第一个参数 `token`（复制令牌）调用 `POST /external/product-links/convert`，提交 1 条（来源渠道按原逻辑识别，默认 `WEIDIAN`），拿到 `jobCode`；
   - 再用 `POST /external/product-links/jobs/{jobCode}/items/resolve` 轮询，总时长控制在 25 秒内（Google 自定义函数上限 30 秒）。`success` 返回新商品 ID；`already_applied` 返回原 ID；`failed`、`conflict`、`not_found` 返回「❌ 原因」；超时返回「⏳ 复制处理中，请稍后重新计算」；
   - `businessId`、`appId`、`channelId`、`markUp` 参数保留在签名中但不再使用（达人与目标店铺由令牌决定，原价复制，见 ADR-019），在函数注释中说明；
   - 重新计算时再次调用是安全的：后端对已有对应关系直接返回已有 ID，不重复复制、不扣额度。在 README 中说明这一点。
4. **删除不可达且调用外部服务的遗留函数**：`fetchPriceFromAPI`、`priceWithdrawal`、`requestShortLink`、`convertToShortUrl`，以及只被它们使用的辅助函数。删除前用调用关系确认：既不被菜单（`onOpen`）、HTML 中的 `google.script.run`、公式函数调用，也不被其他可达函数调用。另外 3 个调用 `syncGetRequest` 的遗留函数（约第 43、410 行起），如果同样不可达，一并删除；可达的保留。README 中列出删除清单和理由。
5. **验收标准**：
   - `grep -n "welcometopanda\|xcnbuy\|w2collab"` 在 `deploy/sheet-script/` 中结果为零；
   - 其余代码与原件逐行一致（README 的差异清单可以用 `diff` 核对）；
   - 用 `node --check` 做语法检查（先复制成 `.js`），或用同等方式确认语法正确。

## 4. ADR 与文档

- ADR-019 文末补「2026-10-23 修订」：
  - 交付客户的是 SparkTrack 版脚本，不再是「脚本代码不改」；
  - 新增短链解析接口与域名白名单；
  - 两个公式函数改走复制接口。
- 设计说明书相应位置同步。

## 5. 验证

- 后端：`mvn -B clean`、`spotless:check`、`verify -Pdocker-it`，全部通过，不加测试过滤。检查交给子代理执行。
- 本机联调：用 18571 端口启动后端，连接共享中间件（`deploy/.env.local` 只读），用 `curl` 验证：
  - 一个白名单内的真实短链（例如微店分享短链，没有就用一个会 301 到 `weidian.com` 的地址）；
  - 一个白名单外的地址（被拒）；
  - `http://127.0.0.1` 和 `http://169.254.169.254`（被拒）。
  
  结果写进执行报告。**不要修改共享库中的任何配置，也不要生成或使用复制令牌**：脚本在 Google 表格中的实测由负责人进行。
- 不得停止或删除共享中间件，不得 `down -v`，不得重建负责人的预览。

## 6. 交付

执行报告（含逐条复核表、脚本调用关系表、删除清单），提交 PR。不要合并、不要推 main、不要改任务索引。GitHub Actions 已暂停，不要等待 `gh pr checks`。
