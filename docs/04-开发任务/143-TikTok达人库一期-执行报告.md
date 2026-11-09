# 任务 143：TikTok 达人库一期（公司版本 + B 公司达人库）执行报告

> 工作树 `/Users/hh-mini/Public/dev/t-crm/.worktrees/143-kol-library`，分支 `task/143-kol-library`。
> 基线 `origin/main`，先合并了 PR #246 携带的需求、ADR-038 与任务提示词（`bc41cba8`、`0628c6e4`），代码提交自 `de00e696` 起。
> 本任务分 A（公司版本）、B（达人库）两部分，同一个 PR。不改达人端与跳转服务。

## 1. 交付内容

| 部分 | 内容 | 提交 |
| --- | --- | --- |
| A | `sys_tenant.edition` 迁移、`Edition`／`@EditionScope`／`EditionInterceptor`（默认拒绝）、权限码按版本收窄、`/me` 与切换公司返回版本、平台新建公司选版本、架构测试白名单 | `de00e696` |
| B | `kollibrary` 模块（两张表、8 个接口、达人ID 规范化与判重、计算口径、审计脱敏）、集成测试 | `2293b988` |
| 管理端 | 达人列表／新增编辑／明细（两个页内页签）、按版本菜单与路由、B 公司首页为达人列表、账号管理按版本收窄、平台公司版本列与单选、切换公司关标签 | `0c7790f0` |
| 文档 | 设计说明书 §3.4、§5.2、§6.2 接口目录、§8.8 计算口径 | `75d9368f` |
| 修复 | 角色按 token 所在公司判定（P1-15 越权）、内容表「新增内容记录」按钮、`/` 与 `/home` 的版本首页、切换公司只留新首页、既有测试随版本收窄同步 | `b4a9d94d` |

## 2. 独立只读复核

复核由只读子代理逐条对照需求文档 P1-01～P1-21、1.1.1、1.2.1～1.2.6（每字段一行）、ADR-038 §1～§4、提示词 §2／§3／§5 与通用要求。**下表为复核结论与本轮的处置**。

| 编号 | 复核结论 | 处置 | 实现位置 |
| --- | --- | --- | --- |
| P1-15 删除仅超管 | **不满足 → 已修**：Sa-Token 角色注解收到的登录 id 与当前 id 不是同一对象，回落到「账号最后所在公司」的角色，一人属 A、B 时 B 子账号可删 | `roleNames`／`permissionNames` 两侧统一按 `Long` 比较，当前登录人一律取 token 所在公司的成员关系；新增集成测试（同一人 A、B 双成员，切到 B 后子账号删除 403、B 超管 200） | `security/api/CurrentUserContext.java`、`KolLibraryIT`、`CurrentUserContextTest` |
| 1.2.5 ③ 内容表「新增内容记录」 | **不满足 → 已修**：`DataTable` 的 `plain` 模式不渲染 `#toolbar`，按钮被吞掉 | 操作按钮移到表格上方（`data-test="content-create"`），不产生重复标题 | `views/kol-library/kol-detail/index.vue` |
| 提示词 §2.4-3 B 公司首页 | **不满足 → 已修**：`/` 的静态 `redirect: "/home"` 在守卫之前执行，B 公司停在未声明的 `/home` 进而 403 | `/` 的 redirect 按版本决定；守卫对 `/home` 按版本转到达人列表；`/home` 路由声明两个版本，保证能走到版本判定 | `router/routes.ts`、`router/guard.ts` |
| 提示词 §2.4-5 切换公司 | **部分满足 → 已修**：切换后残留上一版本首页标签 | 关闭全部标签后只保留新版本首页；恢复的标签列表丢弃另一版本的 affix | `composables/useCompanySync.ts`、`utils/tabs.ts` |
| 1.2.5 ② 用户名可复制 | 部分满足：复制值为 `@用户名`（需求 ② 写「用户名可复制」、③ 写「@用户名（可复制）」） | 保留 `@` 前缀，与页头展示一致；在报告登记为口径说明 | `kol-list/index.vue`、`kol-detail/index.vue` |
| 1.2.5 ② 列表字段 | 部分满足：列表项多返回 `followers`、`grade`、`cooperationStage` | 保留（前端不渲染，不影响列数）；登记为说明 | `controller/LibraryKolVO.java` |
| 1.2.6 ③ 内容表合计 | 部分满足：需求写「指标卡、内容表合计」，实现只有指标卡 | 按提示词 §3.2.2「表尾或指标卡体现累计数，两者一致」采用指标卡，口径由同一 `LibraryMetrics` 计算；登记为说明 | `kollibrary/service/LibraryMetrics.java` |
| 1.2.5 ③ 页头 | 满足：页头卡片在需求字段外多显示「寄样状态」与跟进负责人 | 保留（只增不减，便于跟进）；登记为说明 | `kol-detail/index.vue` |
| 提示词 §5-1 前端单测 | 部分满足 → 已补 | 新增：B 公司 affix 与首页、`/` 跳转、`/home` 守卫判定、B 子账号无「账号管理」、切换公司只留新首页标签 | `utils/__tests__/tabs.spec.ts`、`layout/__tests__/library-layout.spec.ts` |
| 既有测试随版本收窄 | **不满足（CI 会红）→ 已修** | `PermissionIT`、`BdTransferIT` 改用 `Permission.codesOf(Edition.DISTRIBUTION)`；`SystemIT` 业务表清单补两张达人库表 | 对应测试文件 |
| 其余（P1-01/02/03/10～14/16～18、1.1.1、1.2.1 全字段、1.2.2、1.2.3、1.2.4、1.2.5 ①④⑤⑥⑦、1.2.6 ①②、ADR-038 §1～§4、提示词 §3） | 满足 | — | 见各提交 |

