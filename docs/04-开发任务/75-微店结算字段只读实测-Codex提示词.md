# 任务 75：微店结算字段只读实测，记录计佣口径与字段来源

> **执行环境**：本机。
> - 工作树 `/Users/hh-mini/Public/dev/t-crm/.worktrees/75-weidian-settlement-probe`，分支 `task/75-weidian-settlement-probe`，从最新 `main` 检出。
> - 不启动后端和前端，不占端口。只读访问共享中间件（55432）中的店铺授权和订单表。
> - **不新增迁移，不改 `backend/src/main` 下的生产代码**。只新增一个手动运行的实测测试类，外加文档。
>
> **开工前先完整阅读**：
> - [`本机任务通用要求.md`](本机任务通用要求.md)，以及规范 05（测试）、06（安全）；
> - [ADR-032](../06-架构决策/ADR-032-佣金结算与提现模型.md) 第 2 条和修订记录；
> - [M0 实测清单](M0-微店实测清单.md)；
> - [任务 55 实测结果](55-微店接口连通性-实测结果.md)，以及 `backend/src/test/java/com/tcrm/admin/integration/service/WeidianLiveConnectivityTest.java`（本任务照它的写法做）；
> - [交付就绪审查报告](交付就绪审查-20261027-报告.md) D2，附件 A 第 5 节第 1、2 条。

## 1. 背景

计佣基数一直没有接入：`OrderCommissionSync` 把 `basisCny` 固定传 `null`，所有商品行都是「未核实」，佣金为 0。此外，订单的结算时间（`settled_time`）、收货时间（`receive_time`）从来没有从微店写入，`completedTime` 也固定为 `null`。所以即使接上基数，佣金也不会释放，达人永远无法提现。

**负责人 2026-10-28 确认的计佣口径**：佣金 = 微店回传的**可提现金额** × 达人佣金比例（即 R15、进度表 11.01）。数据统计口径第 16 行和 DR-14 中「以微店回传为准」，理解为计佣基数取自微店，不是由微店直接给出佣金金额。

审查人查阅了微店开放平台文档，找到两个来源（文档原文以开放平台为准）：

| 接口 | 文档位置 | 相关内容 |
|---|---|---|
| `shop.order.trade.detail` 1.0「订单结算明细」 | 支付钱包分类，<https://open.weidian.com/#/api/10652> | 入参：`pageNo`、`pageSize`（≤100）、`startTime`、`endTime`（毫秒时间戳，跨度不超过 3 个月）、`queryType`（`INCOME_DETAIL` 收支明细，含部分退款；`PRE_SETTLE_REFUND` 结算前全额退款订单）。出参 `result.list[]`：`amount`、`orderId`、`refundId`、`tradeType`（收入 / 支出）、`tradeSubType`、`tradeTime`、`remark`；`result.total`。示例中同一订单有「货款收入 600」「交易手续费 −4」「分润 −300」「退款 −600」等多笔。**文档写的是企业门店场景**：要传总店 `access_token`，并通过 `ctx={"offline_shop_id":...}` 指定门店 |
| `vdian.order.get` 2.0「获取订单详情」 | <https://open.weidian.com/#/api/1064> | 订单级 `last_income`（本单实际收入）、`total`（含运费）、`price`（不含运费）、`express_fee`。商品行 `items[]`：`total_price`、`fee_list[]`（平台佣金、服务费：`fee`、`feeType`、`feeTypeDesc`）、`refund_info`（`refund_fee`、`refund_item_fee`、`refund_status`、`refund_kind`）、`status`（40 确认收货、50 订单完成）。**没有结算时间和完成时间字段** |

另外还有 `shop.income.detail` 1.0「收支明细」（<https://open.weidian.com/#/api/10638>），出参类似，多一个 `balance`。本任务不调用它，在报告中写一句它和订单结算明细的区别即可。

文档本身回答不了以下问题，本任务要用现有店铺授权实际调用来确认：

