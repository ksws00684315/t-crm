# 工程规约符合性只读审查（main `1ee34a53`，2026-10-27）

## 1. 总体结论

主干没有发现阻断交付的硬伤：模块边界、接口权限声明、多公司隔离（跨公司绕过都写明了原因）、迁移不可改、密钥管理、依赖版本这几类核心规则都有自动化门禁或人工逐处核对过，结论是符合。问题集中在两块：一是近期合并的佣金和提现代码（任务 62/63/47、PR #112/#116），共 3 项应修，分别是缺字段注释、冻结和恢复提现时没有落实商务 `OWN` 数据范围、分页 `total` 的类型与全站不一致；二是 JaCoCo 排除规则过宽，让覆盖率门禁实际变弱。其余都是建议级问题。

## 2. 逐条复核表

| # | 规则（出处） | 结论 | 证据 | 严重度 |
|---|---|---|---|---|
| 1 | 模块之间只依赖对方 `api` 包、无循环（01 §3） | 符合（已由 ArchUnit 强制） | `backend/src/test/java/com/tcrm/admin/ArchitectureTest.java:85-115` | — |
| 2 | controller → service → mapper 单向（01 §3） | 符合（已由 ArchUnit 强制） | `ArchitectureTest.java:57-73` | — |
| 3 | `*DO` 不出现在 controller 和 api 的入参出参（01 §4） | 符合（已由 ArchUnit 强制：DO 只能被 model/mapper/service 引用） | `ArchitectureTest.java:74-83`；controller 只引用 model 中的枚举，例如 `commission/controller/AdminPayoutAccountVO.java:3-5` | — |
| 4 | 禁止用 `Map` 传递业务数据（01 §4） | 部分违反 | `order/controller/IdleKolCleanupController.java:31`（`R<Map<Long,Integer>>`）；`kol/controller/PromotionRecordVO.java:9-10`（`Map<String,Object>`）；`product/controller/PromoSheetUploadController.java:65,111` | 建议 |
| 5 | controller 返回 `*VO`（01 §4） | 部分违反 | `order/controller/OrderController.java:48-108` 返回 `*DTO`；`product/controller/ProductLinkController.java:46-93`、`system/controller/PostTemplateController.java:31` 返回 service 内部类型 | 建议 |
| 6 | 每个接口声明权限码、角色或公开（06 §2、ADR-018） | 符合（已由 ArchUnit 强制，含 `@SaCheckLogin(type="kol")` 与 `@PublicEndpoint`） | `ArchitectureTest.java:122-175`；共 56 个 `@RestController`，逐个核对无遗漏 | — |
| 7 | 超管专属操作（审批预付、标记提现已打款）只允许 `SUPER_ADMIN`（ADR-018 决定 5） | 部分违反（文档冲突） | 代码按 ADR-032 实现为可授予的 `withdrawal:pay` / `prepay:*`：`commission/controller/AdminWithdrawalController.java:174-175`、`PrepayController.java:168,201-204`；但 ADR-018 第 39-41 行和第 81 行、设计说明书第 121、127 行仍写「超管负责」，而设计说明书第 297 行和 ADR-032 第 127 行已改为可授予。ADR-018 头部没有标注被 ADR-032 修订 | 应修（文档） |
| 8 | 商务 `OWN` 范围在 service 层落实、按 ID 访问须校验范围（06 §2、设计 §3.2） | 部分违反 | `commission/service/WithdrawalService.java:1209-1239`（`freeze`，第 1220 行只调 `requireKol`）、`:1243-1263`（`resume`，第 1251 行同样）没有调用 `requireKolScope`；同一文件的其他入口（第 562、879、960、993、1070 行）都校验了。订单、退款、达人、商品、提现列表、预付、收款账户、收入均已落实 | 应修 |
| 9 | 达人 ID、公司 ID 只从登录态取（03 §7、06 §2） | 符合 | kol-api 全部用 `current.kol()`；请求体里的 `tenantId` 只出现在 `security/controller/SwitchCompanyRequest.java:6`（ADR-022 准许）和 `kol/controller/KolSelectCompanyRequest.java:9`（ADR-014 登录选公司，带一次性 selectionToken） | — |
| 10 | 业务表有 `tenant_id NOT NULL`，例外只限 §3.4（04 §1） | 符合（已由 `SystemIT` 集成测试强制） | `backend/src/test/java/com/tcrm/admin/system/service/SystemIT.java:154-245`；脚本解析结果：没有 `tenant_id` 的只有 `sys_tenant`、`sys_config`、`crm_fx_rate`、`crm_link_index_outbox`、`sys_user`，都在设计说明书 §3.4 例外清单中 | — |
| 11 | 绕过租户过滤须显式声明原因（06 §2、ADR-014） | 符合 | 共 57 处 `TenantScope.runSystem/callSystem`，每处都带 reason（`TenantScope.java:31-38` 强制 reason 非空）；7 处 `@InterceptorIgnore` 都有注释（`link/mapper/ClickLogMapper.java:107-141`、`commission/mapper/WithdrawalOutboxMapper.java:19`、`PrepayOutboxMapper.java:19`）；唯一的 `JdbcTemplate` 用法 `product/service/ProductLinkService.java:190-205` 包在 system scope 中并写了原因（ADR-019 公开模板） | — |
| 12 | 定时任务、异步任务的租户上下文（06 §2） | 符合（抽查） | `task/service/ImportTaskService.java:183-197`、`product/service/CopyJobWorker.java:155-159` 从任务行取公司后进入 company scope；`system/service/AuditWriter.java:20-24` | — |
| 13 | 定时任务通过 `shared` 分布式锁执行（02 §6） | 部分违反 | 21 个 `@Scheduled` 类中只有 `product/service/KolCleanupWorker.java:96` 没用 `DistributedLocks`，靠行级租约防重复 | 建议 |
| 14 | 迁移脚本命名 `V{yyyyMMddHHmm}__…`（04 §4） | 符合 | 85 个脚本全部匹配 | — |
| 15 | 已合并迁移不得修改（04 §4） | 符合 | 在 main 历史上执行 `git log --diff-filter=M` 结果为空；只有基线提交 `fb385747`（#2，2026-10-05）删除并改名了初始化脚本 `V1__init_system.sql`，属于工程基线整改，之后再无修改 | — |
| 16 | 每张表、每个字段有注释（04 §1） | 违反（1 个脚本） | `V202610251100__create_commission_withdrawal_tables.sql`（任务 63，#121）中 8 张表的通用字段 `id/tenant_id/create_time/update_*` 全部没有注释，另有业务字段缺注释：`withdrawal_id`（第 103、145、172、201、243 行）、历史表 `note`（第 177 行）、outbox 的 `attempts/next_attempt_at/delivered_at`（第 246-248 行）。其余 84 个脚本经解析没有缺失 | 应修 |
| 17 | 只用参数绑定，禁止 `${}`（04 §5） | 符合 | `${}` 只出现在分区 DDL：`ClickLogMapper.java:122-142`，参数经 `link/service/ClickPartitions.java:21` 的正则白名单校验 | — |
| 18 | 禁止 `SELECT *`（04 §5） | 违反 | 17 处，分布在 6 个 Mapper：`product/mapper/KolItemMapper.java:24,33,41`、`CopyJobMapper.java:13`、`CopyJobItemMapper.java:14,22`、`commission/mapper/CommissionItemStateMapper.java:23,29`、`CommissionRuleVersionMapper.java:16,22` 等 | 建议 |
| 19 | 禁用 FK、触发器、`TIMESTAMP`、`DOUBLE`、`ENUM`（04 §1-2） | 符合 | 扫描全部迁移，无命中 | — |
| 20 | 路径复数 kebab-case、`/{前缀}/{模块}/{资源}`（03 §1） | 部分违反 | `/admin-api/files/{id}` 与 `/admin-api/files` 缺少模块段（`commission/controller/AdminPayoutAccountController.java:89`、`AdminWithdrawalController.java:152`）；路径中的模块与代码模块不一致：`order/controller/IdleKolCleanupController.java:21` → `/admin-api/kol/kols`，`kol/controller/LinkTemplateController.java:31` → `/admin-api/link/templates`，`product/controller/PromoSheetUploadController.java:29` → `/admin-api/kol/...`。路径中没有出现 camelCase | 建议 |
| 21 | 统一信封与错误码登记在 `ErrorCode`（03 §3-4） | 符合 | `shared/R.java:7-16` 只接受 `ErrorCode` 枚举；前端映射由 `admin-ui/src/api/__tests__/error-codes.spec.ts` 校验 | — |
| 22 | 信封 `msg` 始终为英文（03 §7） | 疑似违反 | `integration/service/WeidianItemCopyService.java:97-102` 以中文 message 抛 `BusinessException`，而 `GlobalExceptionHandler.java:42-43` 会把自定义 message 原样放进 `msg`。需要确认这个异常是否会传到 HTTP 层（复制 worker 中会被捕获） | 建议 |
| 23 | 分页结构 `{items,total:"1024",page,size}`（03 §6） | 部分违反 | 全站 `PageResult.total` 是 `long`，经全局 `ToStringSerializer` 输出为字符串（`MultiCompanyAccountIT.java:161` 断言 `"1"`）；但佣金模块 6 个分页 VO 把 `total` 声明为 `int`，输出为数字：`commission/controller/SettlementDetailController.java:73`、`KolWithdrawalController.java:201`、`AdminWithdrawalController.java:249,458`、`KolCommissionController.java:75`、`FinanceTaskController.java:78`。ADR-032 第 179 行写「分页 total 全站为数字」，与规范 03 和事实都不符；`admin-ui/src/api/commission/finance-types.ts:7` 仍声明 `total: string` | 应修 |
| 24 | ID、金额序列化为字符串（03 §7） | 符合 | `shared/CommonConfiguration.java:26-29`（`Long`、`long`、`BigDecimal` → String）；佣金金额用 `setScale(2, HALF_UP)`（`CommissionIncomeController.java:283-286`） | — |
| 25 | 选择器不用 `size=100` 冒充全量（03 §6） | 符合（1 处疑似） | `admin-ui/src/views/product/copy-token/CopyTokenCreateDialog.vue:163` 是带关键字的远程搜索，规范允许；`admin-ui/src/api/commission/finance.ts:182` 只在预览模式下执行。疑似：`kol-portal/src/views/funds/index.vue:118,159` 只取前 50 条提现记录，就当作全集查找「驳回后重新提交」的原单 | 建议 |
| 26 | Controller 不加类级 `@Validated`（03 §5） | 符合（已由 ArchUnit 强制） | `ArchitectureTest.java:183-193` | — |
| 27 | 无通配符导入、`System.out`、`printStackTrace`、空 catch（02） | 符合（已由 Checkstyle 强制） | `config/checkstyle/checkstyle.xml` | — |
| 28 | 无 `public` 实例字段（02 §1） | 符合（已由 ArchUnit 强制，允许 `public final`） | `ArchitectureTest.java:195-212` | — |
| 29 | 无 Lombok、无字段注入（02 §1） | 符合 | 扫描 `@Autowired/@Resource/@Inject` 字段无命中，`lombok` 无命中 | — |
| 30 | 金额不用 `double/float`；时间不用 `Date/LocalDateTime`（02 §6） | 符合 | `double` 只出现在 `link/service/ClickStreamMonitor.java:27-30` 的监控比例；`LocalDateTime` 只用于带显式时区的解析：`order/service/OrderImportFields.java:84`、`integration/service/WeidianBusinessApiParser.java:285` | — |
| 31 | 写多表的 `@Transactional` 带 `rollbackFor`（02 §6） | 部分违反 | `kol/service/KolPortalProfileService.java:70`（写达人表并写审计） | 建议 |
| 32 | 日志不输出个人信息与凭证（02 §4、06 §4） | 符合 | 扫描全部 `LOG.*` 的参数，没有邮箱、IP、用户名、账号；`kol/service/KolMails.java:48` 对邮箱做了脱敏；`integration/service/LoggingMailSender.java:27-32` 受开关控制，`application.yml:93` 默认关闭，`deploy/README.md:141` 写明生产禁止开启 | — |
| 33 | 不提交密钥、真实账号、默认密码（06 §1、§5） | 符合 | 只提交 `deploy/.env.example` 且值全部为空；`application.yml` 中的密码类占位符都没有默认值；常见密钥特征扫描无命中；迁移脚本中没有口令 | — |
| 34 | 对外复制接口和短链解析的鉴权、限流、SSRF 防护（06 §3、ADR-019） | 符合 | 全部按 IP 限流：`product/controller/ProductLinkController.java:49-95`；`convert` 与 `resolve` 带令牌；jobCode 为 128 位随机值（`ProductLinkService.java:566-570`）；短链解析有白名单、逐跳校验、公网地址校验、钉住 IP、重定向次数上限（`ShortUrlResolver.java:71-79`、`ShortUrlHttpFetcher.java:46-49`） | — |
| 35 | 导出防公式注入（06 §3） | 符合 | `commission/service/WithdrawalService.java:2005-2015`、`PrepayService.java:982-990`、`task/service/ExcelFiles.java:219` | — |
| 36 | CSP 等安全响应头（06 §3） | 符合，另有 1 项疑似功能问题 | `deploy/nginx/snippets/security-headers*.conf`；疑似：`security-headers-web.conf:4` 中 `img-src 'self' data:`，会拦截外部 HTTPS 商品图（`crm_product.image_url`、提现占用快照 `image_url`、任务 71 的订单行图片） | 应修（待浏览器确认） |
| 37 | 前端界面文字走语言包（02 §7、11 §7） | 基本符合 | 管理端中文由 `admin-ui/src/locales/__tests__/residual.spec.ts` 强制；达人端没有同类扫描。英文写死扫描未发现页面文字；后端把中文直接写进审计摘要，与界面语言无关：`kol/service/BdTransferService.java`（27 处中文字面量）、`system/service/OperLogService.java:152-165`（residual 白名单为此留了例外） | 建议 |
| 38 | 时间按当前界面语言格式化（02 §7） | 违反（1 处） | `kol-portal/src/views/account/index.vue:296` 使用 `new Date(...).toLocaleString()`（按浏览器语言） | 建议 |
| 39 | 管理端列表使用 `ListSearch`、`DataTable`、`RowActions`、`CopyText`（11 §2） | 部分违反 | 行操作没有用 `RowActions`：`admin-ui/src/views/product/product/index.vue:398-418`、`product/copy-token/index.vue:393-404`（原生 `<button>`）、`system/import-task/index.vue:278-286`；抽屉内手写分页：`fund/withdrawal/WithdrawalDrawer.vue:236`（DataTable `paged=false` 再加 `el-pagination`） | 建议 |
| 40 | 字号只用 token（10 §1、11 §10） | 符合（已由 `tokens.spec.ts` 强制 `font-size: Npx`） | 扫描出唯一的非 CSS 写法：`admin-ui/src/layout/index.vue:292` 中水印 `fontSize: 14`（扫描覆盖不到；数值与正文一致） | 建议 |
| 41 | 未实现功能显示「开发中」，不用假数据（11 §9） | 部分违反（仅非生产模式） | 生产源码里带有演示和预览用的假数据分支：`kol-portal/src/api/creator.ts:3-170`、`api/auth.ts:33-56`（演示登录、假令牌 `demo-token`）、`api/payout-account.ts:3-109`；`admin-ui/src/api/commission/finance.ts:28-60` 配合 `admin-ui/preview/commission/*`（1199 行）。都受 `DEV && MODE==="demo"` 或 `MODE==="commission-preview"` 且主机为 localhost 的条件控制，生产构建不会启用，但这是交付源码中未被需求要求的功能和代码路径 | 建议 |
| 42 | 前端页面不直接调用 http 或 axios（01 §6） | 符合 | views 只从 `@/utils/http/error` 导入 `ApiError` | — |
| 43 | 不使用 `v-html`；令牌不进 URL（06 §3、02 §7） | 符合 | 无命中 | — |
| 44 | 直接依赖写死稳定版（07 §1） | 符合 | 两个 pom 与两个 `package.json` 中没有范围、SNAPSHOT 或预发布版本（只有 `engines.node` 使用范围） | — |
| 45 | 由 Spring Boot 管理的依赖不写 `<version>`（07 §1） | 违反 | `backend/pom.xml:91`（`spring-security-crypto` 7.1.1）、`:102,108`（testcontainers 2.0.5）、`redirect/pom.xml:38`。Boot 4.1.1 BOM 管理了这两个版本（本地 `spring-boot-dependencies-4.1.1.pom:205,209` 中版本相同） | 建议 |
| 46 | 无 GPL/AGPL；附第三方许可声明（07 §3） | 符合 | 4 个工程都有 `THIRD-PARTY-NOTICES`；其中出现的 GPL 都是 Jakarta 的「EPL-2.0 OR GPL-2.0+CE」双重许可 | — |
| 47 | 集成测试命名 `*IT` 且带 `@Tag("docker")`（05 §1） | 符合 | 所有 `*IT.java` 都有该标签；没有不叫 `*IT` 却带 docker 标签的类 | — |
| 48 | 无 `@Disabled`、跳过或被注释掉的断言（05 §2） | 符合 | 没有 `@Disabled`、`.skip` 或注释掉的断言；`WeidianLiveConnectivityTest`（`@Tag("weidian-live")`，默认排除，`backend/pom.xml:19`）用 `assumeTrue` 做手动实测，其中 59 处中文字符串违反「代码用英文」 | 建议 |
| 49 | 覆盖率门槛未降低（05 §4） | 符合（数值）/ 部分违反（排除范围） | `git log -p backend/pom.xml`：阈值 70/60、85/80 自 `fb385747` 起从未改动。但排除规则 `backend/pom.xml:221-233` 中的 `**/*Query*`、`**/*Application*`、`**/config/**`、`**/*Request*` 误排除了带业务逻辑的类：`order/service/RefundQueryService`（235 行，订单模块门槛 85%）、`order/service/KolItemSalesQueryService`、`product/service/CopyJobQueryService`（257 行）、`link/service/KolItemVisitQueryService`、`security/config/CompanyHeaderInterceptor`、`security/config/KolApiInterceptor`（安全模块门槛 85%）、`shared/RequestTraceFilter` | 应修 |
| 50 | 业务规则有单元测试（05 §3） | 符合（抽查） | commission 和 order 的 service 类几乎都有对应 `*Test`；`CommissionSnapshotListener`、`WithdrawalOutboxJob`、`OrderItemInfoService`、`KolItemSalesQueryService` 在测试中从未被引用 | 建议 |
| 51 | 设计说明书接口清单与路由一致（12、AGENTS 12） | 基本符合 | 抽查订单、佣金、提现、达人四个模块：代码中的路由在说明书中都有记录。设计说明书第 1111 行残留 `PUT /admin-api/kol/kols/{id}/freeze`，实际路由为 `POST /admin-api/commission/withdrawal-freezes`（第 1292-1293 行也已登记）；`GET /admin-api/dashboard/kol/{}` 未实现（看板任务 48-51 尚未开始） | 建议 |
| 52 | OpenAPI（springdoc）（03 §8） | 已登记例外 | `docs/05-工程规范/09-现状差距与整改清单.md:206,261`：没有找到支持 Boot 4.1 的稳定版，暂时跳过 | — |
| 53 | Git：禁止直推 main（非过程文档）、Squash merge（08 §1、§3） | 部分违反（历史） | `51e59cc6`、`6d25ed34`、`ad4c6f79`（10-17/18）直接推送了 `docs/03-设计` 和 `docs/06-架构决策`（ADR-011 只豁免过程文档）；`7549a3cc` 用合并提交方式合并了 #116，没有 squash | 建议 |

