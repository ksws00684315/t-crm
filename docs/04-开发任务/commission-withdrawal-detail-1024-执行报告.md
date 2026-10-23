# 提现明细前后端调整执行报告

日期：2026-10-24（Asia/Shanghai）。分支：task/commission-finance-integration。沿用既有 CRM、工作树和 PR #112。

## 需求依据及范围

依据负责人确认的[最终示意图](assets/task-withdrawal-detail-1024/approved-mockup.png)及本任务[提示词](commission-withdrawal-detail-1024-Codex提示词.md)。负责人随后明确要求此部分前后端一起调整。

前端已接入独立详情和订单分页适配器。后端新增持久化只读详情、订单分页及增量表，不新增钱包、不执行转账、不改共享库，不把演示数据写入真实账务。

**来源限制：**当前工作树及 fetch 后的 origin/main 70331e8 均没有真实提现申请写入模块。因此本次后端交付是只读详情基础，尚无权威申请事务向新表生成快照；真实申请写入、审核及投影生产链路未实现。当前18941预览仍使用明确标识的演示链路，不能据此宣称真实提现闭环完成。缺失订单历史快照显示待核实，禁止用当前钱包或商品行余额求和补历史。

## 逐条复核

| 负责人要求 | 实现位置 | 结果 | 说明 |
|---|---|---|---|
| 按图改为信息表，展示身份、金额、状态、商务、时间、收款及三类备注 | WithdrawalDrawer.vue / WithdrawalDetailDTO | 满足 | 达人名称正常黑字，ID独立灰色小字；未知字段不填假值 |
| 长商品名及独立SKU | WithdrawalDrawer.vue products | 满足 | 商品名自然换行，SKU独立灰字，无强制单行截断 |
| 两个金额列稍左移 | WithdrawalDrawer.vue order-money | 满足 | 金额统一右对齐，并保留20px右侧呼吸空间 |
| 订单编号可复制 | CopyText | 满足 | 浏览器真实复制已验证，名称/ID无复制入口 |
| 多商品同订单、每页10笔 | getWithdrawalOrders / WithdrawalDetailMapper | 满足 | 以不同订单分页，订单内全部商品一起显示，占用汇总一次 |
| 不显示底部占用合计 | WithdrawalDrawer.vue footer | 满足 | 底部只保留关闭，顶部申请金额保留 |
| 确认后的快照说明 | finance中英文语言包 | 满足 | 中文逐字为“剩余快照为本次申请占用后，该笔订单当时剩余的可提现金额。” |
| 订单级不可变剩余快照 | orderRemainingSnapshot / remaining_snapshot | 满足 | 显式历史值，null为待核实；新演示申请不继承种子快照 |
| 失败重试和切换申请正确 | sequence控制 / finance-dialogs.spec.ts | 满足 | 分页失败可单独重试，旧申请迟到响应不覆盖新申请 |
| 前后端详情接口契约 | 两个真实GET端点 / finance.ts | 满足 | ID金额字符串，统一信封，按订单分页、稳定排序 |
| 公司/商务/收款权限 | WithdrawalDetailService / DTO | 满足 | 平台拒绝、租户隔离、当前商务范围；普通人员账户遮罩，财务备注按权限隐藏 |
| 真实申请自动写入详情快照 | 尚无真实申请写入基础 | 部分满足 | 只读表和读接口已提供，权威生产者待后续真实提现申请链路实现 |

只读复核子代理发现并复核修复了：种子快照被新申请继承、付款备注兼容、财务历史备注及兼容reviewNote遮罩旁路。最终未发现阻断本次详情展示交付的问题；上面的来源限制保留。

## 前后端契约及迁移

- GET /admin-api/commission/withdrawals/{id}
- GET /admin-api/commission/withdrawals/{id}/orders?page=1&size=10
- [后端契约/来源说明](../../backend/docs/withdrawal-detail-read-api.md)。
- 增量迁移 V202610241230__withdrawal_detail_read_model.sql；申请详情JSON不允许account键，收款快照使用现有SecretCipher独立加密。订单表同租户、同申请、同订单唯一。
- 普通STAFF的账户及持有人遮罩；无财务备注权限时顶层、付款、兼容reviewNote和财务阶段历史备注均不可见，明确运营备注保留。预付权限不扩大提现资料权限。
- 旧商品行remainingSnapshot保留原语义，另用显式orderRemainingSnapshot支持新订单级展示；缺失不能直接重命名、求和或填零。

## 验证

- 前端相关5文件43个不同用例：42项在整组通过；收入列表首项在资源并行期间触发原有5秒超时，随后保持原超时阈值隔离复跑通过。未跳过/弱化失败断言。新增订单分页、不同商品与订单快照、缺失快照、失败重试和切换申请迟到响应覆盖通过。
- admin-ui：类型检查及commission-preview构建通过；本次相关文件ESLint通过。
- kol-portal：类型检查与两个调整文件ESLint通过；新演示申请显式清空订单快照，不伪造订单历史值。
- 后端：最终 WithdrawalDetailServiceTest 5/5；WithdrawalDetailControllerTest 3/3；ArchitectureTest 9/9；PostgreSQL WithdrawalDetailIT 1/1。Spotless及Checkstyle通过。IT验证76个Flyway迁移、真实SQL的10+1订单分页、商品数组、租户隔离、tenant非空、同单唯一、未知快照null及拒绝JSON明文account。最新备注遮罩修复后专门复跑Service5项通过。
- 后端执行使用工作树缓存JDK21/Maven。最终命令：mvn -f backend/pom.xml spotless:apply test -Dtest=WithdrawalDetailServiceTest checkstyle:check -q。前序整组包含Controller/Architecture及Docker IT；旧日志曾有测试fixture漏paused字段，已补显式false并在最终Service运行消除，没有放宽全局反序列化规则。
- 浏览器：[报告](assets/task-withdrawal-detail-1024/browser-acceptance.md)与[数据](assets/task-withdrawal-detail-1024/browser-report.json)，16张截图；1440/1280/2560/390、中英文、复制、10笔分页、多商品、长名称、空/失败、缺历史、角色范围均已检查。0页面异常、0财务写请求。
- 浏览器认证限制：旧真实管理员fixture返回401。本次UI验收只使用浏览器隔离身份/只读响应，不声称实际登录验证成功；真实后端权限另由服务/MVC/PostgreSQL测试验证。
- 手机筛选挤压列表导致操作入口被遮挡的问题已局部修复；390中英文已用真实点击进入详情。

## 预览及截图

现有管理端：http://127.0.0.1:18941/fund/payouts 。服务来自本工作树admin-ui，继续保留演示提示。

- [1440中文](assets/task-withdrawal-detail-1024/withdrawal-detail-1440-zh-CN.png)
- [1280中文](assets/task-withdrawal-detail-1024/withdrawal-detail-1280-zh-CN.png)
- [英文](assets/task-withdrawal-detail-1024/withdrawal-detail-1440-en-US.png)
- [手机](assets/task-withdrawal-detail-1024/withdrawal-detail-390-zh-CN.png)
- [第二页](assets/task-withdrawal-detail-1024/withdrawal-detail-page2-zh-CN.png)

未合并PR、未推主线、未停止共享服务、未迁移演示财务记录到真实数据库。
