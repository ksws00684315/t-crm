# 任务 59 修复前浏览器实测数据（简体中文 zh-CN，视口 1440×900）

- 环境：管理端 http://localhost:8859（预览），账号 预览超管（超级管理员），当前公司「默认公司」。
- 测量方法（L-001）：取可见表格 body 每行最后一个单元格（操作列）的 `.cell` `scrollWidth` 最大值；行内嵌套表格与展开行已排除。
- 「建议列宽」= max scrollWidth + 24px（Element Plus cell 左右 padding 12+12）。
- canvas 兜底字体：`14px -apple-system, "PingFang SC", "Helvetica Neue", "Microsoft YaHei", sans-serif`。

## 1. 操作列实测（任务 2）

| 页面 | 路由 | 行数 | 操作列内容（样本） | max scrollWidth | 建议 +24px 宽度 |
|---|---|---|---|---|---|
| 店铺管理 | /product/shops | 2 | 去授权 拉订单 拉退款 编辑 停用 取消源店铺/设为源店铺 加入复制池/退出复制池 检查接口…（右侧横向溢出，出现省略号） | **485** | **509**（源码未配置该列固定宽度，为弹性列） |
| 商品任务 | /product/tasks | 0 | 无行，无法实测 | 0 | canvas 兜底：查看明细 = 56 → 建议 ≥ 80（源码列宽 120） |
| 复制令牌 | /product/copy-tokens | 1 | 作废 | **100** | **124**（源码列宽 100） |
| 达人列表 | /kol/kols | 7 | 资料编辑 佣金信息 预付金额 剩余30天/剩余3天/清理配置 冻结/解冻 | **344** | **368** |
| 订单查询 | /order/orders | 10 | 查看 链路（无达人行「链路」置灰） | **100** | **124** |
| 退款查询 | /order/refunds | 0 | 无行，无法实测 | 0 | canvas 兜底：查看 = 28 → 建议 ≥ 52（源码列宽 px(70,80)） |
| 用户管理 | /system/users | 8 | 编辑 权限设置 停用 重置密码 | **395** | **419**（源码列宽 px(340,444)） |
| 人工匹配池 | /order/unmatched（重定向 /order/orders「人工匹配池」页签） | 6 | 查看依据 指定达人 标为非达人订单 重新匹配 | **333** | **357**（源码列宽 px(290,330)） |
| 收款账户 | /fund/payout-accounts（重定向 /fund/payouts） | 0 | 无行（操作列有表头） | 0 | canvas 兜底：审核 = 28 → 建议 ≥ 52（源码列宽 90，仅可审核角色显示） |
| 链接模板 | /system/link-templates | 3 | 编辑 停用 | **160** | **184**（源码列宽 160） |
| 导入任务 | /system/tasks | 7 | 单元格文本为空（图标/条件渲染按钮） | **130** | **154** |
| 公司管理 | /platform/tenants | — | **403 无权访问**（超管不可见，页面仅平台管理员可见；跳过实测） | — | canvas 兜底：编辑 28 + 成员 28 + 停用(启用) 28 = 84 → 建议 ≥ 108（源码列宽 290） |

### 弹窗内表格

| 弹窗 | 页签 | 行数 | 最后一列 | max scrollWidth | 说明 |
|---|---|---|---|---|---|
| 订单查询「同步状态」弹窗 | 同步状态 | 4 | 操作（立即同步） | **100** | 建议 124 |
| 订单查询「同步状态」弹窗 | 失败任务 | 52（弹窗内滚动） | **无操作列**；最后一列为「关联导入任务」链接列，实测样本均为「-」 | 180（关联导入任务列 cell） | 记录实际情况：失败任务页签没有行操作按钮列，只有关联导入任务链接列；截图 before-sync-dialog-1440x900-zh.png |
| 公司管理「管理员」弹窗（TenantAdminsDialog） | — | 无法实测（页面 403） | 源码：行操作仅「重置密码」，列配置宽 110 | — | canvas：重置密码 = 56 → 建议 ≥ 80 |

## 2. 商品任务结果列测量（任务 3 / R-01）

列表共 0 条，无数据行，**无法逐行测量**。

