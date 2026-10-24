# 66 微店解析重复剥离 result 修复 —— 执行报告

分支 `task/66-weidian-parser-unwrap`，只改后端与任务文档；无迁移、无新依赖、无新错误码、无前端改动、无 Docker 容器操作（检查中的 docker-it 为 Testcontainers 自起容器）。

## 1. 缺陷

2026-10-24 M0 第二次复现：真实微店复制商品时，`vdian.item.add` 返回成功、微店已建商品，系统却报「微店已返回成功但未识别新商品 ID」。WARN 日志 `result fields=[]`。

## 2. 原因

`WeidianClient.call` 通过 `WeidianResponseParser.resultOrThrow(envelope)` 已经返回信封中的 `result` 节点。`WeidianBusinessApiParser` 中大多数方法直接读取该节点，是正确的；以下三个方法又执行了一次 `path("result")`，等于读取 `result.result`，永远为空：

- `itemDetail`：复制时源商品详情为空，新商品不带图文详情；
- `addItem`：读不到新商品 ID，即本次缺陷；
- `saveItemDetail`：返回值未被使用，暂无业务影响，但同样错误。

`WeidianItemCopyService.logItemIdMissing` 用 `created.raw().get("result")` 取字段名，同样多取一层，因此日志恒为 `fields=[]`，这也是缺陷现场日志的来源。

单元测试通过的原因：这三个方法的测试喂入的是带 `result` 包装的对象，与生产中解析器收到的形状不一致。

任务 65 的字段名假设（`itemid`）不是根因。解析器已能识别 `itemid`，问题在于解析器读错了层级。

### 2.1 真实形状未经实测确认

现有证据不能唯一确定生产中 `vdian.item.add` 的 `result` 形状，因此解析器兼容以下三种形状（测试覆盖见第 4 节）：

1. **单层业务对象**：`result` 即 `{"itemid": …, "add_sku_ids": […]}`（M0 日志 `result fields=[]` 与此一致，当前判断为最可能形状）。
2. **内层为对象**：`result` 为 `{"result": {…业务对象…}, "status": …}`，即官方示例夹具的形状。
3. **内层为标量**：`result` 为 `{"result": 12345}` 或 `{"result": "12345"}`，即内层直接是新商品 ID。日志只会显示 `fields=[result]`，无法区分 (2) 与 (3)；第二轮修复日志会补充内层类型。

真实形状以下一次 M0 实测日志为准。

## 3. 修复

| 文件 | 改动 |
|---|---|
| `WeidianBusinessApiParser.java` | `itemDetail`、`addItem`、`saveItemDetail` 的参数改为 `raw`（即调用方传入的节点），并先经 `innerResult(raw, 业务字段…)` 解析：若 `raw` 是对象、不含该方法的业务字段、且含 `result` 子节点，则只下钻一层取该子节点；否则不变。`raw` 字段保留传入节点。`addItem` 保留 `itemid` / `item_id` / `itemId` 多字段识别；解析出的节点若是整数或数字字符串，则直接作为商品 ID。数值解析为 `numericValue(JsonNode)`。其余 9 个方法未改。 |
| `WeidianItemCopyService.java` | `logItemIdMissing` 直接读取 `created.raw()`：对象时列字段名（格式不变）；若存在 `result` 子节点，追加其 JSON 类型，如 `result fields=[result], inner result type=NUMBER`；非对象时仅写 JSON 类型（如 `result type=STRING`）。均不写值。 |
| `WeidianBusinessApi.java` | 类 Javadoc 说明：各响应的 `raw` 为调用方传入的 `result` 节点，不是完整信封。 |
| `WeidianLiveConnectivityTest.java` | 手工实测类（tag `weidian-live`，默认构建不执行）中 `vdian.item.getItemDetail` 的字段探查同样多取一层，改为直接读 `root`。 |
| `ADR-016` 后续核实 | 任务 65 那条改正：字段名假设不成立，真正原因是解析器重复剥离 `result`（任务 66）；兼容三种形状；真实形状与真实 ID 字段名以首次 M0 实测为准。 |

## 4. 复现测试（修复前后）