## 3. 违反项明细

### 应修

**A1 冻结和恢复提现没有落实商务 `OWN` 数据范围**（06 §2「按 ID 访问资源时必须校验可访问范围」，设计 §3.2）
- 位置：`backend/src/main/java/com/tcrm/admin/commission/service/WithdrawalService.java:1209-1239`（`freeze`）、`:1243-1263`（`resume`）；入口 `commission/controller/WithdrawalFreezeController.java:34-48`。
- 问题：两个方法只调用了 `requireKol(kolId)`（第 1703 行，只判断达人是否存在），没有调用同一文件中已有的 `requireKolScope(user, kol)`（第 1735 行）。同文件的审核、付款、详情都调用了它（第 562、879、960、993、1070 行）。
- 影响：`commission:freeze` 是可授予的权限码（设计 §3.1 第 284 行）。超管把它授给 `OWN` 范围的商务后，该商务可以冻结或恢复本公司任意达人的提现资格。另外，对非本人的达人，按规范应返回 404（`COMMON_NOT_FOUND`），现在不会。
- 验证：补一条 `OWN` 商务冻结他人达人返回 404 的单元测试。

**A2 提现相关 8 张表缺少字段注释**（04 §1）
- 位置：`backend/src/main/resources/db/migration/V202610251100__create_commission_withdrawal_tables.sql`（任务 63，#121）。
- 问题：8 张 `crm_commission_withdrawal*` 表的 `id`、`tenant_id`、`create_time`、`update_by`、`update_time` 都没有 `COMMENT ON COLUMN`；`withdrawal_id`（第 103、145、172、201、243 行）、`crm_commission_withdrawal_history.note`（第 177 行）、`crm_commission_withdrawal_outbox.attempts/next_attempt_at/delivered_at`（第 246-248 行）也没有。其余 84 个脚本经解析，所有表和字段都有注释。
- 影响：违反强制条款。该脚本已经合并，不能修改，只能新增迁移补充注释。现有 IT 只对个别表检查注释（`BdTransferIT.java:232`、`DeparturePlanIT.java:142`），没有全库检查，所以门禁没有拦住。

