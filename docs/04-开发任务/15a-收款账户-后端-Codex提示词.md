# 任务 15a（后端）：达人收款账户与业务文件

> 本文件是交给编码代理执行的提示词，把全文作为任务输入即可。
>
> **并行说明**：
> - 与 11c、12c、13、16 同时进行。本任务在 `commission` 模块中新建代码，只读取 `kol` 模块的 `api` 包。
> - 界面（达人端填写、管理端审核）放在以后的资金页面任务中，本任务只做后端。
> - 迁移脚本版本号固定为 `V202610171700`。

---

## 1. 背景

客户需求补充 2（2026-10-15）与 10-14 会议确认了收款账户的规则：

- 达人用 **PayPal** 或 **USDT** 收款；
- **由达人本人填写**：客户担心商务填成自己的账户；
- **所属商务审核**：可以通过或驳回，通过后锁定；
- 超级管理员需要知道有账户提交。

收款账户是之后「提现申请」「预付」的前提。系统不做任何支付，只记录（负责人 2026-10-16）。

## 2. 开始前必读

1. `AGENTS.md`、`docs/05-工程规范/`（重点：03、04、06）。
2. 设计说明书：
   - §3.1 权限码：`payout-account:review`、`commission:view`；
   - §3.2；
   - §3.4「公司级配置与凭证」：`SecretCipher`；
   - §4.11 收款账户；
   - §5.2：`crm_payout_account`、`crm_file`；
   - §6.2、§6.3；
   - §10 安全与隐私。
3. 现有代码：
   - `shared.SecretCipher`；
   - `security.api` 中的达人令牌与当前达人上下文（12a），`kol-api` 接口的写法（`KolAuthController`）；
   - 审计：`@AuditLog`，以及 12a 新增的达人操作审计；
   - 任务文件的存储写法（`crm_task_file`）。

## 3. 执行环境与交付

- 在独立的 git 工作树中工作，分支 `task/15a-payout-account` 已从最新的 `main` 检出。
- **只修改 `backend/`、你的执行报告，以及第 4.7 节列出的文档。**
- 新增错误码时，按规范 03 同时在 `admin-ui` 与 `kol-portal` 的语言包和错误码映射中补文案，前端其他文件不改。
- JDK 21 下执行 `mvn spotless:apply`、`mvn verify`、`mvn verify -Pdocker-it`，全部通过。前端语言包改动后，在对应工程跑 `pnpm lint && pnpm test`。
- 推送分支，按 PR 模板创建 PR，并用 `gh pr checks --watch` 等 CI 通过。**不要合并 PR，不要推送 `main`。**
- **不新增依赖。**

## 4. 已定的设计决定（不要自行改变）

### 4.1 迁移 `V202610171700__create_payout_account_and_file.sql`

1. **`crm_file`**：
   - 字段：`id`、`tenant_id`、`biz_type`（`PAYOUT_QR` / `WITHDRAWAL_VOUCHER` / `PREPAY_ATTACHMENT` / `PREPAY_VOUCHER`，本任务只用 `PAYOUT_QR`，`CHECK` 列出全部四种）、`biz_id`（可空，账户创建前先上传时为空，保存账户时回填）、`file_name`、`content_type`、`size_bytes`、`content BYTEA`、`create_by`、`create_by_type`（`ADMIN` / `KOL`）、`create_time`；
   - 只追加；
   - 索引 `(biz_type, biz_id)`。
2. **`crm_payout_account`**：
   - 字段按设计 §5.2；另加 `last_submitted_at`；
   - `account_cipher` 存 `SecretCipher` 的 `v1:` 密文，`account_masked` 存脱敏值；
   - 部分唯一索引 `(kol_id) WHERE is_default`；
   - 索引 `(kol_id, status)`、`(tenant_id, status, last_submitted_at)`。
3. 两张表都带 `tenant_id NOT NULL`、正值雪花主键，表与字段都有中文注释。

### 4.2 账户规则

1. **类型与字段**：
   - `PAYPAL`：`account`（邮箱或 PayPal 账号，≤ 128）、`holderName`（必填，≤ 64）；
   - `USDT`：`network`（`TRC20` / `ERC20` / `BEP20` / `SOL` / `TON`）、`address`（≤ 128，只允许字母和数字）、`qrFileId`（可选）。
2. **数量**：一位达人非停用的账户最多 5 个，超出返回 422 `PAYOUT_ACCOUNT_LIMIT`。
3. **状态流转**：
   - 新建或修改后为 `PENDING`；
   - 审核通过为 `APPROVED`，驳回为 `REJECTED`（原因必填，≤ 255）；
   - 达人停用后为 `DISABLED`，不可恢复。
4. **修改**：
   - `PENDING`、`REJECTED` 的账户达人可以修改，修改后回到 `PENDING`，清空审核信息；
   - `APPROVED` 的账户不能修改，返回 409 `PAYOUT_ACCOUNT_LOCKED`。要换账户就新建一个。
5. **默认账户**：只能在 `APPROVED` 的账户中设置；设置时同一事务内取消该达人原来的默认账户；默认账户被停用时自动取消默认。
6. **停用**：本任务不检查「是否被处理中的提现引用」（提现在后续任务），在代码中留出检查点，并在报告中说明。
7. **加密与脱敏**：
   - 账号和地址用 `SecretCipher` 加密；
   - 脱敏规则：邮箱为前 2 位 + `***` + `@域名`；其他字符串为前 4 位 + `…` + 后 4 位，8 位以下只保留首尾各 1 位；
   - 列表与日志一律只出现脱敏值。