## 3. 自动检查

后端（JDK 21）：

| 命令 | 结果 |
| --- | --- |
| `mvn -q spotless:check` | 通过 |
| `mvn -B verify` | 通过：`Tests run: 2315, Failures: 0, Errors: 0`；Checkstyle 0 violation；JaCoCo 各门槛全部达标 |
| `mvn -B verify -Pdocker-it`（分批执行，见下） | 全部通过 |

集成测试分批（避免一次起 39 个容器的资源问题）：

| 批次 | 类 | 结果 |
| --- | --- | --- |
| A | `EditionIT`、`KolLibraryIT`、`SystemIT`、`PermissionIT`、`BdTransferIT`、`TenantIsolationIT`、`MultiCompanyAccountIT` | 83 通过 |
| B | `CopyTokenIT`、`PromoSheetIT`、`SheetSourceIT` | 19 通过 |

说明：首轮全量一次跑 39 个 IT 时有 4 个类共 33 个 error，全部是 `HikariPool ... Connection refused`（Testcontainers 容器在资源紧张时未起来）；同样的类分批重跑全部通过，判定为环境问题，不是代码缺陷。

管理端：

| 命令 | 结果 |
| --- | --- |
| `pnpm install --frozen-lockfile` | 通过（lockfile 无变化） |
| `pnpm lint` | 通过（eslint 0 警告，prettier 通过） |
| `pnpm typecheck` | 通过 |
| `pnpm test` | 113 文件 / 889 用例全部通过 |
| `pnpm build` | 通过 |
| `pnpm notices:check` | 通过（无差异） |

## 4. 浏览器验收

由浏览器子代理按 skill `$browser-acceptance` 在真实后端（18143）与管理端（8843）上执行，连共享中间件，54 项场景截图存 `docs/04-开发任务/assets/task-143/`（中文，1440×900 / 1280×800 / 2560 宽）。

第三轮（完整验收）18 项中 10 项通过、5 项不通过、3 项部分通过；第四轮复验发现其中 1 项未真正修复（内容记录加载时序，见下表最后两行）。全部修复、复验过程与结论：

| 发现 | 现象 | 修复 | 复验 |
| --- | --- | --- | --- |
| 达人明细页页头被压成 2px | 昵称与三个按钮全部不可见 | 明细页改块级布局（路由宿主是滚动 flex 容器，列向 flex 页被压缩） | 复验见下 |
| B 公司访问 A 路由白屏 | 守卫把 `/403` 又送去 `/403`，无限重定向 | `/403` 对两个版本开放，守卫放行自身 | 复验见下 |
| 缓存恢复的明细标签内容表为空 | KeepAlive 缓存页不再重挂，`/contents` 不再请求 | 明细页 `onActivated` 重新加载 | 复验见下 |
| 编辑档案后明细不刷新 | 详情缓存未失效 | 保存后刷新已打开的明细标签 | 复验见下 |
| 编辑标签标题显示 `route.libraryKolEdit` | 路由 meta 标了动态标题但页面没赋值 | 去掉标记，编辑标签带昵称 | 复验见下 |
| 切回 B 公司停在 `/home` | 固定首页用的是切换前传入的公司编码（`KOL_LIBRARY`）与实际 id 比较，永远不相等 | 改为按**生效中**账号的版本取首页；切换时先卸载旧页面再跳转，布局拒绝给已不属于当前会话的路由开标签 | 第四轮通过 |
| 内容记录不加载（刷新/首次进入） | **第四轮复验发现第三轮的修复不完整**：`load()` 与 `loadContents()` 并行启动，而内容加载在档案未就绪时直接返回，首次进入永远不发请求 | 改为先加载档案再加载内容（`loadAll`），并新增用例（去掉修复即失败） | 第五轮复验 |