**A3 佣金模块分页 `total` 输出为数字，与规范及其他模块不一致**（03 §6）
- 位置：`commission/controller/SettlementDetailController.java:73`、`KolWithdrawalController.java:201`、`AdminWithdrawalController.java:249,458`、`KolCommissionController.java:75`、`FinanceTaskController.java:78`，均为 `int total`。
- 问题：规范要求 `"total": "1024"`。其余模块使用 `PageResult<T>(…, long total, …)`，经 `CommonConfiguration.java:27-28` 输出为字符串（`MultiCompanyAccountIT.java:161` 断言 `"1"`）。ADR-032 第 179 行「分页 total 全站为数字」与规范和事实都不符；`admin-ui/src/api/commission/finance-types.ts:7` 仍声明 `total: string`，前端类型与后端对不上（01 §6）。
- 影响：同一系统内两种格式。前端目前用 `Number(...)` 兼容，所以没有显性故障。
- 处理：需要负责人裁定：按规范把 VO 改为 `long` 并修正 ADR-032，或者修改规范。

**A4 JaCoCo 排除规则过宽，覆盖率门禁实际变弱**（05 §4「只排除启动类、配置类、纯数据类」）
- 位置：`backend/pom.xml:221-233`。
- 问题：按通配符匹配类名，误排除了带逻辑的类：`order/service/RefundQueryService`（235 行，order 门槛 85/80）、`order/service/KolItemSalesQueryService`、`product/service/CopyJobQueryService`（257 行）、`link/service/KolItemVisitQueryService`、`security/config/CompanyHeaderInterceptor`（ADR-022 防串公司拦截器）与 `security/config/KolApiInterceptor`（security 门槛 85/80）、`shared/RequestTraceFilter`。阈值本身在历史中没有被降低。
- 影响：权限、租户拦截器和订单查询逻辑不计入覆盖率，模块门槛的实际约束弱于规范。
- 处理：把排除规则收窄为后缀精确匹配（如 `**/*Query.class`、`**/*Request.class`），并评估收窄后能否达标。