1. 普通店铺、我们的服务型应用，能否调用 `shop.order.trade.detail`；要不要传 `ctx`；是否需要单独申请接口权限。
2. `amount` 的单位是分还是元。
3. `tradeType`、`tradeSubType` 实际有哪些取值；`orderId` 能否和订单详情的 `order_id`（我们库里的 `crm_order.weidian_order_id`）对上；`tradeTime` 是哪个时间点。
4. `last_income` 在不同订单状态下的实际取值，和结算明细的合计是否一致。

**本任务不接入计佣，只实测和记录。** 接入作为后续开发任务，等本任务的结论出来再派。

## 2. 实测

### 2.1 实测类

新建 `backend/src/test/java/com/tcrm/admin/integration/service/WeidianSettlementLiveProbeTest.java`，照 `WeidianLiveConnectivityTest` 的写法：

- **运行范围**：标注 `@Tag("weidian-live")`，默认构建和 `-Pdocker-it` 都不运行。
- **环境变量**：用同一组环境变量（`TCRM_SECRET_KEY`、`TCRM_DB_*`、`WEIDIAN_LIVE_SHOP_ID`），缺任何一个就跳过。值从主目录的 `deploy/.env.local` 读取（**只读**）。
- **数据库只读**：只执行 `SELECT`，只读 `crm_weidian_shop_auth`（解密访问令牌）和 `crm_order`（取样本订单号）。**不刷新令牌**；令牌已过期就不调用任何接口，在报告中写明，然后结束。
- **只调用下面两个方法**，在代码中用白名单断言，名字里不能含 add / delete / save / update / create / modify / deliver / refund / cancel：
  - `shop.order.trade.detail`
  - `vdian.order.get`
- **请求限制**：单次运行最多 30 次请求，相邻两次间隔至少 1 秒。
- **生产代码保持不变**：如果 `WeidianHttpGateway.apiPost` 不支持 `ctx` 参数，就在测试类里自己拼请求，不要改它。
- **自动生成报告**：运行后写出 `docs/04-开发任务/75-微店结算字段-实测结果.md`。报告中：
  - 不得出现访问令牌、密钥、数据库密码、买家信息（订单详情中的加密字段一律不输出，也不解密）；
  - 订单号、退款单号、商品 ID 只显示后 4 位；
  - 金额、时间、状态、子类型原样输出。

### 2.2 要做的调用

1. **结算明细能否调用**：用 `queryType=INCOME_DETAIL`、最近 3 个月的时间窗口、`pageSize=100` 调用。
   - 先**不传** `ctx`。
   - 如果返回错误，最多再试一次：传 `ctx={"offline_shop_id":"<店铺 ID>"}`。
   - 两次的结果（成功 / 错误码 / 错误信息原文）都写进报告。
   - 如果都失败，记录错误后**跳过第 2、3 步，直接做第 4 步**，在报告中写明需要负责人在微店开放平台申请哪项权限（如果错误信息里有提示）。
2. **收集样本**：调用成功时，按 3 个月一个窗口往前翻，最多翻 4 个窗口（约 12 个月），找到有数据的窗口为止；每个窗口最多取 2 页。然后统计：
   - `tradeType` × `tradeSubType` 的组合和各自条数；
   - `orderId` 的格式（纯数字 / 带 `_refund` `_1` 等后缀）；能否和 `crm_order.weidian_order_id` 对上（报告只写匹配了几条）；
   - 每个订单号所有笔的 `amount` 合计。
   - 再用 `queryType=PRE_SETTLE_REFUND` 在同一个窗口调用一次，记录是否有数据和字段形态。
3. **判断金额单位**：选 1～3 个能匹配到本库订单的订单号，把它的「货款收入」`amount` 与订单详情的 `total` 或 `price` 对比，判断单位是分还是元，写明依据。如果一笔都匹配不上，用 `amount` 的数量级和示例对比做推断，并**标注「推断，未证实」**。
4. **订单详情**：从 `crm_order` 中选出该店铺所有已付款的真实订单（10-23、10-24 的测试订单都在其中），最多 5 笔，逐笔调用 `vdian.order.get` 2.0，记录：
   - `status`、`status_desc`、`last_income`、`total`、`price`、`express_fee`；
   - 每个商品行的 `status`、`total_price`、`fee_list`（全部条目）、`refund_info.refund_fee` / `refund_status`；
   - 如果第 2 步匹配到了同一订单：`last_income` 与结算明细合计是否相等。
