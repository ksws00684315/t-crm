# Task 59 — 界面测量数据（英文界面 en-US，视口 1440×900）

- 测量时间：2026-10-24；环境：http://localhost:8859（预览超管 sa5f176c，登录后切换 English）。
- DOM 测量方法（L-001）：取 `.el-table__body tr` 每行最后一个 `td` 内 `.cell` 的 `scrollWidth` 最大值。`.cell` 左右 padding 各 12px（合计 24px）。
- 当某列内容不溢出时，`scrollWidth` = 容器宽度（含 24px padding），数值会等于列宽；此时实际文案更窄，另给出 canvas 文本测量做交叉校验。
- canvas 测量字体取自页面真实按钮计算样式：`600 14px -apple-system, "system-ui", "Segoe UI", "PingFang SC", "Microsoft YaHei", Arial, sans-serif`；行动作间距 `--tcrm-space-2` = 8px（RowActions `gap`），按钮自身 padding 为 0。
- 「建议 +24px 宽度」= 内容宽度（canvas 文本和 + 间隙）+ 24px 单元格 padding；DOM 溢出页直接给 `max scrollWidth + 0~1`（scrollWidth 已含 padding）。

## 1. 操作列 DOM 实测（任务 2，L-001 方法）

| 页面 | 行数 | 操作列 max scrollWidth | 源码声明列宽（en） | 结论 | 建议 +24px 宽度 |
|---|---|---|---|---|---|
| 店铺管理 /product/shops | 2 | **675** | px(470, **663**) | **溢出**（内容 675 > 663，末位 "Check APIs…" 被省略裁切，见 before-shops 截图；且固定右侧列悬浮遮挡中部列） | **675**（canvas 校验：最长行内容 650.2 + 24 = 674.2） |
| 商品任务 /product/tasks | 0（No data） | 无法 DOM 测量 → canvas | 120 | 无溢出 | canvas 97.7（"View items" 73.7 + 24） |
| 复制令牌 /product/copy-tokens | 1 | 100（=容器宽，未溢出） | 100 | 无溢出 | canvas 72.7（"Revoke" 48.7 + 24） |
| 达人列表 /kol/kols | 7 | **476**（=列宽 px(344,476)，未溢出） | 476 | 列内不溢出，但固定 Actions 列**遮挡**了 Invited at / Invited by / Review status / Login status / Relation status 各列（见 before-kols 截图） | canvas 最宽行内容 410.9 + 24 = 434.9 < 476，列宽够用；问题在固定列遮挡 |
| 订单查询 /order/orders | 10 | 120（=列宽 px(100,120)，未溢出） | 120 | 无溢出 | canvas 103.5（View 32.8 + Chain 38.7 + gap 8 + 24） |
| 退款查询 /order/refunds | 0（No data） | 无法 DOM 测量 → canvas | px(70, 80) | 无溢出 | canvas 56.8（"View" 32.8 + 24）；当前 80 已够 |
| 用户管理 /system/users | 8 | **550** | px(340, **444**) | **溢出**（BD 行 6 个动作实际需 ~550，超出 444 约 106px） | **550**（canvas：Edit+Permissions+Transfer creators+Disable+Mark departure+Reset Password ≈ 526.3 内容 + 24 = 550.3） |
| 人工匹配池 /order/orders「Matching Pool」页签（原 /order/unmatched 重定向） | 6 | **395**（原子窗口 DOM 测得；canvas 校验 391.8） | px(290, **330**) | **溢出**（内容 ~392 > 330，"Re-match" 被裁成 "Re-"，见过程截图） | **392**（Evidence 61.4 + Assign creator 98 + Mark non-creator 118.3 + Re-match 66.1 + gap 24 = 367.8 + 24 = 391.8） |
| 收款账户 /fund/payouts「Payout Account Review」（原 /fund/payout-accounts 重定向） | 0（No data） | 无法 DOM 测量 → canvas | 90 | 无溢出 | canvas 63.1（"Detail" 39.1 + 24） |
| 链接模板 /system/link-templates | 3 | 160（=列宽，未溢出） | 160 | 无溢出 | canvas 108.4（Edit 26.3 + Disable 50.1 + gap 8 + 24） |
| 导入任务 /system/tasks | 7 | **166** | 130 | **溢出**（"Download error file" 原生 el-button 实占 ~166 > 130，约超 36px；canvas 纯文本 128.5 + 24 = 152.5，按钮自带内边距再增宽） | **166** |
| 公司管理 /platform/tenants | —（**403**，当前账号无此菜单/权限，无法打开） | canvas | 290（源码） | 不可见；按源码行动作 Edit / Members / Disable(Enable) 估算 | canvas 179.5（Edit 26.3 + Members 63.1 + Disable 50.1 + gap 16 + 24），列宽 290 足够 |

## 2. 弹窗内表格