**A5（文档）ADR-018 与 ADR-032、设计说明书在「资金最后一道关」上互相矛盾**
- 位置：ADR-018 第 39-41、81 行，设计说明书第 121、127 行写「审批预付、标记提现已打款仅超管」；ADR-032 第 127 行和设计说明书第 297 行写 `withdrawal:pay` 可授予给财务模板。
- 现状：代码按后者实现（`AdminWithdrawalController.java:174`、`PrepayController.java:168,204`）。ADR-018 头部只标注了被 ADR-022 修订，没有标注 ADR-032。
- 影响：交付文档自相矛盾，客户或审查方会读到两种口径。按 AGENTS 规定，这种冲突应由负责人确认。

**A6（疑似）生产 CSP 拦截外部商品图片**
- 位置：`deploy/nginx/snippets/security-headers-web.conf:4`，`img-src 'self' data:`。
- 依据：`crm_product.image_url`（`V202610052300__create_shop_and_product.sql:76`，「图片 HTTPS 地址」）、提现占用快照中的 `image_url`、任务 71 的订单行图片都是外部 HTTPS 地址；预览环境也引用了同一个 snippet（`deploy/nginx/preview/preview.conf:56,72,85,144`）。
- 需要验证：在 `preview.sh up` 环境用浏览器查看商品、订单链路、提现抽屉的图片是否被 CSP 拦截（控制台会有 `Refused to load the image` 报错）。如果确认被拦截，就需要把微店图片域名加进 `img-src`。