- `.copy-job__result .el-tag`：常规状态下 0 个（无行）。
- 运行任务模板时曾短暂捕获到 3 个标签（疑似上一会话 keep-alive 缓存实例被清空前的残影，不可作为可靠依据）：总 = 34px、成功 = 48px、失败 = 48px（强制 `flex:none; white-space:nowrap` 前），说明标签可被压缩。
- `.copy-job__hint`：不存在（hintW = 0，hintText 为空）。
- 源码参考：结果列 minWidth 280（`admin-ui/src/views/product/copy-job/index.vue`）。

## 3. ListSearch DOM 检查（任务 4 / R-22 根因）

在 3 个列表页各执行一次，结果一致：

| 页面 | inlineStyleAttr | computedMarginLeft |
|---|---|---|
| 退款查询 /order/refunds | `null`（无内联 style） | 0px |
| 达人列表 /kol/kols | `null` | 0px |
| 订单查询 /order/orders | `null` | 0px |

结论：`.list-search__form .el-form-item__content` 上**没有** Element Plus 内联输出的 `margin-left`（`el-form-item__content` 的内联样式不存在），computed margin-left 为 0px。R-22 的根因不在该项内联样式。

## 4. 数据清点（任务 5，决定后续验证数据）

| 数据 | 共 N 条 | 代表性内容 |
|---|---|---|
| 店铺（/product/shops） | 2 | 测试源店铺（SHOP-100000，源店铺）、复制店铺 A（SHOP-100001，已加入复制池） |
| 达人（/kol/kols） | 7 | KOL-RPT007 Zoe … KOL-WJT001 Luna；预付金额列均为「开发中」占位 |
| 达人商品（/kol/kol-items） | 6 | 有数据 |
| 订单（/order/orders） | 10 | 4 行有达人且「链路」可点（5001002004/3/2/1）；6 行无达人「链路」置灰（857742282574459、5001001005…5001001001） |
| 退款（/order/refunds） | 0 | 无 |
| 复制任务（/product/tasks） | 0 | 无（因此无「已完成且失败项>0」的行可展示） |
| 导入任务（/system/tasks） | 7 | 有数据（操作单元格为空文本，疑似图标/条件渲染） |
| 收款账户（/fund/payout-accounts → /fund/payouts） | 0 | 无 |
| 链接模板（/system/link-templates） | 3 | 有数据 |
| 用户（/system/users） | 8 | 有数据 |
| 公司管理（/platform/tenants） | — | 403：预览超管无权访问（仅平台管理员角色可见） |
| 同步状态弹窗 | 同步状态 4 行 / 失败任务 52 行 | 失败任务均为「测试源店铺」订单/退款同步失败（Order synchronization failed: inspect API…），关联导入任务均为 - |

## 5. 截图清单（assets/task-59/，均 zh-CN、1440×900）

1. before-copy-jobs-1440x900-zh.png — 商品任务（0 条）
2. before-shops-1440x900-zh.png — 店铺管理（2 条）
3. before-refunds-1440x900-zh.png — 退款查询（0 条）
4. before-kols-1440x900-zh.png — 达人列表（7 条）
5. before-orders-1440x900-zh.png — 订单查询（10 条）
6. before-chain-1440x900-zh.png — 订单链路（订单 5001002004；注：当前实现点击行操作「链路」是 window.open 新开浏览器标签页打开 `/order/orders/{id}/chain`；新开标签页截图通道故障，改在主标签页直接导航同一 URL 截图，页面内容一致）
7. before-tenant-1440x900-zh.png — 公司管理（403 无权访问）
8. before-sync-dialog-1440x900-zh.png — 同步状态弹窗「失败任务」页签

（同目录下另有此前会话生成的 *-en.png 英文版截图与 measurements-en.md，保留未动。）

## 6. 测量过程中的环境注意事项

- 应用把页签与会话状态持久化在 sessionStorage，整页加载后会恢复上次激活页签；界面语言存于 localStorage（tcrm.admin.locale），且存在延迟恢复竞态，实测中语言会被异步翻回 en-US。本报告所有测量均在确认菜单首项为「工作台」（zh-CN）后立即执行。
- 页面 aria-label 为英文常量而可见文案为中文，交互与文案识别以可见文本为准。
- 全程只读：仅执行查询、打开弹窗/页签、切换语言与导航，未点击任何同步/停用/删除/清理/保存类按钮。