按规范 05 先写复现测试，修复前运行确认失败，再修复。第一轮修复前运行结果：`WeidianBusinessApiParserTest` 9 失败、`WeidianItemCopyServiceTest` 2 失败、`WeidianBusinessApiEnvelopeTest` 3 失败，合计 14 失败。

| 测试 | 修复前 | 修复后 |
|---|---|---|
| `WeidianBusinessApiParserTest.shouldParseItemDetailOfficialExample_174_v1_0`（内层为对象，夹具原样传入） | 失败（`Expected size: 1 but was: 0`） | 通过 |
| `WeidianBusinessApiParserTest.shouldParseItemDetailBlocks_whenResultIsBusinessNode`（单层） | 失败（同上） | 通过 |
| `WeidianBusinessApiParserTest.shouldParseAddItemOfficialExample_231_v1_5`（内层为对象，夹具原样传入） | 失败（`Expecting actual not to be null`） | 通过 |
| `WeidianBusinessApiParserTest.shouldParseAddItemWithItemidField_whenWeidianReturnsItemidNumber`（单层） | 失败（同上） | 通过 |
| `WeidianBusinessApiParserTest.shouldParseAddItemWithItemidField_whenWeidianReturnsNumericString`（单层） | 失败（同上） | 通过 |
| `WeidianBusinessApiParserTest.shouldParseAddItemId_whenResultItselfIsNumber`（单层标量） | 失败（同上） | 通过 |
| `WeidianBusinessApiParserTest.shouldParseAddItemId_whenResultItselfIsNumericString`（单层标量） | 失败（同上） | 通过 |
| `WeidianBusinessApiParserTest.shouldParseSaveItemDetailOfficialExample_175_v1_0`（内层为对象，夹具原样传入） | 失败（`expected "id9232…"`，实际 null） | 通过 |
| `WeidianBusinessApiParserTest.shouldParseTemporaryDetailId_whenResultIsBusinessNode`（单层） | 失败（`expected "id-42"`） | 通过 |
| `WeidianBusinessApiParserTest.shouldParseAddItemId_whenInnerResultIsNumber`（内层为标量） | 第二轮新增，未在第一轮代码上单独运行 | 通过 |
| `WeidianBusinessApiParserTest.shouldParseAddItemId_whenInnerResultIsNumericString`（内层为标量） | 第二轮新增，未在第一轮代码上单独运行 | 通过 |
| `WeidianBusinessApiParserTest.shouldParseAddItemId_whenInnerResultIsBusinessObject`（内层为对象，无夹具） | 第二轮新增，未在第一轮代码上单独运行 | 通过 |
| `WeidianItemCopyServiceTest.shouldFailWithExplicitMessage_whenAddSucceedsButNoItemIdRecognised` | 失败（日志 `fields=[]`，应为 `fields=[add_sku_ids, new_goods]`） | 通过 |
| `WeidianItemCopyServiceTest.shouldLogJsonTypeOnly_whenAddResultIsNotAnObject` | 失败（日志未含 `result type=STRING`） | 通过 |
| `WeidianItemCopyServiceTest.shouldLogInnerResultTypeOnly_whenAddResultNestsScalar`（第二轮新增） | 未运行（修复前代码不输出内层类型） | 通过 |
| `WeidianItemCopyServiceTest.shouldCopyAndSaveDetail_whenAddReturnsItemidField` | 第一轮修复前未失败（输入仍为旧形状）；输入改为业务节点后未单独在修复前验证 | 通过 |
| `WeidianBusinessApiEnvelopeTest`：`itemDetail`、`addItem`、`saveItemDetail` 串联用例（第一轮） | 失败 | 通过 |
| `WeidianBusinessApiEnvelopeTest.shouldParseNewItemId_whenWeidianNestsScalarIdInResult`（信封 `result = {"result": 12345}`，第二轮） | 未运行 | 通过 |

## 5. 第 4 条：串联测试覆盖的方法清单

