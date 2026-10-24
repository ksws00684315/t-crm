# SparkTrack 表格脚本（脱离旧系统版）

本目录是交付给客户的 Google 表格脚本，以客户现用的 Apps Script 3.0.5（`docs/00-需求/客户补充资料-1015/prod5(2)/`，git 忽略目录）为基础生成。**不再调用旧系统和任何第三方服务**，全部外部调用都指向本系统（SparkTrack CRM）。本 README 只描述差异，不出现旧系统与第三方的具体地址；按任务验收要求，在本目录内 grep 旧系统/第三方地址关键字为零命中。

| 文件 | 说明 |
|---|---|
| `SparkTrack-3.0.5.gs` | 脚本主文件（Apps Script 脚本） |
| `submitFrom.html` | 「一键替换链接(V2)」弹窗（与原件一致） |
| `weidianOriginalForm.html` | 「转换为微店原始链接」弹窗（与原件一致） |
| `buyLinkConvertForm.html` | 「buy 链接互转」弹窗（与原件一致） |

## 安装步骤

1. 打开客户的 Google 表格，菜单「扩展程序 → Apps Script」。
2. 把 `SparkTrack-3.0.5.gs` 的内容粘贴到默认脚本文件（可重命名为 `SparkTrack-3.0.5`）。
3. 依次「文件 → 新建 → HTML」，分别创建 3 个 HTML 文件并粘贴对应内容，名字必须正好是 `submitFrom`、`weidianOriginalForm`、`buyLinkConvertForm`（脚本按名字引用）。
4. 左侧「项目设置」，在**脚本属性**中新增：
   - `TALENT_CRM_API_BASE_URL` ＝ 本系统后台地址，例如 `https://admin.<正式域名>/admin-api`。
5. 保存后回到表格，刷新页面会出现「链接工具」菜单：🔗 一键替换链接(V2)、转换为微店原始链接、buy 链接互转（选区）。

> **默认接口地址**：`SparkTrack-3.0.5.gs` 顶部的 `PRODUCT_LINK_API_DEFAULT_BASE_URL` 当前指向 `https://admin.river0413.top/admin-api`（预演环境）。**正式环境必须**设置脚本属性 `TALENT_CRM_API_BASE_URL` 为正式域名，或把该常量改为正式地址。

## 使用要点

- 「一键替换链接(V2)」需要填写**复制令牌**（管理端「复制令牌」页为达人生成）、邀请码并选择链接模板；脚本每批 50 个单元格调用一次任务解析接口。
- 公式函数保留原名与参数个数，客户表格中已有公式不用改：
  - `=GET_PRODUCT_ID(令牌, 达人ID, 链接单元格, [加价])`
  - `=GET_CHANNEL_PRODUCT_ID(令牌, 店铺ID, 渠道ID, 链接单元格, [加价])`
  - `达人ID / 店铺ID / 渠道ID / 加价` 只是占位，**不再参与请求**：达人与目标店铺由令牌决定，原价复制（ADR-019）。
- **重新计算是安全的**：对已复制过的商品，后端直接返回已有的复制商品 ID（状态 `already_applied` 时不重复复制、不扣额度）；单个自定义函数最长等待 25 秒，超时返回「⏳ 复制处理中，请稍后重新计算」，稍后重新计算即可。
- 「一键替换链接(V2)」遇到取不到商品 ID 的短链时，会调用本系统 `GET /admin-api/external/short-urls/resolve` 还原最终地址；短链域名必须在后台「系统配置」的 `link.short-url.allowed-hosts` 白名单内（默认含脚本里 29 个代购平台域名和微店相关域名，平台管理员可修改）。

## 与原件的差异清单

3 个 HTML 文件与原件**逐字节一致**；以下差异全部集中在 `SparkTrack-3.0.5.gs`（可用 `diff` 核对）。

### 1. 接口地址

