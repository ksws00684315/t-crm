# 提现明细浏览器验收（2026-10-24）

仅工作树预览18941，Playwright+Edge。Existing fixture real admin login returned 401 AUTH_INVALID_CREDENTIALS. UI matrix used browser-only session and auth responses; no real authentication or backend authorization claim.

共享财务数据只读；preview state 非GET写入拦截计数0；页面异常0。

| 检查 | 结果 |
|---|---|
| copy-zh-CN | 通过 |
| paging-zh-CN | 通过 |
| copy-en-US | 通过 |
| paging-en-US | 通过 |
| explicit order snapshot is 7.25, not sum of item snapshots | 通过 |
| legacy missing order snapshot remains unverified | 通过 |
| viewport horizontal overflow | 通过 |
| dialog centering and viewport bounds | 通过 |

手机列表入口最初被工具栏遮挡，主代理修复后390中英均使用真实click打开；最终未使用dispatch。商品表格窄屏内部横滚，页面无横向溢出。

长商品名完整多行、SKU独立灰字；同单多商品占用20.00只展示一次，订单剩余快照7.25；未显示底部占用合计。中文截图中固定关闭位于弹窗底部，内容独立滚动。

| 截图 | 检查项 |
|---|---|
| [withdrawal-detail-1440-zh-CN](withdrawal-detail-1440-zh-CN.png) | 分辨率、语言、长商品名和多商品订单 |
| [withdrawal-detail-page2-zh-CN](withdrawal-detail-page2-zh-CN.png) | 第2页仅第11笔订单 |
| [withdrawal-detail-1280-zh-CN](withdrawal-detail-1280-zh-CN.png) | 分辨率、语言、长商品名和多商品订单 |
| [withdrawal-detail-2560-zh-CN](withdrawal-detail-2560-zh-CN.png) | 分辨率、语言、长商品名和多商品订单 |
| [withdrawal-detail-390-zh-CN](withdrawal-detail-390-zh-CN.png) | 分辨率、语言、长商品名和多商品订单 |
| [withdrawal-detail-1440-en-US](withdrawal-detail-1440-en-US.png) | 分辨率、语言、长商品名和多商品订单 |
| [withdrawal-detail-page2-en-US](withdrawal-detail-page2-en-US.png) | 第2页仅第11笔订单 |
| [withdrawal-detail-1280-en-US](withdrawal-detail-1280-en-US.png) | 分辨率、语言、长商品名和多商品订单 |
| [withdrawal-detail-2560-en-US](withdrawal-detail-2560-en-US.png) | 分辨率、语言、长商品名和多商品订单 |
| [withdrawal-detail-390-en-US](withdrawal-detail-390-en-US.png) | 分辨率、语言、长商品名和多商品订单 |
| [withdrawal-detail-empty-1440-zh](withdrawal-detail-empty-1440-zh.png) | 空订单 |
| [withdrawal-detail-legacy-1440-zh](withdrawal-detail-legacy-1440-zh.png) | 缺少订单快照显示待核实 |
| [withdrawal-detail-error-1440-zh](withdrawal-detail-error-1440-zh.png) | 接口失败 |
| [withdrawal-detail-role-business-1440-zh](withdrawal-detail-role-business-1440-zh.png) | 角色可见范围（浏览器身份fixture） |
| [withdrawal-detail-role-operator-1440-zh](withdrawal-detail-role-operator-1440-zh.png) | 角色可见范围（浏览器身份fixture） |
| [withdrawal-detail-role-platform-1440-zh](withdrawal-detail-role-platform-1440-zh.png) | 角色可见范围（浏览器身份fixture） |