新增 `WeidianBusinessApiEnvelopeTest`。使用真实 `WeidianClient`，只桩掉 `WeidianTokenCenter`、`WeidianRateLimiter`、`WeidianHttpGateway`、`WeidianRetryDelay`；桩返回完整微店信封 `{"status":{"status_code":0,...},"result":{...}}`，由 `WeidianResponseParser.resultOrThrow` 剥离。不访问网络。

覆盖 `WeidianBusinessApiClient` 的全部 12 个公开方法，每个方法断言关键字段：

| 方法 | 断言 |
|---|---|
| `orderList` | `orderNum`、`totalNum`、`nextAfterKey`、订单号、金额 |
| `orderDetail` | 订单号、金额、明细商品 ID、明细金额 |
| `refundList` | `totalNum`、退款单号、退款金额 |
| `refundDetail` | 退款单号、订单号、退款金额（分）、创建时间 |
| `itemList` | `totalNum`、商品 ID、创建时间 |
| `item` | 商品 ID、价格、状态 |
| `itemDetail` | 详情块数量、`url`、`pos` |
| `categoryList` | 分类 ID、商品数（结果为数组） |
| `addItem` | 新商品 ID（`itemid`）、`add_sku_ids`；另有信封 `result = {"result": 12345}` 用例 |
| `saveItemDetail` | `tmp_detail_id` |
| `itemOnSale` | 结果为字符串且非信封（`""`） |
| `deleteItem` | 结果为 `200` |

`itemOnSale` 与 `deleteItem` 修复前即已通过，作为未受影响方法的回归保护。

## 6. 第 5 条：同类「多取一层」排查结论

| 位置 | 结论 |
|---|---|
| `WeidianBusinessApiParser` 其余方法（`orderList`、`orderDetail`、`refundList`、`refundDetail`、`itemList`、`item`、`categoryList`、`itemOperation`） | 均直接读取传入节点，正确，未改。 |
| `WeidianItemCopyService.buildAdd` 读取 `item.raw()` | `item()` 收到的是 `result` 节点，正确，未改。 |
| `order` 包消费者（`OrderNormalizer`、`OrderSync`、`OrderWriter`）读取 `raw` | 订单列表、订单详情、退款详情的 `raw` 均为 `result` 节点，读取层级正确，未改。 |
| `WeidianConnectivityChecker`、`WeidianTokenCenter` | 各调用一次 `resultOrThrow`，正确，未改。 |
| `WeidianLiveConnectivityTest`（手工实测类） | 修复 `vdian.item.getItemDetail` 字段探查的多取一层（第 3 节）。其余 `child(root, …)` 调用均针对业务节点，正确。 |

## 7. 检查结果

命令：

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
cd backend && mvn -B clean && mvn -B spotless:check && mvn -B verify -Pdocker-it
```

（检查结果见第 9 节。）

## 8. 与任务前提的偏差与观察

1. **官方示例夹具结构与生产不一致。** `add-item-231-v1.5.json`、`item-detail-174-v1.0.json`、`save-item-detail-175-v1.0.json` 的 `result` 节点内嵌套了一层 `result`（`{"result": {"result": 业务对象, "status": …}}`），与其余夹具（如 `order-list-10668-v1.6.json`）不同。第二轮修复后，测试原样传入夹具的 `result` 节点，由解析器只下钻一层（第 3 节）。夹具文件未改动。建议对照微店官方文档原文核实这三份夹具的嵌套层级。
2. **真实形状与真实 ID 字段名仍未确认。** 解析器现兼容单层对象、内层对象、内层标量三种形状（第 2.1 节）；`vdian.item.add` 的真实形状与 ID 字段名需在下一次 M0 实测中确认。若都不匹配，会得到「未识别新商品 ID」错误，日志列出字段名并在存在内层 `result` 时给出其类型。
3. **观察，未改（不在本任务范围，未核实）。** `OrderDetailResponse` 的 `OrderLine` 解析器读取 `quantity`，而记录组件的 `@JsonProperty` 标注为 `count`。该不一致与本缺陷无关，未修改。
4. **遗留 L-013 未处理。** 微店已建成商品但系统无法识别 ID 时，复制项仍记失败、不写对应关系与链接索引，需人工在微店后台核对。本任务只修复解析层级，未改变该失败路径。
5. **`WeidianBusinessApiClientTest` 未改。** 其 mock 返回 `{"result":{"result":{}}}`，只验证方法名与版本，不依赖解析结构，故保持不动。

## 9. 检查记录

命令：

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
cd backend && mvn -B clean && mvn -B spotless:check && mvn -B verify -Pdocker-it
```

