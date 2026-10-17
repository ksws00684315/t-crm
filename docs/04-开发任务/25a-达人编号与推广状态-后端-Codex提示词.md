# 任务 25a（后端）：达人编号、推广状态与推广配置

> 本文件是交给编码代理执行的提示词，把全文作为任务输入即可。
>
> **执行环境**：
> - 本机执行时，独立工作树 `.worktrees/25a-kol-status-backend`，分支 `task/25a-kol-status-backend` 已从最新的 `main` 检出；
> - 非本机执行时，自行 `gh repo clone ksws00684315/t-crm`，从最新的 `origin/main` 检出分支 `task/25a-kol-status-backend`。
>
> **并行说明**：
> - 与 25b（管理端与达人端页面）同时进行，按第 5 节的接口约定对接；
> - 与 26（推广表上传）同时进行。26 会改 `crm_kol_item`，本任务不改这张表的结构；
> - 17a（复制令牌）可能已合并或仍在进行，迁移版本号互不冲突。
>
> 迁移版本号使用 `V202610181200`～`V202610181209`（可拆成多个脚本，按顺序编号）。

---

## 1. 背景

客户 2026-10-17 确认了达人编号、状态与推广配置的规则，第一版 10-31 交付。必读：

- [1017 确认事项与变更清单](../00-需求/客户补充资料-1017/1017确认事项与变更清单.md) R1～R9、R18；
- [ADR-023](../06-架构决策/ADR-023-达人编号格式与推广状态由商务维护.md)（本任务的设计依据）；
- 设计说明书 §4.2、§5.2、§8.2。

## 2. 开始前必读

1. `AGENTS.md`、`docs/05-工程规范/`。
2. ADR-012（跳转服务）、ADR-018（权限码与数据范围）、ADR-023。
3. 现有代码：
   - `kol` 模块：`KolRules`、`KolCodeGenerator`、`KolService`、自助注册 `KolSelfRegisterService`、`KolRegisterRequest`；
   - `crm_kol` 已有的 `invite_code`、`sheet_url`、`link_template_id`、`default_rate`、`whatsapp`；
   - `crm_link_template`（代购平台模板）；
   - `redirect/` 中对达人编号的校验；
   - `commission` 模块的收款账户（R18 PayPal 币种）；
   - `shared/ApiCatalogConsistencyTest`（新接口要登记到设计 §6.2 / §6.3 的标记区间）。

## 3. 执行环境与交付

- JDK 21：`backend/`、`redirect/` 都执行 `mvn spotless:apply`、`mvn verify`、`mvn verify -Pdocker-it`，全部通过。本机没有 Docker 时在报告中写明，以 CI 为准，CI 必须通过。
- 只修改 `backend/`、`redirect/`、设计说明书中本任务涉及的章节、两个前端的 `locales`（新增错误码文案）和你的执行报告。
- 推送分支，按 PR 模板创建 PR，`gh pr checks --watch` 等 CI 通过。**不要合并 PR，不要推送 `main`。不新增依赖。**

## 4. 已定的设计决定（不要自行改变）

### 4.1 达人编号（ADR-023 第 1 条）

- 格式 `KOL-` + 3 位随机大写字母（`SecureRandom`，A～Z）+ 序号；序号取现有全局序列 `crm_kol_code_seq`，至少 3 位，不足补零。例：`KOL-ASD001`、`KOL-QWE1234`。
- 校验正则 `^KOL-[A-Z]{3}[0-9]{3,8}$`：后台 `KolRules`、数据库 `CHECK`（迁移中替换原约束）、跳转服务、导入（后端保留的 Excel 导入）统一。
- 编号仍全局唯一、不可修改、不复用；插入冲突时换号重试（沿用现有逻辑）。
- 系统未上线：迁移中**不改写**已有数据；新约束对已有行用 `NOT VALID` 加上，并在报告中说明测试与演示数据需要重建。

### 4.2 推广状态（ADR-023 第 2 条）

- `crm_kol.promotion_status VARCHAR(20) NOT NULL DEFAULT 'PENDING_REVIEW'`，CHECK：`PENDING_REVIEW`、`PENDING_PROMOTION`、`PROMOTING`、`ENDED`。已有行回填为 `PROMOTING`（系统未上线，保持现有演示数据可推广）。
- 显示状态 `displayStatus`：达人没有绑定商务（`bd_user_id` 为空），或绑定的商务已停用、不是可用商务时，为 `PENDING_UPDATE`（待更新）；否则等于 `promotion_status`。只在接口中计算，不入库。
- 状态由商务手动修改，任意两个状态之间都可以改；只校验：改为 `PROMOTING` 时，推广配置的必填项（佣金比例、至少一个代购平台、落地页地址、邀请码）齐全，否则 422 `KOL_PROMOTION_CONFIG_INCOMPLETE`。
- 自助注册与后台录入的新达人为 `PENDING_REVIEW`。

### 4.3 推广配置与记录（ADR-023 第 3 条）

- `crm_kol` 新增 `landing_url`（https，≤ 512）。
- 新表 `crm_kol_platform`：达人与代购平台模板的多对多（`tenant_id`、`kol_id`、`link_template_id`，唯一 `(kol_id, link_template_id)`）。原 `link_template_id` 保留为「默认跳转模板」不变（任务 11 的跳转逻辑依赖它）；多选平台另存。
- 新表 `crm_kol_promotion_record`（只追加）：`tenant_id`、`kol_id`、`change_type`（`CONFIG` / `STATUS`）、`before_json`、`after_json`（只含推广配置字段与状态，不含个人信息）、`remark`、`create_by`、`create_time`。
- 保存推广配置与修改状态都写一条记录；表和字段有中文注释。