### 建议（不阻断）

- B1 前端交付源码含演示和预览假数据路径（见第 41 条）：生产构建不会启用，但违背「不加未要求的功能」和 11 §9 的精神，交付前建议移除，或单独放到不随交付的目录。
- B2 `RowActions` 未统一使用 3 处，`WithdrawalDrawer` 手写分页 1 处（见第 39 条）。
- B3 `SELECT *` 共 17 处（见第 18 条）；`Map` 作为业务出参 3 处（见第 4 条）；controller 直接返回 DTO 或 service 类型（见第 5 条）。
- B4 Boot 管理的依赖写死了版本（见第 45 条），升级 Boot 时容易遗漏。
- B5 `KolPortalProfileService.java:70` 缺 `rollbackFor`；`KolCleanupWorker.java:96` 没有走分布式锁。
- B6 后端中文文案：`WeidianItemCopyService.java:97-102` 以中文作为信封 `msg`（疑似）；审计摘要写死中文（`BdTransferService`、`OperLogService.java:152-165`），英文界面下会显示中文。
- B7 `kol-portal/src/views/account/index.vue:296` 没有按当前界面语言格式化时间；`kol-portal/src/views/funds/index.vue:118,159` 只取前 50 条提现记录当作全集。
- B8 路径缺模块段，或路径模块与代码模块不一致（见第 20 条）。
- B9 设计说明书第 1111 行有一个过时的冻结接口条目。
- B10 历史上有 3 个提交直接推送了设计或 ADR 文档，#116 没有用 squash 合并（见第 53 条）。
- B11 迁移版本号有 3 次乱序合并（`V202610171100`、`V202610171700`、`V202610210001` 合并时都已有更晚版本的脚本），预览环境靠 `SPRING_FLYWAY_OUT_OF_ORDER=true`（`deploy/docker-compose.preview.yml:48`）才能运行，生产 compose 没有这个设置。目前生产还没有数据，所以没有影响；上线后如果再出现乱序合并，升级会失败，建议在 PR 审查时检查版本号单调递增。
- B12 自动化缺口：`SystemIT` 只检查有 `tenant_id` 的表都为 NOT NULL，没有 `tenant_id` 的表只用 `contains` 断言，新增一张漏加 `tenant_id` 的表不会失败；全库「表和字段都有注释」没有测试；达人端没有类似 `residual.spec.ts` 的写死文字扫描；`tokens.spec.ts` 只匹配 `font-size: Npx`。