| 步骤 | 结果 |
|---|---|
| `mvn -B clean` | 成功 |
| `mvn -B spotless:check` | 通过 |
| Checkstyle | 0 violations |
| `mvn -B verify -Pdocker-it`，Surefire 单元测试 | 1771 个，0 失败，0 错误，0 跳过（第一轮 1766，新增 5 个） |
| Failsafe 集成测试（`docker` 标签，Testcontainers） | 246 个，0 失败，0 错误，0 跳过；其中 `WeidianIntegrationIT` 11 个通过 |
| JaCoCo 门槛（7 项） | 全部通过，门槛未调整 |
| 最终结果 | BUILD SUCCESS，退出码 0 |

非阻断告警与第一轮相同：JaCoCo 无法对第三方类 `CCJSqlParserTokenManager` 插桩；Surefire 一个 fork JVM 在 `System.exit(0)` 后 30 秒被强制结束，测试计数均为 0 失败。

未改动 `admin-ui`，因此未运行前端检查。

## 10. 逐条复核

| # | 任务要求 | 结果 | 证据 |
|---|---|---|---|
| 1 | 先写复现测试，修复前失败 | 满足 | 第 4 节；第一轮修复前运行 14 个失败 |
| 2 | `itemDetail` / `addItem` / `saveItemDetail` 增加一次「内层 result」容错，只下钻一层，其余 9 个方法不改 | 满足 | `innerResult(...)`；其余方法未改 |
| 2a | `addItem` 保留 `itemid` / `item_id` / `itemId` 识别 | 满足 | `ADD_ITEM_ID_FIELDS` 与 `addItemId` 未改 |
| 2b | 子节点为标量时直接作为商品 ID；为对象时按 `itemid` / `item_id` / `itemId` 识别 | 满足 | `shouldParseAddItemId_whenInnerResultIsNumber` / `…NumericString` / `…BusinessObject` |
| 2c | `raw` 为调用方传入的节点（统一语义为 `result` 节点） | 满足 | 解析器三个方法与 `WeidianBusinessApi` 类 Javadoc |
| 2d | `logItemIdMissing`：对象时列字段名，存在内层 `result` 时追加其类型；非对象仅写类型；均不写值 | 满足 | `WeidianItemCopyService.logItemIdMissing`；`shouldLogInnerResultTypeOnly_whenAddResultNestsScalar`；`shouldLogJsonTypeOnly_whenAddResultIsNotAnObject` |
| 3 | 三种形状都有用例：单层对象、内层对象（夹具原样传入）、内层标量（`addItem`） | 满足 | 第 4 节；信封串联用例 `{"result":12345}` |
| 3a | 旧测试的完整信封改为 `result` 节点；断言未删除 | 满足 | 第 4 节 |
| 4 | 串联测试覆盖 `WeidianBusinessApiClient` 全部公开方法，真实 `WeidianClient` + 桩 HTTP 信封 | 满足 | `WeidianBusinessApiEnvelopeTest`，第 5 节 12 个方法 |
| 5 | 排查同包其他同类问题并修正、说明 | 满足 | 第 6 节 |
| 6 | ADR-016「后续核实」任务 65 条改正，说明两种形状都已兼容，真实形状以首次实测日志为准 | 满足 | `ADR-016-微店店铺授权与凭证加密.md` 第 30 行 |
| 7 | 执行报告（逐条复核表、检查结果） | 满足 | 本文件 |
| - | 不新增迁移、依赖；不碰 Docker 容器、预览脚本、共享数据库、真实微店 | 满足 | 改动仅后端源码、测试与任务文档；未运行 `preview.sh` |
| - | 不改 `docs/04-开发任务/README.md`，不合并、不推 main | 满足 | 未改 |
