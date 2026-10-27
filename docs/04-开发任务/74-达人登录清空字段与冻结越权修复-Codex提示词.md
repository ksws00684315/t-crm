# 任务 74：达人登录清空档案与推广配置字段（缺陷 D1），冻结提现越权（S1）

> **执行环境**：本机。
> - 工作树 `/Users/hh-mini/Public/dev/t-crm/.worktrees/74-kol-login-field-wipe`，分支 `task/74-kol-login-field-wipe`，从最新 `main` 检出（已包含任务 72 / #139）。
> - 后端端口 `TCRM_ADMIN_PORT=18741`，管理端 Vite `8877`。
> - 只改后端。**不新增迁移**；如果确实需要，先停下来报告。
>
> **开工前先完整阅读**：
> - [`本机任务通用要求.md`](本机任务通用要求.md)；
> - 规范 04（数据库）、05（测试）、06（安全）；
> - [交付就绪审查报告](交付就绪审查-20261027-报告.md)第 3、4 节；
> - 审查附件 B 的 A1 条（`交付就绪审查-20261027-附件/B-规约符合性扫描.md`）。

## 1. 背景

2026-10-27 交付就绪审查发现两个问题，都已在共享中间件上实际复现：

- **D1（阻断交付）**：达人每登录一次，档案和推广配置中的 9 个字段就被写成 `NULL`。
- **S1（安全，应修）**：被授予 `commission:freeze` 的 OWN 范围商务，可以冻结和恢复别人名下达人的提现资格。

两项互相独立，**每项至少一个提交**。只做本文列出的内容；检查中发现的其他问题写进执行报告，不要顺手改。

## 2. D1：达人登录清空字段

### 2.1 缺陷

**根因**：`backend/src/main/java/com/tcrm/admin/kol/service/KolAccountStoreService.java` 的 `updateLastLoginTime` 新建了一个空 `KolDO`，只设置 `id` 和 `lastLoginTime`，然后调用 `kols.updateById(change)`。`KolDO` 中以下 9 个字段标注了 `@TableField(updateStrategy = FieldStrategy.ALWAYS)`，MyBatis-Plus 会把实体里为 `null` 的这些字段一起写成 `NULL`：

`defaultRate`、`remark`、`whatsapp`、`linkTemplateId`、`inviteCode`、`sheetUrl`、`phone`、`promotionRemark`、`autoCleanupDays`

`KolAuthService.logIn` 会调用这个方法。以下 5 条路径都会走到 `logIn`：

| 路径 | 入口 |
|---|---|
| 单公司登录 | `login` |
| 多公司选择公司 | `select` |
| 修改密码 | `changePassword` |
| 激活账号 | `activate` |
| 自助注册（注册即登录） | `KolSelfRegisterService.register` → `loginEstablished` |

所以注册时填的 WhatsApp、手机号当场就会丢失。

**审查时的复现**：管理员保存资料、审核并保存推广配置后，whatsapp、phone、remark、佣金比例 0.1200、推广表地址、跳转模板都有值，关系状态为「推广中」。达人调用一次 `/kol-api/auth/login` 后，这些字段全部变成 `NULL`，关系状态仍是「推广中」。

**影响**：

- 推广配置完整性检查（`KolPromotionSupport.missingConfigFields`）会判定缺少佣金比例。
- 自动清理被静默取消：`KolCleanupService` 要求 `autoCleanupDays` 非空。
- 管理端的资料、审核、佣金信息对话框显示空值，运营重新保存时可能用错误的值覆盖。
- 计佣使用推广配置的历史版本（`KolLookupService.rateEffectiveAt`），已产生的佣金金额不受影响。

### 2.2 修复要求

1. **先写复现测试**（规范 05），用集成测试（`*IT`、`@Tag("docker")`），因为这个缺陷取决于 MyBatis-Plus 对真实数据库的更新语句，mock 测不出来。放在 `KolAuthIT` 或 `KolRegisterIT` 中，或者新建一个 IT。
   - **登录**：准备一个达人，把上面 9 个字段都设为非空的合法值（`linkTemplateId` 引用真实存在的模板）。通过 `/kol-api/auth/login` 登录后，断言 9 个字段全部不变，且 `last_login_time` 已更新。
   - **注册**：带 `phone` 和 `whatsapp` 调用 `/kol-api/register`，断言两个字段都已入库。
   - **修复前这两个测试必须失败**，执行报告中贴出失败输出。
   - 其余 3 条路径（选择公司、修改密码、激活）与登录共用 `logIn`，至少再补一条集成测试覆盖其中一条，在报告中说明另外两条为什么也被覆盖。
2. **修复**：`updateLastLoginTime` 改为按列更新，只写 `last_login_time`。写法参照管理员侧 `system/service/AccountStoreService.java` 中更新登录时间的 `LambdaUpdateWrapper`。
   - 不要改 `KolDO` 字段的 `ALWAYS` 策略。资料编辑等功能依赖它把字段清空。
   - 登录不算资料变更，不写 `update_time`。如果你认为应该写，在报告中说明理由，不要自行决定。
