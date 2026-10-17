# 任务 26（后端 + 管理端 + 达人端）：上传推广表关联达人商品、达人端「我的商品」

> 本文件是交给编码代理执行的提示词，把全文作为任务输入即可。
>
> **执行环境**：
> - 本机执行时，独立工作树 `.worktrees/26-promo-sheet-upload`，分支 `task/26-promo-sheet-upload` 已从最新的 `main` 检出；
> - 非本机执行时，自行 `gh repo clone ksws00684315/t-crm`，从最新的 `origin/main` 检出分支 `task/26-promo-sheet-upload`。
>
> **并行说明**：与 25a、25b 同时进行。25a 改达人表与编号格式，不改 `crm_kol_item`；本任务改 `crm_kol_item`。语言包、设计说明书冲突时保留双方。迁移版本号使用 `V202610181300`～`V202610181309`。

>
> **CI 说明（2026-10-18 起）**：GitHub Actions 暂停使用，**不要等待** `gh pr checks`。验收以本地全量检查为准：必须在本机实际跑完本提示词要求的全部检查（后端含 `mvn verify -Pdocker-it`，需要 Docker），并把命令、结果（测试数量、失败数）贴进 PR 描述和执行报告；本机缺 Docker 等条件时，在报告中写明哪些没跑，由架构负责人在本地补跑。

---

## 1. 背景与必读

1. `AGENTS.md`、`docs/05-工程规范/`（重点 03、04、05、06）。
2. **[ADR-024](../06-架构决策/ADR-024-上传推广表关联达人商品.md)（本任务的设计依据）**、ADR-021 第 5 条（链接解析规则）、[1017 确认事项与变更清单](../00-需求/客户补充资料-1017/1017确认事项与变更清单.md) R10～R12。
3. 设计说明书 §4.3、§4.4 第 5 条、§5.2 `crm_kol_item`。
4. 现有代码：
   - `product.service.SheetLinkParser`（任务 07，解析代购平台包装、店铺号、商品 ID、非微店平台）；
   - `crm_kol_item` 与对应关系服务（任务 05）、订单归因中按复制商品 ID 匹配达人的逻辑（任务 13）；
   - 跳转服务的 `/i/{复制商品 ID}`（ADR-019）、`crm.link.base-url`；
   - 管理端达人详情抽屉、对应关系页；达人端导航 `layout/nav.ts`、我的订单页（任务 19b 的写法）。
5. **推广表样例**：负责人本机的 `docs/00-需求/客户补充资料-1015/测试专用【达人名称A】.xlsx`（241 MB，不在仓库中）。结构见 ADR-024「背景」：
   - 一个工作表 `ARCHIVE style`，第 2 行为表头 `PHOTO / NAME / CW / PRICE (USD) / LINK / …`；
   - E 列显示「LINK」，实际是单元格超链接：`https://item.kakobuy.com/item/details?url=https%3A%2F%2Fshop1856156775.v.weidian.com%2Fitem.html%3FitemID%3D7849640325%26spider_token%3D…`；
   - 另有分类行、`RETURN` 行（指向表内 `'ARCHIVE style'!A1`）、Discord 链接、重复商品。

   测试时用代码生成的小样本（POI 写入超链接、内嵌一张较大的图片），不要把大文件提交到仓库。

## 2. 执行环境与交付

- 后端 JDK 21：`mvn spotless:apply`、`mvn verify`、`mvn verify -Pdocker-it` 全部通过（本机没有 Docker 时在报告中写明，由架构负责人本地补跑）。
- 前端 Node 24：两个前端都执行 `pnpm install --frozen-lockfile && pnpm lint && pnpm typecheck && pnpm test && pnpm build && pnpm notices:check`。
- 本机验证（本机执行时）：后端 18262、管理端 Vite 8829、达人端 Vite 8830、PostgreSQL 55462、Redis 46462，Compose 项目名 `tcrm-26`。避开 18081、8848、8849、55432、46391。
- 推送分支，按 PR 模板创建 PR，PR 描述中贴出本地检查结果。**不要合并 PR，不要推送 `main`。不新增依赖**（XML 用 JDK 自带的 StAX，ZIP 用 `java.util.zip`）。

