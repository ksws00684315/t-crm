# 任务 89：权限模板与数据范围统一

> **执行环境**：本机。
> - 工作树 `/Users/hh-mini/Public/dev/t-crm/.worktrees/89-permission-scope-cleanup`，分支 `task/89-permission-scope-cleanup`，从最新 `main` 检出。
> - 后端端口 `TCRM_ADMIN_PORT=18813`。需要联调时连接共享中间件（`deploy/.env.local` 只读），测试数据前缀 `t89_`。
> - 迁移版本区间 `V202611011000`～`V202611011009`。不新增依赖。可与任务 87、88 并行；**任务 90、91 要等本任务合并后再开始**。
>
> **必读**（通用阅读范围见 [`本机任务通用要求.md`](本机任务通用要求.md) 第 1 节）：
> - [`遗留问题清单.md`](遗留问题清单.md) 的 L-004、L-020、L-022、L-027、L-039 原文，其中负责人 2026-10-29 的决定是本任务的依据；
> - 设计说明书第 122、268～275 行附近的权限表，§6.2 预付接口行；
> - ADR-018；规范 03、规范 06 的权限小节。

## 1. 背景

负责人 2026-10-31 决定全盘处理遗留问题。这三个后端项的口径都已确定。

## 2. 要求

### 2.1 L-004：运营模板去掉 `kol:edit`

负责人 2026-10-29 决定：运营不需要调佣，也不需要 `kol:edit` 连带的新建、导入、审核达人、改资料、冻结解冻、重发邀请、清理、链接模板、推广表上传。**不拆分权限码。**

1. `security/api/PostTemplate.java` 的 `OPERATOR` 去掉 `Permission.KOL_EDIT`，删掉上面那段已过时的注释。
2. 表 `sys_post_template` 按公司保存模板（迁移 `V202610201002`）。查清已保存的模板行是否带 `kol:edit`：
   - 带的话，写迁移去掉 `OPERATOR` 模板行里的 `kol:edit`；
   - 但公司超管可能手动调整过模板，迁移前确认「默认模板」和「已被定制的模板」能否区分。**分不清时停下来报告，不要改已定制的模板。**
3. **不改已有账号的授权**（`sys_user_permission`）。模板只影响新建账号，已有运营账号由超管逐个取消。
4. 管理端如有写死的模板默认勾选（`views/system/post-template`、`views/system/user`），同步修改；只改这一处，不动其他管理端代码。
5. 设计说明书第 122 行、第 275 行的权限表同步。

### 2.2 L-027：数据范围不满足统一返回 403

负责人 2026-10-29 决定：同公司内 OWN 商务访问不负责的达人数据，统一返回「没有权限」`403 AUTH_FORBIDDEN`。

1. 把 `commission/service/WithdrawalService.java`、`PrepayService.java` 的 `requireKolScope` 从 `COMMON_NOT_FOUND` 改为 `AUTH_FORBIDDEN`。先全仓搜索，看还有没有其他同类写法（`OrderService.readable` / `locked` 已是 403，作为参照）。
2. **跨公司访问保持 404 不变**：数据不属于本公司时，行本身查不到，这条隔离规则不能动。`TenantIsolationIT`、`MultiCompanyAccountIT` 里跨公司的断言不应改变，如果需要改，停下来报告。
3. 列表接口的行为不变：OWN 范围内的过滤不是这条规则。
4. 同步测试：`GuardChainIT`（任务 79 按现状固定的断言）、`WithdrawalIT`、`CommissionWalletIT`、`PrepayKolQueryTest`、任务 74 相关测试。每处断言调整在报告里说明。
5. 核对前端是否依赖 404 判断（搜索 `COMMON_NOT_FOUND`、`status === 404`），有依赖时写进报告；只有改动会让页面出错时才改前端。
6. 设计说明书、ADR 里写到「范围外返回 404」的地方同步。

### 2.3 L-039：删除无调用方的预付接口

任务 82 之后 `GET /admin-api/commission/prepays/kol-summaries` 与预付收款账户接口已无前端调用。

1. 全仓确认没有调用方（管理端、达人端、测试、文档、`deploy/`），再删除 controller 方法、只为它们服务的 service、mapper、DTO 和测试。确认不了时保留，写进报告。
2. 设计说明书 §6.2 接口表同步删除。

### 2.4 L-020、L-022：文档修正

1. L-020：`docs/06-架构决策/ADR-032-佣金结算与提现模型.md` 第 262 行 `FundSummary.withdrawnCny` 由 `income.cumulativePaid` 改为 `income.commissionPaid`，修订记录加一条（2026-10-26 负责人决定「佣金提现不含预付」）。
2. L-022：`deploy/README.md` 系统配置表「业务默认值与上限」一行补上 `commission.minimum-withdrawal-cny`（200）。

## 3. 不做

- 不拆分权限码，不改 `BD` 模板。`DEPARTURE_INVALID_HEIR`、`KOL_INVALID_BD_USER`、`BdUserService` 依赖 `kol:edit` 判断商务，这些都不动。
- 不改已有账号授权。
- 不修改 `docs/04-开发任务/README.md`。

## 4. 测试

- 必须有测试覆盖：运营模板不含 `kol:edit`；OWN 越范围访问提现详情、冻结、恢复、打款、预付都返回 403；跨公司仍是 404。
- 不得弱化或跳过失败的测试；覆盖率门槛不得降低。

## 5. 验收与交付

- **检查**：后端 `mvn -q spotless:check`、`mvn -B verify`、`mvn -B verify -Pdocker-it`（JDK 21）；改了前端时跑对应工程六项。检查和需求复核交给子代理。
- **浏览器验收**：只有改了前端时才做，1440×900，中英文。
- **执行报告**：`docs/04-开发任务/89-权限模板与数据范围统一-执行报告.md`，逐条复核表覆盖 §2 每一项。写明迁移是否改动了共享库。
- **遗留清单**：不改状态，由架构负责人在合并时更新。
- **PR**：提交 PR，不合并、不推 main。推送后看一次 `gh pr checks` 的结果写进报告，不轮询等待。

## 6. 停下来报告

- 分不清默认模板和已定制模板；
- 改 403 后影响跨公司隔离断言，或某处其实是在防止泄露公司间数据；
- 需要修改已合并的迁移，或降低测试门槛。
