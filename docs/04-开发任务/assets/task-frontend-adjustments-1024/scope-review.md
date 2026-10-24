# 1024 前端调整范围审计

核对日期：2026-10-24。只读核对源表 `D:/1-达人分销CRM/5、进度表/1024前端调整内容.xlsx` Sheet1 第 3—9 行；代码以本工作树当前版本为准。本报告不代表已完成实施或浏览器验收，未修改任何业务代码、后端、数据库或接口。

## 一、全系统达人信息展示迁移清单（D3）

目标：姓名在上、深色正文；达人编号在下、浅灰；两行居中、紧凑。以下路径除明确标注外均相对 `admin-ui/src/`。

| 文件与当前位置 | 当前展示 | 数据是否齐全 / 建议 |
|---|---|---|
| `views/kol/index.vue:156-157,380` | 姓名和编号分为两列 | `KolVO.name/kolCode` 齐全，可合并两行展示 |
| `views/kol/KolDetailDrawer.vue:152-156` | 编号、姓名分开描述项 | `KolVO` 齐全，可合并身份展示 |
| `views/kol/KolManageTab.vue:97,217` | 选择器单行 `formatNameWithCode` | `KolVO` 齐全，下拉选项可自定义两行；保留选中标签纯文本以避免输入控件变高 |
| `views/kol/CleanupConfigDialog.vue:110-111` | 编号加姓名单行 | 属性 `kol` 齐全，可改双行 |
| `views/kol/KolLinksDrawer.vue:19-20` | 标题拼接编号和姓名 | 已传 `kolCode/kolName`；可在内容区显示双行身份，标题仍保持可访问纯文本 |
| `views/kol/CommissionDialog.vue:90` | 标题拼接编号和姓名 | `kol.name/kolCode` 齐全，同上 |
| `views/kol/KolProfileDialog.vue:36-37,165-171` | 编辑标题和独立编号/姓名字段 | 数据齐全；编辑字段应保留原用途，可额外统一身份区，不合并可编辑字段 |
| `views/kol/PromoSheetUploadDialog.vue:177` 及 `KolDetailDrawer.vue:316`、`views/product/kol-item/index.vue:244` | 仅接收已拼接 `kolLabel` 字符串 | 调用方都有姓名和编号；可改本地组件 props 传独立值，不涉及 HTTP 接口 |
| `views/product/copy-token/index.vue:361-364` | 姓名加编号标签 | `CopyTokenVO.kolName/kolCode` 齐全，可直接替换双行 |
| `views/product/copy-token/index.vue:119-123,300` | 筛选标签/下拉选项单行 | `KolVO` 齐全，可改下拉选项双行，保留筛选 chips 纯文本 |
| `views/product/copy-token/CopyTokenCreateDialog.vue:169-170,263` | 选择器标签单行 | `KolVO` 齐全；预设 `presetKolLabel` 由 `KolDetailDrawer.vue:310` 拼接，需调用方改传独立 props |
| `views/product/kol-item/index.vue:62-64` 及达人列插槽 | 达人单行 | `KolItemVO.kolName/kolCode` 齐全，可改双行 |
| `views/product/kol-item/index.vue:433,594` | 筛选、上传选择器单行 | `KolVO` 齐全，可改下拉选项 |
| `views/product/kol-item/KolItemDetailDrawer.vue:58-59,97` | 描述区单行 | `KolItemDetailVO` 字段齐全，可改双行 |
| `views/product/copy-job/index.vue:100-102` | 达人内部 ID、编号、名称三个默认隐藏列 | `CopyJobVO.kolName/kolCode` 齐全，身份列可合并为双行；不能把数据库 `kolId` 当显示用达人编号 |
| `views/order/index.vue:91-95`、达人列插槽 | 达人链接/纯文本单行 | `OrderVO.kolName/kolCode` 齐全，保留点击详情和权限行为，仅替换内部显示 |
| `views/order/unmatched.vue:360-361,405` | 达人编号加姓名；分配达人下拉单行 | `OrderVO`、`KolVO` 齐全 |
| `views/order/OrderDetailDrawer.vue:106,170-171` | 主单和归属明细单行 | `OrderDetailVO`、明细归属 DTO 均有姓名/编号，可双行 |
| `views/order/MatchEvidenceDrawer.vue:151-152` | 映射达人单行 | 映射 DTO 姓名/编号齐全 |
| `views/order/chain/index.vue:115,181-182` | 主单与链路关联达人单行 | 详情、链接 DTO 姓名/编号齐全 |
| `views/order/refund/index.vue:62-66,342` | 达人单行 | `RefundVO` 姓名/编号齐全 |
| `views/order/refund/RefundDetailDrawer.vue:92,141-142` | 主单和子行达人单行 | 详情、归属子行 DTO 姓名/编号齐全 |
| `views/order/OrderLines.vue:25,51` | 展开订单行只显示达人编号 | **OrderItemVO、后端 OrderItemDTO 均无 kolName，见后端缺口** |
| `views/fund/prepay/index.vue:43-44` | 编号、姓名分两列 | `PrepayVO.kolName/kolCode` 齐全，可合并双行 |
| `views/fund/prepay/DetailDrawer.vue:362-366` | 已分两处展示姓名/编号 | 数据齐全，统一布局和颜色即可 |
| `views/fund/prepay/ApplyDialog.vue:134` 与姓名区域 | 分开姓名/编号 | `kol.name/kolCode` 齐全，统一组件即可 |
| `views/fund/payout-account/index.vue:264-266` | 编号上、姓名下，顺序相反 | `PayoutAccountVO.kolName/kolCode` 齐全，替换为目标顺序 |
| `views/fund/payout-account/ReviewDrawer.vue:154-156` | 编号加姓名单行 | 详情字段齐全，可改双行 |
| `kol-portal/src/layout/index.vue:73` | 登录达人姓名加公司名 | 登录态 `KolProfile.name/kolCode` 齐全，可加双行身份；保留公司信息 |
| `kol-portal/src/views/account/index.vue:65` | 资料页编号、姓名分开字段 | `KolProfile` 齐全；身份区可双行，独立资料字段保留编辑/语义 |