复验结论（第四轮，基于提交 `aaff5523`）：R1 页头 376px 可见（三档分辨率）通过；R2 B 帐号访问 4 个 A 路由 + `/403` 共 8 次全部落在 403 页、无 `Infinite redirect`、`#app` 非空；R4 编辑标签标题为「编辑档案: T143 Beauty」、保存后明细昵称/分级/备注即时为新值；R5 B→A→B 回到 `/kol-library/kols`、固定标签「达人列表」、菜单与 `sessionStorage` 的 affix 均正确；R3 内容记录不加载 —— 不通过，已按上表修复，**第五轮通过** —— 从列表进入、浏览器刷新（标签从 tab 列表恢复）、新标签页直接打开明细 URL 三条路径都发出了 `GET .../kols/{id}/contents`，内容表均为 2 行、「共 2 条」，指标卡「内容条数」与行数一致。

通过项摘要：
- 平台新建公司弹窗（版本单选默认分销完整版）与公司列表「版本」列正常。
- B 超管菜单只有「达人库 → 达人列表」+「系统管理 → 账号管理」，无通知铃铛；B 子账号只有达人列表。
- B 公司登录后、`/`、`/home` 都进入 `/kol-library/kols`，固定标签「达人列表」。
- 达人列表 8 列（7 数据列 + 操作），无内容条数 / GMV；6 项筛选与 chip 可用，无匹配有空状态。
- 新增表单六卡片单列，四项必填校验齐全，达人ID 提示可粘贴 `@用户名`。
- 判重提示「该达人已由 {跟进负责人} 录入。」+ 查看入口，点击打开已有达人。
- 带货数据 6 指标卡 + 内容表 14 列齐全，金额右对齐带 ¥ 千分位，链接新窗口可点可复制。
- 内容记录弹窗实时计算（GMV 1000 / 成本 30+50 / 比例 12.5 → 预估佣金 ¥125.00、总投入 ¥205.00、ROI 4.88），保存后指标卡与行一致。
- 更新跟进只改 5 个字段，保存后页头与档案同步、其他字段不变。
- 两个达人明细标签同时打开，标题各为昵称，切换不串数据。
- 删除二次确认含达人名称，删除后关闭标签回列表并刷新；子账号看不到删除。
- 已删除达人 URL 显示空状态 + 返回列表。
- **A 公司回归**：超管 28 项菜单、运营（无财务、无账号管理）、商务（BD/OWN，多达人排行/邀请人业绩/达人收入/财务结算）与改动前一致；各打开达人列表、订单查询、店铺管理均正常。
- `auditTableAlignment()` 在达人库与平台页面全部返回空数组（店铺管理页 8 项为 A 公司既有页面，与本任务无关，登记在遗留事项）。

口径说明（需求与实现不一致处，已登记）：
- **零成本条目的 ROI**：需求 1.2.6 ③ 写「预估佣金 = 带货GMV × 佣金比例」，佣金比例非 0 时即使样品成本与坑位费为 0，总投入也不为 0，因此不会出现「—」；只有 GMV 为 0 且比例为 0（总投入为 0）时 ROI 显示「—」。这是按需求公式实现的必然结果，若客户希望「样品与坑位费都为 0 就显示 —」，需另行确认规则。
- 复制值为 `@用户名`（保留 `@`，与页头展示一致）。
- 累计数用指标卡呈现，内容表不加合计行（提示词 §3.2.2 允许二者之一，数值由同一口径计算）。
- 明细页头在需求字段外多显示「寄样状态」与「跟进负责人」。