### 2.1 订单查询「Sync status」弹窗（SyncStatesDialog，宽 1160px）
- 「Sync status」页签：表头 Shop / Type / Source / Last success / Last error / Failures in 24h / Missing fields / Last import cutoff / Actions；4 行；操作列 max scrollWidth = **100**（=列宽 100，内容 "Sync now" canvas 64.9+24=88.9 未溢出）。
- 「Failed jobs」页签：表头 Shop / Type / Time / Last error / Import task —— **没有操作列**（末列 "Import task" 内是有 taskId 时的 "View" 链接，当前数据全部显示 "-"）。已截图 before-sync-dialog-1440x900-en.png（Failed jobs 页签）。
- canvas 参考：Sync now = 64.9 + 24 = 88.9；View = 32.8 + 24 = 56.8。

### 2.2 公司管理「Members」弹窗（TenantAdminsDialog，源码行操作列宽 110）
- 页面本身 403 打不开，无法 DOM 测量；源码确认弹窗内表格唯一行动作为 `tenantAdmin.resetPassword` = **"Reset Password"**，列宽声明 110。
- canvas："Reset Password" 文本 107.3 + 24 = **131.3 > 110**，若按现数据会溢出约 21px（建议 ≥132）。
- 备注：任务书中的行操作 "Admins" 实际英文文案是 **"Members"**（`tenant.admins`）。

## 3. 商品任务「Result」列（任务 3，R-01）

- /product/tasks 当前环境 **0 行数据**，无 el-tag 可量 → canvas 方案（文案来自源码 `copyJob.total/success/fail/completedHasFailures`，en：`Total {n}` / `Success {n}` / `Failed {n}` / 提示语）。
- el-tag 实测样式（取自页面真实 tag）：font-size 14px，padding 左右各 9px，border 1px → 单个 tag 文本宽 + 20px。Result 列内 3 个 tag 间距 `copy-job__result gap` = 6px；hint 文本字体 600 14px。
- 单个 tag 宽度（px）：

| 文案 | 宽度 |
|---|---|
| Total 5 | 63.7 |
| Success 5 | 86.5 |
| Failed 0 | 70.4 |
| Total 20 | 72.1 |
| Success 18 | 93.1 |
| Failed 2 | 70.0 |
| Total 200 | 80.8 |
| Success 186 | 101.9 |
| Failed 14 | 76.9 |

- 整行合计（3 tag + 2×6px gap；hint 单独 +6px gap）：

| 场景 | 3 tag 合计 | 加 hint 后合计 |
|---|---|---|
| 小（5/5/0） | 232.6 | — |
| 中（20/18/2） | 247.2 | 642.0（+hint 388.8） |
| 大（200/186/14） | 271.6 | 666.4（+hint 388.8） |

- 对照：Result 列声明 `minWidth: 280` → 中等任务 3 tag 即接近 280，一旦「Completed with failures」出现 hint（388.8px），内容达 ~642px，远超列宽，必然换行/挤压（R-01 成立）。
- hint 文案（en）：`This completed job still has failed items. Open the details.` = 388.8px。

## 4. 截图清单（均为 1440×900，英文界面）

| 文件 | 页面 | 状态 |
|---|---|---|
| before-shops-1440x900-en.png | /product/shops | 已存（可见 L-001：末动作 "Check APIs…" 裁切、固定列遮挡中部列） |
| before-copy-jobs-1440x900-en.png | /product/tasks | 已存（0 行；表头 "Fir…" 也被裁） |
| before-refunds-1440x900-en.png | /order/refunds | 已存（0 行；"Applied at" 表头被裁成 "A"，Actions 列被推出视口） |
| before-kols-1440x900-en.png | /kol/kols | 已存（Invited at / Invited by 等被固定 Actions 列遮挡） |
| before-orders-1440x900-en.png | /order/orders | 已存 |
| before-tenant-1440x900-en.png | /platform/tenants | **未截**：当前账号（预览超管）打开该页返回 403（路由跳 /403，"You do not have permission to open this page."），菜单中也无该页 |
| before-sync-dialog-1440x900-en.png | /order/orders → Sync status 弹窗 Failed jobs 页签 | 已存 |

## 5. 测量过程备注（供修复参考）

1. **共享预览环境存在并发干扰**：测量期间浏览器标签页会被其他会话反复切换页面/把语言重置回中文（应用内只有 LocaleSwitch 会写 `tcrm.admin.locale`，但实测多次被改回 zh-CN，且页面自行跳到 /product/copy-tokens 等已访问页）。所有英文测量都在单次调用内完成「设为 en-US → 打开页面 → 校验 document.title/locale → 测量」的原子窗口，并回读 locale 证明测量时为英文；Measurement Pool 的 395 来自一次原子窗口内的全表扫描（其值与 canvas 391.8 互证）。
2. `/order/unmatched` 与 `/fund/payout-accounts` 是旧路由重定向（routes.ts：task 32 合并页），分别落到 /order/orders「Matching Pool」页签与 /fund/payouts「Payout Account Review」页签。
3. /order/orders 主表操作列只有 View / Chain 两个链接（120 列宽足够）；截图右缘被裁的是「支付时间/创建时间」等列被推出视口，不是操作列内容溢出。
4. 行动作组件 RowActions 为 inline-flex、gap 8px、按钮 padding 0、无「More」菜单（ADR-029）；/system/tasks 与 SyncStatesDialog 的动作是页面自写原生 `el-button link`（自带内边距，比 RowActions 宽）。
