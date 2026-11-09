# 任务 141：佣金扣回时撤回预付冲抵（L-118）

> **执行环境**：本机。
> - 工作树由架构负责人在派发时创建：`/Users/hh-mini/Public/dev/t-crm/.worktrees/141-commission-recovery-reversal`，分支 `task/141-commission-recovery-reversal`。
> - 端口：后端 `TCRM_ADMIN_PORT=18141`（只在需要本机联调时启动）。
> - 连接共享中间件：`deploy/.env.local` 只读，用法同 [`本机任务通用要求.md`](本机任务通用要求.md) 第 2 节。测试数据前缀 `t141_`。
> - **只改后端 `backend/`、设计说明书和本任务执行报告。不改前端，不新增依赖。**
> - **迁移版本号只用 `V202611091400`～`V202611091449`。**
>
> **必读**（通用阅读范围见 [`本机任务通用要求.md`](本机任务通用要求.md) 第 1 节）：
> - [`遗留问题清单.md`](遗留问题清单.md) L-118 原文；
> - [`金额名词与口径定义.md`](../03-设计/金额名词与口径定义.md) 中预付、已抵扣、待抵扣的定义；
> - 设计说明书 §4.8（佣金与钱包）、§8.3，ADR-032；
> - `commission/service/CommissionWalletService.java`（`postCommissionAdjustment`、`postCredit`、`allocateFifo`）；
> - `commission/service/CommissionItemSnapshotService.java` 第 170～200 行（两处调用 `postCommissionAdjustment`：归属改出的全额扣回、同一达人释放后的减少）；
> - `commission/mapper/CommissionWalletMapper.java`（`listOutstanding`、`insertRecovery`）、`PrepayMapper.sumRecovered`、`CommissionFinanceQueryMapper.recoverySums` / `pendingDeductions`：现有读取冲抵记录的三处，都按 `SUM(amount_cny)` 汇总；
> - 迁移 `V202610221900__create_prepay_and_wallet_ledger.sql` 中 `crm_commission_recovery` 的定义（`ck_crm_commission_recovery_amount CHECK (amount_cny > 0)`，唯一索引 `(tenant_id, credit_ledger_id, deduction_ledger_id)`）。

## 1. 背景

预付抵扣会在钱包里记一笔负向台账 `PREPAY_DEDUCT`。之后每笔佣金正向入账 `COMMISSION_CREDIT`，都按 FIFO 分配给还没冲抵完的预付抵扣，分配结果写进 `crm_commission_recovery`。管理端预付页、达人收入和达人端工作台的「已抵扣」「待抵扣」都由这张表汇总得到。

问题：已释放的佣金被扣回（`COMMISSION_ADJUSTMENT`，例如归属改出、释放后退款）时，只减了钱包余额，没有撤回这笔佣金当初做过的冲抵。结果是：

- 扣回后余额已经变少，「已抵扣」仍按扣回前计算，偏大；「待抵扣」偏小；
- 同一行之后如果再次入账（例如归属改出又改回），新的入账会再去冲抵其他待抵扣的预付，「已抵扣」进一步多计。

钱包余额本身是对的，错的是冲抵记录。

## 2. 要求

### 2.1 扣回时按 LIFO 撤回冲抵

在 `postCommissionAdjustment` 写完扣回台账之后、同一个事务和钱包锁内：

1. 找出**同一达人、同一来源行**（`source_type = 'COMMISSION'`、`source_id` 等于本次扣回的 `sourceId`）的入账台账，以及这些入账在 `crm_commission_recovery` 里的**净冲抵额**（已有撤回要扣掉），按预付抵扣分组。
2. 本次撤回额 = min(本次扣回金额的绝对值, 该行净冲抵合计)。净冲抵为 0 时什么都不做。
3. 按冲抵发生的逆序（LIFO：后冲抵的先撤回，同一时间按 ID 倒序）逐条撤回，直到撤回额用完。
4. 撤回用**追加负数冲抵记录**表示，不删除、不修改已有记录：
   - 新建迁移，把 `ck_crm_commission_recovery_amount` 改为 `amount_cny <> 0`，并更新表注释和 `amount_cny`、`credit_ledger_id` 的字段注释：正数是入账冲抵，负数是扣回撤回，负数行的 `credit_ledger_id` 指向那笔 `COMMISSION_ADJUSTMENT` 台账；
   - 现有唯一索引 `(tenant_id, credit_ledger_id, deduction_ledger_id)` 保持不变：同一笔扣回对同一预付只写一条撤回。