## 5. 下拉取值清单（供客户核对，需求 Q-2）

| 字段 | 取值（编码 / 中文 / English） |
| --- | --- |
| 所属平台 | `TIKTOK` TikTok / `INSTAGRAM` Instagram / `YOUTUBE` YouTube / `OTHER` 其他 / Other |
| 国家站点 | `US` 美国 United States、`GB` 英国 United Kingdom、`MX` 墨西哥 Mexico、`DE` 德国 Germany、`FR` 法国 France、`IT` 意大利 Italy、`ES` 西班牙 Spain、`JP` 日本 Japan、`ID` 印度尼西亚 Indonesia、`TH` 泰国 Thailand、`VN` 越南 Vietnam、`MY` 马来西亚 Malaysia、`PH` 菲律宾 Philippines、`SG` 新加坡 Singapore、`OTHER` 其他 Other |
| 内容类目（多选） | `BEAUTY_PERSONAL_CARE` 美妆个护、`HEALTH_SUPPLEMENTS` 健康营养、`HOME_DAILY` 家居日用、`ELECTRONICS_3C` 电子 3C、`FASHION_ACCESSORIES` 服饰配饰、`MATERNAL_BABY` 母婴、`FOOD_BEVERAGE` 食品饮料、`PET` 宠物、`SPORTS_OUTDOOR` 运动户外、`OTHER` 其他 |
| 受众年龄段 | `AGE_18_24` 18-24、`AGE_25_34` 25-34、`AGE_35_44` 35-44、`AGE_45_54` 45-54、`AGE_55_PLUS` 55+ |
| 受众性别 | `MOSTLY_FEMALE` 女性为主、`MOSTLY_MALE` 男性为主、`BALANCED` 均衡 |
| 内容质量 / 带货潜力 / 配合度 | `HIGH` 高、`MEDIUM` 中、`LOW` 低 |
| 达人分级 | `S`、`A`、`B`、`C` |
| 合作阶段 | `FIRST_COOPERATION` 首次合作、`REPEAT_INVESTMENT` 复投、`LONG_TERM_PARTNERSHIP` 长期合作、`EXCLUSIVE` 独家合作 |
| 建联状态 | `NOT_CONTACTED` 未建联（默认）、`EMAIL_SENT` 已发邮件、`REPLIED` 已回复、`WHATSAPP_ADDED` 已加 WhatsApp、`REJECTED` 拒绝合作、`COOPERATING` 合作中 |
| 寄样状态 | `NOT_SENT` 未寄样（默认）、`SHIPPED` 已寄出、`RECEIVED` 已签收、`NOT_NEEDED` 不需要 |
| 内容形式 | `SHORT_VIDEO` 短视频、`LIVE` 直播、`POST` 图文 |
| 样品情况 | `GIFT` 送样、`PHOTO_SHOOT` 寄拍（可空） |
| 公司版本（平台管理员） | `DISTRIBUTION` 分销完整版（默认）、`KOL_LIBRARY` 达人库版 |

## 6. 两个版本各自开放的控制器

判定方式：控制器类级 `@EditionScope`；**未声明＝只属于 `DISTRIBUTION`**。架构测试 `ArchitectureTest` 固定了 `KOL_LIBRARY` 白名单，新增开放必须改测试。

| 版本 | 开放的 `/admin-api` 控制器 |
| --- | --- |
| `DISTRIBUTION` | 现有全部控制器 |
| `KOL_LIBRARY` | `AuthController`、`UserController`、`PermissionController`、`KolLibraryController`、`KolLibraryContentController` |

不分版本（不经此判定）：`/admin-api/platform/**`、`/admin-api/external/**`、登录与验证码、`/admin-api/public/site-config`、微店 OAuth 回调。

## 7. 新增错误码

| 错误码 | HTTP | 场景 |
| --- | --- | --- |
| `AUTH_EDITION_FORBIDDEN` | 403 | 当前公司版本未开放该接口 |
| `USER_PERMISSION_EDITION_MISMATCH` | 400 | 授权或查询的权限码不属于当前公司版本 |
| `KOL_LIBRARY_DUPLICATE` | 409 | 同一公司同一平台达人ID 已存在（提示「该达人已由 {跟进负责人} 录入」） |

## 8. 数据库变更

