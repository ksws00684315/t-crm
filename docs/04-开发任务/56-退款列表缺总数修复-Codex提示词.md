# 任务 56：退款同步在微店不返回 `totalNum` 时失败（缺陷）

> **执行环境**：本机。工作树 `/Users/hh-mini/Public/dev/t-crm/.worktrees/56-refund-list-total`，分支 `task/56-refund-list-total`，已从最新 `main` 检出。只改后端，**不启动应用，不连接共享中间件**（预览正在用真实微店店铺同步，避免两边同时拉取）。不新增迁移。
>
> **先完整阅读 [`本机任务通用要求.md`](本机任务通用要求.md)**，以及规范 05（测试）。

## 1. 缺陷

2026-10-23 第一个真实微店店铺（服务型）授权后，预览中这个店铺的**退款同步每 5 分钟失败一次**，`crm_order_sync_failure` 记录的错误是 `REFUND_SYNC`「Order synchronization failed」；订单同步正常。

任务 55 的只读实测（PR #111，`docs/04-开发任务/55-微店接口连通性-实测结果.md`）显示：店铺在时间窗口内没有退款时，`open.SellerQueryRefundList` 2.0 的响应根字段**只有 `list`，没有 `totalNum`**。

`order/service/OrderSync.java` 的退款分页循环在 `page.totalNum() == null` 时抛出 `IllegalStateException("Refund count missing")`，导致整轮退款同步失败。

## 2. 修复要求

1. **先写复现测试**（规范 05）：在 `OrderSync` 的单元测试中模拟退款列表返回空 `list` 且没有 `totalNum`，断言同步不抛错、正常结束、推进时间窗口，不记失败。修复前这个测试必须失败。
2. **修复分页结束条件**：
   - 有 `totalNum` 时，保持现有逻辑；
   - 没有 `totalNum` 时，按本页条数判断：本页少于页大小（50）就结束；本页满 50 条就继续取下一页；
   - 空页直接结束，不再抛 `Refund page missing`。这条只适用于没有 `totalNum` 的情况；有 `totalNum` 且声称还有数据却返回空页时，保留现有报错。
   - 加一个安全上限（例如单个时间窗口最多 200 页），防止接口异常时无限翻页；超过上限时抛错，并写明原因。
3. 再补两个测试：没有 `totalNum`、第一页满 50 条、第二页 3 条，两页都要处理；有 `totalNum` 的现有用例行为不变。
4. 订单列表的分页如果有同样的写法，一并检查并在报告中说明。只有确有同类问题才改，并同样补测试。
5. 设计说明书中描述退款同步分页的位置（如有）同步更新；ADR-016「后续核实」补一句：退款列表无数据时不返回 `totalNum`（任务 55 实测）。

## 3. 检查与交付

```bash
cd backend
mvn -B clean
mvn -B spotless:check
mvn -B verify -Pdocker-it
```

- 用 JDK 21；不加测试过滤，覆盖率门槛不得降低。检查交给子代理执行。
- 执行报告含逐条复核表；提交 PR。不要合并、不要推 main、不要改任务索引。GitHub Actions 已暂停，不要等待 `gh pr checks`。