5. **字段清单**：列出 `vdian.order.get` 实际返回、但我们解析器（`WeidianBusinessApiParser`）没有读取的、与金额或时间有关的字段，只要字段名和样本值。

### 2.3 样本不足时

店铺里可能没有已结算的订单（现有测试订单可能还没发货、没有确认收货）。这种情况下：

- 能确认的写清楚，不能确认的逐条标为「待有结算样本后复测」；
- **不要下单，不要发货，不要确认收货，不要在微店做任何写操作。**

## 3. 文档

1. **实测结果**：`docs/04-开发任务/75-微店结算字段-实测结果.md`，由实测类生成。在文件末尾**手写一节「结论」**，逐条回答第 1 节的 4 个问题，每条注明「已证实 / 推断 / 待复测」。
2. **M0 清单** `M0-微店实测清单.md`：
   - 「实测路线」表加一行 **第 11 步「结算明细与可提现金额字段（11.01、Q-1017-2）」**，填写依赖、执行人、通过标准、记录位置。
   - 「实测记录」表加本次运行的记录。
3. **ADR-032** 在修订记录末尾追加一条「2026-10-28（任务 75）」，包含：
   - **计佣口径**：负责人确认按 R15 计算（佣金 = 微店回传的可提现金额 × 达人比例），并写明对口径第 16 行、DR-14「以微店回传为准」的理解（见第 1 节）。
   - **字段来源表**，每行写明实测状态：
     - 可提现金额 ← 同一订单在 `shop.order.trade.detail` 中所有流水的合计；
     - 结算时间 ← 「货款收入」那一笔的 `tradeTime`；
     - 结算前预估 ← `vdian.order.get` 的 `last_income`（是否可用以实测为准）；
     - 交易完成时间：订单详情中没有这个字段。
   - **留给后续开发任务决定、本任务不定的事项**，逐条列出：
     - 订单级金额如何拆分到商品行；
     - 可提现金额中包含运费，是否照样计佣（需负责人确认）；
     - 拿不到完成时间时，`COMPLETED_PLUS_DAYS` 兜底如何取时间；
     - 结算明细的拉取频率和窗口。
   - 第 2 条正文不要改，只追加修订记录。
4. 设计说明书中与计佣基数来源有关的段落（§4.8、§8.3 附近），**只加一句指向 ADR-032 本次修订的说明**，不改其他内容。

## 4. 检查与交付

- 后端按 CI 的步骤执行，不加测试过滤：`mvn -B clean`、`mvn -B spotless:check`、`mvn -B verify`（只跑单元测试）、`mvn -B verify -Pdocker-it`。新增的实测类不得进入这两轮测试。
- 实测本身用下面的命令手动运行，把命令写进实测结果文档的开头：
  ```
  mvn -B test -Dgroups=weidian-live -Dsurefire.excludedGroups= -Dtest=WeidianSettlementLiveProbeTest
  ```
  如果项目里的 surefire 配置不接受这种写法，换成等效写法，并在报告中说明。
- 检查和逐条复核交给子代理执行。复核子代理要额外检查两点：生成的报告中没有令牌和买家信息；代码中的方法白名单只有上面两个只读方法。
- 执行报告 `docs/04-开发任务/75-微店结算字段只读实测-执行报告.md` 要包含：实测命令、请求次数、各问题的结论（附实测结果文档的位置）、检查结果，以及逐条复核表（对照本文第 2、3 节每一条，状态只用「满足 / 部分满足 / 不满足 / 开发中」）。
- 提交 PR，PR 描述中贴出 4 个问题的结论摘要和检查结果。
- 不要合并 PR，不要推 `main`，不要改任务索引、遗留问题清单和审查报告。GitHub Actions 已暂停，不要等待 `gh pr checks`。

## 5. 停下来报告

- 共享库中没有该店铺的授权记录，或者令牌已过期（不要刷新令牌，报告后结束）；
- 微店返回的错误提示需要在开放平台申请权限，或需要负责人在微店后台操作；
- 需要修改生产代码或新增迁移才能完成实测；
- 实测结果和文档描述明显矛盾，影响第 1 节中的口径理解。