| 迁移 | 内容 |
| --- | --- |
| `V202611091000__sys_tenant_edition.sql` | `sys_tenant.edition`（`VARCHAR(16)`，NOT NULL，默认回填 `DISTRIBUTION` 后删除默认值，CHECK 限定两个版本，带注释）。已有行全部回填 `DISTRIBUTION`，A 公司不受影响 |
| `V202611091001__create_library_kol_tables.sql` | `crm_library_kol`、`crm_library_kol_content`（均带 `tenant_id` 与逻辑删除）；唯一索引 `uk_crm_library_kol_username (tenant_id, platform, kol_username) WHERE is_deleted = FALSE`；索引 `idx_crm_library_kol_update`、`idx_crm_library_kol_content_kol`。计算字段不入库 |

## 9. 清理与复查

浏览器验收创建的数据在第 3、4 轮结束时由子代理清理，主代理另行复查。清理对象：公司 `t143-b`（及其成员关系、角色）、账号 `t143_owner` / `t143_bd01` / 其他 `t143_` 账号、权限行、达人 `t143_beauty` / `t143_other` 及内容记录、相关操作日志。

复查（共享库）：

```sql
SELECT count(*) FROM sys_tenant      WHERE tenant_code LIKE 't143%';   -- 0
SELECT count(*) FROM sys_user        WHERE username    LIKE 't143%';   -- 0
SELECT count(*) FROM crm_library_kol         WHERE kol_username LIKE 't143%'; -- 0
SELECT count(*) FROM crm_library_kol_content;                          -- 0（本任务数据已删）
```

本任务没有新建容器、镜像或网络：后端与管理端用本机进程（18143 / 8843）连共享中间件，集成测试的 Testcontainers 容器由测试自身退出时回收。未停止、未重建负责人的 `tcrm-preview`，未动 `com.tcrm.aliyun-tunnel`，未执行 `down -v`。

复查结果（第 5 轮结束后实测）：`sys_tenant` / `sys_user` 中 `t143%` 计数为 0，`crm_library_kol` 与 `crm_library_kol_content` 为 0 行。各轮清了公司 1、账号 1～5、成员关系与权限若干、达人 1～4、内容记录 2～3 条、相关操作日志；公司总数回到 3（default / trev-b / t77-b02e732f）。

存在 1 处**非本任务**的孤儿数据：`sys_oper_log` 里 7 行属于 `tenant_id=2112885098872340481`，该租户在本次任务之前就已不存在，未删除，登记备查。

## 9.1 交付

分支 `task/143-kol-library`，[PR #254](https://github.com/ksws00684315/t-crm/pull/254)（非草稿，未合并、未推 main）。创建后查一次 `gh pr checks 254`：`Backend verify`、`Backend integration (1/4)~(4/4)`、`Admin UI verify` 均为 `pending`（刚入队），结果以 CI 为准。

## 10. 偏差与遗留事项

### 本任务范围内


1. 复制值保留 `@` 前缀（见 §2）。
2. 列表接口多返回 3 个档案字段（前端不渲染）。
3. 累计数用指标卡呈现，内容表不加合计行。
4. 明细页头多显示「寄样状态」「跟进负责人」。
5. `EditTenantRequest` 不含 `edition`，传入忽略（提示词允许二选一）；由 `EditionIT` 固定。
6. 集成测试需分批执行（资源受限），CI 本来就按分片跑 `-Dit.test`。

### 范围外发现（不在本任务处理，登记备查）

1. **A 公司店铺管理页表格对齐**：`/product/shops` 有 8 项 `center` 对齐问题（列「自动复制池」「接口状态」），是既有页面，本任务未改动该页。
2. **A 公司长会话页过渡卡死**：长时间在 SPA 内连续切页后，旧页节点停留在 `tcrm-page-leave-active`（`opacity:0`），随后抽屉/资料弹窗打不开，整页刷新即恢复。过渡结构本任务未改动；建议另开任务排查。
3. **负责人预览 `tcrm-preview`（8890）是旧构建**：不含达人库版本能力，B 公司账号在预览上按分销完整版渲染。A 公司场景两边一致；B 场景需负责人决定何时重建预览。
4. **平台管理员不能直接建公司子账号**（`/admin-api/system/users` 只允许公司超管），这是既有设计（设计说明书 §6.2 同口径），验收时改为公司超管操作。
5. 偶发「加载失败，请重试」与共享库中 `trev_ops` 账号被锁定，均为既有环境现象。