5. 幂等：同一 `sourceKey` 的扣回重放时（`existingEntry` 命中直接返回），不能再写一次撤回。
6. 撤回后，这些预付抵扣重新变成「待抵扣」，之后的入账照常按 FIFO 冲抵，不需要额外处理。

### 2.2 读取方核对

现有三处读取都是 `SUM(amount_cny)`，负数行会自动抵消。逐一确认：

- `listOutstanding` 的 `HAVING -l.amount_cny > COALESCE(SUM(r.amount_cny), 0)` 在有负数行时结果正确；
- `PrepayMapper.sumRecovered`、`CommissionFinanceQueryMapper.recoverySums`、`pendingDeductions` 结果正确；
- 全仓库再搜一遍 `crm_commission_recovery`，如果有逐行展示冲抵记录、或假定 `amount_cny > 0` 的地方，列进报告；需要改的，在本任务范围内改。

### 2.3 现有数据

- 不做数据修复迁移，不改共享库和正式环境的已有数据。
- 写一条**只读**查询 SQL 放进执行报告：找出「有扣回、且扣回所在行的入账做过冲抵」的达人和金额，用来让负责人判断正式环境是否已经受影响。不在正式环境执行。

### 2.4 设计文档

设计说明书 §4.8 中冲抵与扣回的描述、`crm_commission_recovery` 表结构说明同步更新；文末修订记录加一条。

## 3. 不做

- 不改 `CommissionSettlementRules`、计佣金额、预估佣金的口径。L-120 负责人 2026-11-08 决定不修。
- 不改订单详情与链路的取数（L-114② 已关闭）。
- 不改前端、接口出入参。
- 不修改 `docs/04-开发任务/README.md`、`遗留问题清单.md`，这些由架构负责人改。

## 4. 测试

**先写复现测试，确认在改动前失败，再改实现**（规范 05）。

1. 单元测试或集成测试（`*IT`，`@Tag("docker")`）覆盖：
   - **复现场景**：预付抵扣 100 → 某行入账 100（全部冲抵）→ 该行扣回 100 → 断言已抵扣 0、待抵扣 100、余额 −100；该行再入账 100 → 已抵扣 100、待抵扣 0、余额 0；
   - **两笔预付**：预付 A 60、预付 B 40，入账 100 冲抵 A、B → 扣回 30 → 撤回 B 的 30（LIFO），A 不变；
   - **部分冲抵**：入账 100 中只有 20 冲抵了预付 → 扣回 100 → 只撤回 20；
   - **没有冲抵**：没有预付时扣回，不写撤回；
   - **重放**：同一 `sourceKey` 的扣回调用两次，撤回只写一次；
   - **归属改出**：A 达人的行改归属给 B，A 的冲抵按扣回撤回，B 不受影响；
   - **跨公司**：撤回只影响本公司数据。
2. 迁移在 Testcontainers 上执行通过，`CHECK` 改动后正数、负数都能写，0 被拒绝。

## 5. 检查与复核（交给子代理）

按 [`本机任务通用要求.md`](本机任务通用要求.md) 第 4 节：

1. **检查子代理**：只改了后端，在 `backend/` 执行 `mvn -q spotless:check`、`mvn -B verify`、`mvn -B verify -Pdocker-it`（JDK 21）。不跑前端检查。
2. **复核子代理**（只读）：按本提示词第 2、4 节逐条核对，输出「要求 → 实现位置 → 是否满足」。
3. 不涉及界面，不做浏览器验收。

## 6. 交付

- **执行报告**：`docs/04-开发任务/141-佣金扣回撤回预付冲抵-执行报告.md`，包括逐条复核表、复现测试改动前失败的输出片段、迁移内容、§2.2 的读取方核对结论、§2.3 的只读查询 SQL。
- **PR**：标题「fix(commission): 佣金扣回时撤回预付冲抵（任务 141）」。不合并，不推 main。推送后看一次 `gh pr checks`，结果写进完成后的回复，不轮询。

## 7. 停下来报告

- 发现「已抵扣」「待抵扣」的口径与本提示词的理解不一致（例如金额口径定稿另有规定）；
- 有读取方依赖 `amount_cny > 0` 且改动会影响接口返回；
- 需要修改已合并的迁移，或需要修复已有数据才能让测试通过；
- 复现测试在改动前就是通过的（说明问题理解有误）。