通用说明：操作确认文案、筛选条件 chips、日志与通知正文中的编号是句子参数，不宜将公共 `formatNameWithCode` 直接改成 HTML 或换行，否则会破坏选择器和语言包插值。列表、详情、下拉选项采用共享呈现组件；标题和确认句保留纯文本必要信息。空姓名必须显示缺失占位，不能伪造姓名或用其他达人/订单主单姓名冒充子行姓名。

## 二、确定的字段缺口（本次不改后端）

1. **订单展开商品行缺达人姓名**：前端 `admin-ui/src/api/types.ts:493-511` 的 `OrderItemVO` 与后端 `backend/src/main/java/com/tcrm/admin/order/service/OrderItemDTO.java` 只有 `kolId/kolCode`，没有 `kolName`。`views/order/OrderLines.vue:51` 因而只有编号。若当前详情响应的归属明细能按同一 `kolId` 完整对应，可只在前端安全复用该明确对应的数据；否则须记录后端补充姓名字段。不能直接使用主单 `kolName`，一个订单可能包含多个归属达人。
2. **通知正文只有达人编号参数**：`admin-ui/src/api/notification/notification.ts:17-31` 的 `NotificationParams` 没有姓名，仅有 `kolCode`。通知工具在 `utils/notification.ts:48`，消息模板在中英文语言包。若“全系统”包含历史通知中的每处身份，需后端补参数/兼容旧消息。本次保留原始通知内容并登记差异。
3. Token 启用动作缺接口：主代理已确认当前只有作废接口；本报告不重复实施或自行新增。不得在前端制造启用成功。

## 三、其余需求的范围结论

| 源表行 | 审计结论 |
|---|---|
| D4 菜单 | 已有 PR #122；本任务检查现状即可 |
| D5 店铺状态与更多 | 前端可实现；原文写“四个”实际列出五项，应全部五项归入更多。用户当前明确要求优先于旧 ADR-029 的全量平铺要求，需任务文档记载此页面例外 |
| D6 删除两个页面 | 与截图对应的店铺内“原商品/商品源表”页面入口/标签删除；保留仍被现有流程引用的 API 与后端 |
| D7 Token | 布局、文案、颜色、列与操作可前端修改；启用真实业务动作依赖后端，应禁用并标“开发中”并提醒用户 |
| D8 商品任务 | 同宽筛选、编号 CopyText、状态与结果颜色均可前端完成 |
| D9 任务明细 | `admin-ui/src/api/product/copy-job.ts` 的 `CopyJobItemVO` 及后端 `product/controller/CopyJobItemVO.java` 已有 `sourceShopId/sourceShopName/targetShopId/targetShopName`。可以直接做名称主行、ID 次行、列顺序、复制及状态颜色；无需新增接口 |

检查范围：源表只读提取、两端页面源码与前端 DTO 搜索、必要的后端 DTO 只读核对。未运行全量检查，未启动浏览器，实施后的测试和浏览器验收由主代理另行组织。

## 四、用户最新覆盖与实施中复核

用户后续明确取消 Token 启用需求，并要求所有状态始终显示“作废”：仅 ACTIVE 可以点击，REVOKED、EXHAUSTED、EXPIRED 均为灰色禁用。本说明覆盖本报告第二、三节及源表 D7 的旧“启用/开发中”描述；本轮不应新增启用按钮或恢复接口。

对当前未提交 diff 的只读复核：主要列表、详情、选择器的达人身份已迁移至 NameCode；店铺五类更多沿用原有权限 hidden/disabled；原商品、商品源表两个标签已移除；Token 三主筛选、生成Token文案、状态配色、单一作废按钮与 ACTIVE 守卫符合最新需求；任务明细列序、店铺双行与成功/失败颜色符合。未发现将内部达人 ID 传入 NameCode 作为达人编号的新增用法。

初轮发现的编号字号差异已修复：两端 `NameCode.vue` 编号行均使用 body 字号/行高（14/22），通过浅灰区分视觉层级。此前误改的 `mapping.sourceChannel` 文案也已撤回。

最终冻结实现只读复核结论：未发现新增阻断缺陷。Token 每行始终显示一个作废按钮，无启用；模板 disabled 与事件处理守卫均限制为 ACTIVE，其他三态灰色禁用。Token 三主筛选位于 primary，相关选择器使用 `.token-filter { width: 100%; }`；店铺容器仅保留 ShopListPage，已清理原 tabs 及无用样式。达人端页头省略选择器通过 `:deep(.name-code__name)` / `:deep(.name-code__code)` 正确选中子组件，两行保留 title 全文。未发现后端、redirect、deploy 的未提交改动。此次仅源码复核，不替代主代理组织的测试与浏览器验收。