| 位置 | 原件 | SparkTrack 版 |
|---|---|---|
| `PRODUCT_LINK_API_DEFAULT_BASE_URL` | 旧系统的复制接口地址（见原件第 11 行） | `https://admin.river0413.top/admin-api`（附注释：正式环境用脚本属性覆盖） |
| `var baseUrl` | 旧系统地址（见原件第 10 行） | **删除** |

### 2. `syncGetRequest` 改调本系统短链解析

- 请求改为 `productLinkApiBaseUrl_() + "/external/short-urls/resolve?url=" + encodeURIComponent(shortUrl)`；
- 响应按本系统统一信封解析：`code` 为 0 时返回 `data.url`（最终地址），调用方（V2 主流程的 `resolveSourceProductInfo_`）逻辑不变；
- 失败文案沿用原来的「❌ …」格式（`❌ API返回错误: …`、`❌ API请求失败: …`）；原「API未返回产品ID」改为「API未返回地址」以匹配新语义；
- 删除两处 `console.log` 调试日志（会输出带邀请码的完整链接）。

### 3. 两个公式函数改走复制接口

- `GET_PRODUCT_ID`、`GET_CHANNEL_PRODUCT_ID`：保留函数名与参数个数；内部新增共用函数 `fetchCopiedProductId_`，先用令牌调用 `POST /external/product-links/convert` 提交 1 条（来源渠道按原逻辑识别，默认 `WEIDIAN`），再轮询 `POST /external/product-links/jobs/{jobCode}/items/resolve`：
  - `success` → 返回复制后的商品 ID；`already_applied` → 返回原商品 ID；
  - `failed` / `conflict` / `not_found` → 「❌ 原因」；总时长超过 25 秒 → 「⏳ 复制处理中，请稍后重新计算」；
  - 链接中识别不到商品 ID 时直接返回「❌ 未能从链接中识别商品ID」（原来会把空 ID 发给旧系统）。
- 删除了原来对 `businessId` / `appId` / `channelId` 的数字类型校验：这些参数已不参与请求，仅保留参数位（注释中说明）。
- V2 主流程已有的 `productLinkApiFetch_` 等工具函数原样复用。

### 4. 删除清单（不可达且调用外部服务的遗留函数）

删除前已用调用关系确认：以下函数既不在菜单（`onOpen`）、也不被 3 个 HTML 的 `google.script.run` 调用、不是表格公式函数、也不被其他保留函数调用。

| 删除的函数 | 原因 |
|---|---|
| `extractLinksFromSelection` | 遗留函数（未挂菜单），失败时调用旧系统短链解析 `syncGetRequest` |
| `replaceProductLinks` | 遗留函数（未挂菜单），同样调用旧系统短链解析 |
| `convertToShortUrl`、`requestShortLink` | 调用第三方短链生成服务，服务不可达；`requestShortLink` 还引用旧系统解析 |
| `priceWithdrawal`、`fetchPriceFromAPI` | 调用旧系统查价接口，服务不可达 |
| `getUrlByCell` | 只被 `priceWithdrawal` 使用的辅助函数 |
| `processGetImages` | 调用旧系统图片接口 |
| `processImageRemoveBackground`、`processImageAddBackground` | 调用旧系统图片处理服务（取上传令牌与两张处理接口） |

未删除的孤儿工具函数（如 `extractTenDigitProductId`、`generateProductLinks`、`getColumnName` 等）：不依赖任何外部服务，为兼容客户可能的直接调用而原样保留，其中 `extractTenDigitProductId` 也可作为普通公式使用。

### 5. 验证

- 在本目录对所有文件 grep 旧系统与第三方地址关键字（任务提示词 §3.5 的三个关键字）结果为零；
- 3 个 HTML 与原件 `cmp` 逐字节一致；
- `.gs` 除上述清单外与原件逐行一致（`diff` 核对）；
- 语法检查：`cp SparkTrack-3.0.5.gs /tmp/check.js && node --check /tmp/check.js`。