## 4. 已由自动化强制的规则

| 规则 | 机制 |
|---|---|
| controller 不依赖 mapper；service 不依赖 controller；`*DO` 只在 model/mapper/service；`*DO` 必须位于 model 包 | `backend/src/test/java/com/tcrm/admin/ArchitectureTest.java` |
| 模块之间只依赖 `api` 包；`shared` 不依赖业务模块；模块之间无循环 | 同上 |
| 每个接口声明权限码、角色、公开或达人登录 | 同上（含反例 fixture） |
| Controller 不加类级 `@Validated` | 同上 |
| 无 public 可变实例字段 | 同上 |
| 事件只通过 `TransactionalEvents` 发布；controller 与 commission/notification 不用 JDBC | 同上 |
| redirect 的分层 | `redirect/src/test/java/com/tcrm/redirect/ArchitectureTest.java` |
| 格式（google-java-format） | Spotless |
| 禁止通配符导入、未使用的导入、`System.out/err`、`printStackTrace`、空 catch | Checkstyle `config/checkstyle/checkstyle.xml` |
| 覆盖率阈值（整体 70/60；security、commission、order、stats 为 85/80；system 和 order 的 controller 行覆盖 70） | JaCoCo `backend/pom.xml`（排除范围问题见 A4） |
| 业务表 `tenant_id NOT NULL` 与 §3.4 例外 | `SystemIT.shouldRequireTenantIdOnBusinessTables_whenMigrationsRun`（docker） |
| 两种语言包键一致；路由标题键存在；英文不出现 KOL | `admin-ui|kol-portal/src/locales/__tests__/messages.spec.ts` |
| 管理端源码不写死中文 | `admin-ui/src/locales/__tests__/residual.spec.ts` |
| 前端错误码映射与后端一致 | `*/src/api/__tests__/error-codes.spec.ts` |
| 两端 `tokens.css` 字节一致；不出现 `font-size: Npx`；达人端无裸色值和裸间距；DataTable 和 CopyText 样式两端一致 | `admin-ui/src/style/__tests__/tokens.spec.ts` |
| 页面单根元素；`componentName` 与路由一致 | `page-single-root.spec.ts`、`component-names.spec.ts` |
| compose 与 `.env.example` 的必填变量同步 | `deploy/scripts/check-compose-env.py` |
| 漏洞与许可证 | OSV-Scanner（CI 暂停期间由审查人本地复跑） |