3. **全仓复核同类写法**：检查所有带 `FieldStrategy.ALWAYS` 字段的 DO 中，有没有「新建部分实体 → `updateById`」的写法。
   - 审查用脚本扫描过，只发现这一处；`SheetSourceService.update` 是先加载整行再更新，不算同类问题。
   - 请独立复核，在报告中写明检查方法、清单和结论。
   - 如果确实发现同类问题，按同样方法先写复现测试再修，并在报告中单独列出。
4. **不修复历史数据**：生产环境还没有数据；共享库中已被清空的演示数据无法恢复，不处理，在报告中说明。
5. 现有 `KolAuthServiceTest` 中对 `updateLastLoginTime` 的调用校验保留。

## 3. S1：冻结和恢复提现没有校验商务数据范围

### 3.1 缺陷

`backend/src/main/java/com/tcrm/admin/commission/service/WithdrawalService.java` 的 `freeze` 和 `resume` 只调用了 `requireKol(kolId)`，没有调用同一文件中已有的 `requireKolScope(user, kol)`。同文件的审核、付款、详情等入口都调用了它。

`commission:freeze` 是可以授予的权限码。审查时给 OWN 范围商务 `trev_bd_a` 授予了这个权限：它查看别人名下达人的详情返回 403，但调用 `POST /admin-api/commission/withdrawal-freezes` 和 `PUT /admin-api/commission/withdrawal-freezes/{kolId}/resume` 都返回 200，冻结和恢复都成功了。

### 3.2 修复要求

1. **先写复现测试**，加在 `WithdrawalServiceScopeTest`（或同类单元测试）中：
   - OWN 范围、持有 `commission:freeze` 的商务，冻结和恢复别人名下的达人 → 都抛出 `COMMON_NOT_FOUND`，且不写冻结记录，也不写审计。
   - 同一商务冻结和恢复自己名下的达人 → 成功。
   - ALL 范围的账号行为不变。
   - 修复前，「别人名下」的两个用例必须失败。
2. **修复**：`freeze` 和 `resume` 在 `requireKol` 之后调用 `requireKolScope`，与同文件其他入口一致。
3. **核对 commission 模块中其他按 `kolId` 或按申请 ID 访问的入口**（审查附件 B 认为其余入口已落实），在报告中列出清单和结论。只有确实存在同类问题才改，并同样补测试。

## 4. 验收

- **接口验收**：用自己的后端 18741，连接共享中间件，测试数据加 `t74_` 前缀。
  - D1：管理员给 `t74_` 达人保存资料、审核、保存推广配置并设置自动清理天数 → 达人登录 → 再次查询，字段全部不变。
  - S1：给 `t74_` 商务（OWN）授予 `commission:freeze`，冻结别人名下的 `t74_` 达人返回 404，冻结自己名下的达人成功。
  - 贴出请求和结果摘要。**日志和报告中不得出现密码、令牌。**
- **浏览器验收**（规范 12），管理端 Vite 8877，中文，1440 宽：在上面 D1 的流程之后，打开该达人的「资料编辑」「佣金信息」和审核对话框（如仍可审核），截图证明数据都还在。截图放在 `docs/04-开发任务/assets/task-74/`。
- 收尾时删除自己创建的 `t74_` 数据，把改过的权限恢复原样。不得修改共享库中已有的其他数据。

## 5. 检查与交付

- 后端按 CI 的步骤执行，不加测试过滤，覆盖率门槛不得降低：
  - `mvn -B clean`
  - `mvn -B spotless:check`
  - **`mvn -B verify`**：只跑单元测试，覆盖率门槛必须单独通过
  - `mvn -B verify -Pdocker-it`
- 检查、复核和接口与浏览器验收都交给子代理执行。每次修改代码后重跑完整检查。
- 执行报告 `docs/04-开发任务/74-达人登录清空字段与冻结越权修复-执行报告.md` 要包含：
  - 两个缺陷的复现测试在修复前的失败输出；
  - 修复说明；
  - 2.2 第 3 条和 3.2 第 3 条的核对清单；
  - 各项检查的命令和结果；
  - 逐条复核表：对照本文 2.2、3.2、第 4 节每一条，状态列只用规范 12 规定的「满足 / 部分满足 / 不满足 / 开发中」。
- 提交 PR，PR 描述中贴出检查结果和复核表摘要。
- 不要合并 PR，不要推 `main`，不要修改任务索引、遗留问题清单和审查报告（由审查人在合并时更新）。
- GitHub Actions 已暂停，不要等待 `gh pr checks`。

## 6. 停下来报告

- 需要新增迁移，或需要修改 `KolDO` 字段的更新策略；
- 全仓复核发现同类问题且影响面超出达人模块；
- 合理排查后验证仍然失败。