### 4.4 认领未绑定达人（ADR-023 第 4 条）

- 只有可用商务（`BdUserApi` 的判定：启用、`STAFF`、`OWN`、有 `kol:edit`）可以认领。
- 精确搜索：按达人编号（完全相等，忽略大小写）或邮箱（完全相等，忽略大小写）；只返回**未绑定可用商务**的达人，返回编号、名称、邮箱（脱敏：首字母 + `***@域名`）、显示状态。找不到返回空，不区分「不存在」与「已被别人绑定」。
- 认领：绑定为本人，写商务归属历史（沿用任务 16 的 `changeOwnership`），写操作日志；并发认领只有一个成功（行锁），另一个 409 `KOL_ALREADY_CLAIMED`。

### 4.5 清理配置（ADR-023 第 5 条）

- 按公司保存：`enabled`（默认 false）、`idle_days`（7～365）。可以建一张专用小表 `crm_kol_cleanup_config`（`tenant_id` 唯一）；**不要**建通用的 `crm_config`。
- 每天北京时间 03:00 执行（定时任务，按公司进入作用域，分布式锁防重）：对推广状态为 `PROMOTING` 的达人，最近一笔已归属订单的支付时间（没有订单时用该达人最近一次改为 `PROMOTING` 的时间）早于 `now - idle_days` 时清理。
- 清理 = 逻辑删除达人 + 结束其有效商品对应关系（`valid_to = now`）+ 写操作日志（系统操作）；不调用微店接口；订单、佣金历史不动。
- 预览接口：返回按当前配置「下次会被清理」的达人列表（不执行），供运营确认。

### 4.6 注册与收款账户

- 自助注册：`whatsapp` 改为必填（格式沿用现有校验）。
- 收款账户：PayPal 增加 `currency`（ISO 4217 三位大写，必填；USDT 不填），新迁移加列，已有 PayPal 行回填 `USD`；达人端与管理端接口返回该字段。

## 5. 接口约定（与 25b 共用，不得改动）

```text
达人列表 / 详情（现有接口）每行增加：
  promotionStatus, displayStatus（含 PENDING_UPDATE）, landingUrl, platformTemplateIds: string[]

PUT  /admin-api/kol/kols/{id}/promotion-status          {status}                → 204；422 KOL_PROMOTION_CONFIG_INCOMPLETE
GET  /admin-api/kol/kols/{id}/promotion-config                                  → {defaultRate, platformTemplateIds[], landingUrl, inviteCode, sheetUrl, remark}
PUT  /admin-api/kol/kols/{id}/promotion-config          同上（remark 为本次修改备注，可空）
GET  /admin-api/kol/kols/{id}/promotion-records?page&size → 分页 {changeType, before, after, remark, operatorName, createTime}
GET  /admin-api/kol/claimable?keyword=                  → {id, kolCode, name, maskedEmail, displayStatus} 或 null
POST /admin-api/kol/kols/{id}/claim                      → 204；409 KOL_ALREADY_CLAIMED
GET  /admin-api/kol/cleanup-config                       → {enabled, idleDays}
PUT  /admin-api/kol/cleanup-config                       {enabled, idleDays}
GET  /admin-api/kol/cleanup-preview?page&size            → 分页 {id, kolCode, name, bdUserName, lastOrderAt, idleSince}
收款账户（达人端、管理端）：PayPal 增加 currency
```

权限：

- 状态、推广配置、记录：`kol:edit`，数据范围为「本人」时只能操作名下达人；
- 认领：可用商务；
- 清理配置与预览：`kol:edit` 且数据范围为「全部」（「本人」范围返回 403）；
- 均写操作日志（查询除外）。

## 6. 测试要求

| 场景 | 期望 |
|---|---|
| 编号 | 格式、补零与扩位、唯一冲突重试；跳转服务接受新格式、拒绝旧格式以外的非法值 |
| 状态 | 任意切换；改为推广中时配置不齐 422；显示状态的「待更新」（无商务、商务停用）；每次修改有记录 |
| 推广配置 | 保存、读取、多选平台必须是本公司模板、落地页 https 校验；记录前后值 |
| 认领 | 精确匹配；不返回已绑定达人；并发认领只有一个成功；非商务不能认领 |
| 清理 | 预览与执行一致；只处理推广中；无订单时按改为推广中的时间；逻辑删除与结束对应关系；关闭时不执行 |
| 注册 | WhatsApp 必填 |
| 收款账户 | PayPal 币种必填与格式；USDT 不需要 |
| 权限与隔离 | 本人范围、跨公司、平台管理员 |
| 接口清单 | `ApiCatalogConsistencyTest` 通过 |

## 7. 文档与报告

- 设计说明书：§5.2（新字段与新表）、§6.2 / §6.3（接口，登记到标记区间）、§8.2（如有补充）。
- 执行报告 `docs/04-开发任务/25a-达人编号与推广状态-后端-执行报告.md`：迁移与锁表评估、接口示例、清理任务的判定规则、测试清单、偏离与遗留。

## 8. 禁止事项与停止条件

**禁止**：修改已合并的迁移脚本；新增依赖；建通用 `crm_config`；改 `crm_kol_item` 结构；修改两个前端除 `locales` 以外的文件；删除或弱化测试；推送 `main`、合并 PR；`push --force`、`reset --hard`、改写已推送历史。

**停下来报告**（推送已完成的提交，以 `[WIP]` 标题创建 PR）：ADR-023 与现有代码冲突且无法按本提示词实现；接口约定需要改动；合理排查后验证仍失败。

完成后回复一段总结：PR 链接、做了什么、测试结果、遗留。