## 5. 未能检查的范围

- 没有运行任何构建、测试或浏览器验证（按要求只读）。上表中的「符合（自动化强制）」以门禁存在为依据，没有在本次复跑。A6（CSP）、B6（中文 `msg` 是否传到 HTTP 层）需要实际运行才能确认。
- 05 §3「每个权限码至少一条 403 用例」、「每个模块的跨公司 404 用例」没有逐个对照。只确认了 `TenantIsolationIT`、`WithdrawalServiceScopeTest`、`AdminWithdrawalControllerTest` 等存在。
- 只抽查了金额「保留 2 位小数」：`BigDecimal` 经 `ToStringSerializer` 输出时保留原 scale，没有逐个 VO 确认 scale 为 2，比例为 4。
- 前端写死英文只用正则扫描了模板属性和文本节点，`<script>` 中拼接的英文和 `t()` 之外的 `ElMessage` 文案可能有漏网。
- 只抽查了 `stats` 模块和看板：stats 目前只有 `package-info.java`，看板尚未实现。
- 没有对照规范 08 §4 的 CI 工作流，也没有检查 `deploy/scripts` 中的备份与回滚脚本。
- 只核对了日志中的个人信息与 `AuditSanitizer` 是否存在，没有核对操作日志请求参数的脱敏字段清单是否覆盖收款账号、钱包地址。
- 只抽查了 `docs/03-设计/系统详细设计说明书.md` 中订单、佣金、提现、达人的接口清单，其他模块没有比对。