## 3. 已定的设计决定（不要自行改变）

### 3.1 数据（迁移 `V202610181300`）

- `crm_kol_item.product_id` 改为可空；新增 `item_name VARCHAR(255)`（商品名称快照，可空）与 `source VARCHAR(16) NOT NULL DEFAULT 'IMPORT'`（`IMPORT` 原有导入 / `SHEET_UPLOAD` 上传推广表 / 以后 17b 的 `COPY`）。
- 现有唯一索引不变：`(shop_id, weidian_item_id) WHERE valid_to IS NULL` 保证一个商品只属于一位达人；`(kol_id, product_id) WHERE valid_to IS NULL` 对空值不生效，符合预期。
- 依赖 `product_id` 的查询（对应关系列表、导出、原商品统计、链接生成）处理空值；`/t/{达人编号}/{原商品编码}` 链接只对有原商品的关系生成。

### 3.2 上传与解析

- 接口（权限 `kol:edit`；数据范围为「本人」时只能上传给名下达人；达人必须未删除）：

  ```text
  POST /admin-api/kol/kols/{id}/promo-sheet-uploads           multipart file → 预览
  POST /admin-api/kol/kols/{id}/promo-sheet-uploads/{uploadId}/confirm  → 结果
  ```

- **只为这个接口**放宽上传大小到 300 MB：
  - Spring multipart 上限调整，同时把 `file-size-threshold` 设得很小，使上传内容写到临时文件而不是留在内存；其他上传接口仍按各自业务上限校验，并补测试证明这些上限没有变化；
  - Nginx 只对这一个路径放宽 `client_max_body_size 300m`，写进 `deploy/` 的配置与说明。
- 解析（新类，放在 `kol` 模块，例如 `PromoSheetReader`）：
  - 用 `java.util.zip.ZipFile` 打开临时文件，只读取 `xl/workbook.xml`、`xl/sharedStrings.xml`、`xl/worksheets/sheet*.xml`、`xl/worksheets/_rels/sheet*.xml.rels`；**不读取 `xl/media`**；
  - StAX 流式读取，**关闭 DTD 和外部实体**（防 XXE）；读取的每个条目解压后不超过 64 MB，超过即拒绝（防 ZIP 炸弹）；
  - 取得每个工作表中：单元格超链接（`<hyperlink r:id>` → rels 中的外部地址；`location` 属性的表内跳转计为「表内跳转」并跳过）、`HYPERLINK` 公式、以 http 开头的文本；
  - 若某行有表头 `NAME` / `名称` 列，记录同一行的名称作为 `item_name` 快照；
  - 不是 `.xlsx`、读不出工作表时返回 400 `KOL_PROMO_SHEET_INVALID`。
- 链接识别：调用 `SheetLinkParser`（ADR-021 第 5 条）。需要时可以把它挪到双方都能依赖的位置（例如 `product.api`），但不得改变它的规则与已有测试。

### 3.3 关联规则与预览

| 情况 | 分类 |
|---|---|
| 店铺号不是本公司已登记、启用的店铺 | 跳过：店铺未登记 |
| 非微店平台、取不到商品 ID、表内跳转、非商品链接 | 跳过（分原因计数） |
| 同一商品 ID 在表中多次出现 | 只算一次 |
| 商品 ID 尚未关联任何达人 | 将新增 |
| 已关联本达人 | 已存在 |
| 已关联其他达人 | 冲突（不覆盖）。商务只看到「已属于其他达人」；超管看到对方编号与名称 |
| 该达人已有、但本次表中没有的对应关系 | 只计数报告「表中未出现」，不结束 |