8. **二维码图片**：
   - 先通过上传接口上传，得到 `fileId`，保存账户时引用，并回填 `biz_id`；
   - 只接受 PNG、JPEG，按文件头校验，不信任扩展名和 `Content-Type`；大小 ≤ 2MB；
   - 达人只能引用自己上传、且尚未被其他账户使用的文件。

### 4.3 达人端接口（达人登录）

| 方法与路径 | 说明 |
|---|---|
| `GET /kol-api/payout-accounts` | 本人的账户（不含停用），完整账号对本人可见 |
| `POST /kol-api/payout-accounts` | 新建 |
| `PUT /kol-api/payout-accounts/{id}` | 修改 `PENDING` / `REJECTED` 的账户 |
| `PUT /kol-api/payout-accounts/{id}/default` | 设为默认 |
| `PUT /kol-api/payout-accounts/{id}/disable` | 停用 |
| `POST /kol-api/files` | multipart `file`，参数 `bizType=PAYOUT_QR`，返回 `{fileId}` |
| `GET /kol-api/files/{id}` | 本人上传的文件，附件下载 |

别人的账户或文件一律返回 404。达人的操作写入达人操作审计。

### 4.4 管理端接口

| 方法与路径 | 权限 | 说明 |
|---|---|---|
| `GET /admin-api/commission/payout-accounts` | `payout-account:review` 或 `commission:view` | 分页；可按状态、达人编号、类型筛选；默认按 `last_submitted_at` 倒序；只返回脱敏值 |
| `GET /admin-api/commission/payout-accounts/{id}` | `payout-account:review` | 详情，含完整账号与二维码文件 ID；**每次查看都写入操作日志** |
| `PUT /admin-api/commission/payout-accounts/{id}/review` | `payout-account:review` | 请求 `{action: APPROVE \| REJECT, reason?}`；只能审核 `PENDING` 的账户，否则返回 409 `PAYOUT_ACCOUNT_NOT_PENDING` |
| `GET /admin-api/files/{id}` | 能查看该文件所属业务记录的账号 | 附件下载 |

数据范围：`OWN` 的账号只能看到、审核**当前**归属本人的达人的账户；跨公司的 ID 返回 404。超管可以审核全部。

### 4.5 待办提示

系统还没有站内消息（设计里的 `crm_notification` 尚未实现），本任务**不做通知**。客户要求的「通知所属商务和超管」改由待审核列表承担：管理端列表默认显示 `PENDING`。另提供 `GET /admin-api/commission/payout-accounts/pending-count`，返回当前账号能审核的待审核数量，供以后菜单徽标使用。在报告中把「站内通知」列为遗留。

### 4.6 不在本任务范围

- 达人端、管理端界面（资金页面任务）。
- 提现、预付、钱包（后续任务）。
- 站内消息。

### 4.7 文档

- 设计说明书：
  - §4.11：按实际规则更新（网络枚举、脱敏规则、不做通知）；
  - §5.2：两张表的实际字段；
  - §6.2、§6.3：接口表。

## 5. 执行步骤

1. 迁移与模型。
2. 文件上传、下载与校验。
3. 账户规则与达人端接口。
4. 管理端查询、详情、审核与待办数量。
5. 测试、文档、执行报告。

## 6. 测试要求

| 场景 | 期望 |
|---|---|
| 字段校验 | 两种类型的必填项与格式；网络枚举；超过 5 个账户返回 422 |
| 状态流转 | 新建为 PENDING；通过、驳回（原因必填）；REJECTED 修改后回到 PENDING；APPROVED 修改返回 409；停用不可恢复 |
| 默认账户 | 只能设 APPROVED 的账户；切换时只有一个默认；默认账户被停用后不再是默认 |
| 加密 | 库里只有密文；列表只有脱敏值；脱敏规则的单元测试；日志中没有完整账号 |
| 文件 | PNG / JPEG 通过；伪装扩展名的其他类型被拒；超过 2MB 被拒；不能引用别人的或已被使用的文件 |
| 权限与范围 | 无 `payout-account:review` 不能审核；`OWN` 只能看本人当前达人的账户；商务转移后（直接修改达人的商务）新商务可见、原商务不可见；跨公司 404；达人看不到别人的账户与文件 |
| 审计 | 详情查看、审核、达人的新建、修改、停用都有记录 |

## 7. 文档与报告

执行报告写在 `docs/04-开发任务/15a-收款账户-后端-执行报告.md`：迁移、规则、接口与示例、脱敏规则、测试清单、偏离与遗留（含站内通知、停用时的提现检查点）。

## 8. 禁止事项与停止条件

**禁止**：

- 修改已合并的迁移脚本；
- 新增依赖；
- 明文保存账号或地址，在日志或列表中输出完整账号；
- 修改 `admin-ui/`、`kol-portal/` 中语言包与错误码映射以外的文件；
- 删除或弱化测试；
- 推送 `main` 或合并 PR；
- 使用 `push --force`、`reset --hard`。

**遇到以下情况停下来**（推送已完成的提交，以 `[WIP]` 标题创建 PR，在报告中说明）：

- 设计说明书与本提示词冲突；
- `SecretCipher` 无法用于业务数据（例如只允许在 `integration` 模块中使用）；
- 验证在合理排查后仍失败。