- 预览结果放 Redis，键为随机 `uploadId`，30 分钟过期，绑定公司、达人和上传人；确认时校验三者一致，过期返回 410 `KOL_PROMO_UPLOAD_EXPIRED`。**不保存文件本身**；临时文件处理完立即删除（包括异常路径）。
- 预览响应：`uploadId`、`fileName`、各类数量、将新增的商品清单（店铺、商品 ID、名称快照、能找到的原商品编码）、冲突清单、跳过原因与样例（工作表、行号、原因，最多 50 条）。
- 确认时**重新校验**冲突（预览之后可能有变化），写入将新增的关系：`source = SHEET_UPLOAD`、`valid_from = now`、能按「店铺 + 商品 ID」找到原商品时填 `product_id`；写操作日志（达人、文件名、各类数量），登记链接索引刷新（ADR-013，如该关系需要）。
- **不改达人的推广状态**（ADR-023）。

### 3.4 达人端「我的商品」

- 接口 `GET /kol-api/items?page&size&keyword`：本人有效的对应关系；每行：`weidianItemId`、`itemName`（优先原商品名称，没有则用快照）、`imageUrl`（原商品有图时）、`transitUrl`（`{crm.link.base-url}/i/{weidianItemId}`）、`createTime`。身份与公司只从 KOL 令牌取。
- 页面 `/items`，导航加「我的商品」（`nav.ts` 追加一项，保留已有项）；列表、搜索、复制链接按钮；375px 不溢出；空状态说明「商务完成推广配置后，你的商品会显示在这里」。

### 3.5 管理端

- 达人详情抽屉与达人列表行操作增加「上传推广表」：选择文件（只接受 `.xlsx`，前端提示上限 300 MB）→ 上传进度 → 预览（数量卡片 + 新增、冲突、跳过三个标签页）→ 确认写入 → 结果；
- 对应关系页：原商品为空时显示「未关联原商品」，可按来源筛选；
- 语言包中英文都有，英文用 Creator 术语。

## 4. 测试要求

| 场景 | 期望 |
|---|---|
| 解析 | 单元格超链接、`HYPERLINK` 公式、文本链接；代购平台包装解开；表内跳转、Discord、淘宝等分类跳过；重复去重；名称快照 |
| 安全 | 含外部实体的 XML 被拒绝；超限条目被拒绝；非 xlsx 400；临时文件在成功与失败后都被删除；不读 `xl/media`（用含大图片的样本证明耗时与内存不随图片增长） |
| 关联 | 新增、已存在、冲突、店铺未登记、表中未出现；确认时冲突的重新校验；不改推广状态 |
| 预览 | 过期 410；换人、换达人、换公司确认被拒 |
| 上限 | 该接口接受接近 300 MB 的文件（集成测试可用稀疏文件或流式生成，不必真造大文件，说明方法）；其他上传接口上限不变 |
| 权限与隔离 | 本人范围、跨公司、冲突中对方信息的可见性 |
| 我的商品 | 只看本人；原商品为空时用快照；链接格式 |
| 前端 | 上传、预览、确认流程；我的商品页；对应关系页空原商品 |
| 接口清单 | `ApiCatalogConsistencyTest` 通过 |

**浏览器验证**（写进报告并附截图）：用负责人提供的真实样例或按其结构生成的样例走完上传 → 预览 → 确认 → 达人端「我的商品」。真实样例只在本机使用，不提交。

## 5. 文档与报告

- 设计说明书：§4.4 第 5 条细节、§5.2 `crm_kol_item`、§6.2 / §6.3 接口（登记到标记区间）；`deploy/` 的上传大小说明。
- 执行报告 `docs/04-开发任务/26-推广表上传与我的商品-执行报告.md`：解析规则、安全措施、测试、截图、偏离与遗留。

## 6. 禁止事项与停止条件

**禁止**：把上传文件存进数据库或长期保存；新增依赖；放宽其他上传接口的上限；改变 `SheetLinkParser` 的规则；修改已合并的迁移脚本；删除或弱化测试；推送 `main`、合并 PR；`push --force`、`reset --hard`、改写已推送历史；提交客户样例文件。

**停下来报告**（推送已完成的提交，以 `[WIP]` 标题创建 PR）：样例结构与 ADR-024 描述不符且影响规则；300 MB 上传在现有部署结构下无法安全实现；合理排查后验证仍失败。

完成后回复一段总结：PR 链接、做了什么、测试结果、遗留。
