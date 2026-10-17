# 原生 SQL 清单（修订新增）

基线 `6f17740`。含 `@Select`/`@Update`/`@Insert`/`@Delete` 与有意义的 `.apply` / `.last` / `.setSql`。

`ALWAYS_IGNORED`：`sys_tenant`、`crm_link_index_outbox`、`crm_fx_rate`、`sys_user`（`CompanyTenantLineHandler.java:19-20`）。

说明：MyBatis-Plus `TenantLineInnerInterceptor` 在**非系统作用域**会改写注解 SQL/Wrapper，为未忽略表追加 `tenant_id`。系统作用域下不追加。

## 注解 SQL

| 位置 | 类型 | 方法 | 涉及表 | 始终忽略? | 显式 tenant_id | 结论 |
|---|---|---|---|---|---|---|
| `backend/src/main/java/com/tcrm/admin/integration/mapper/FxRateMapper.java:16` | Insert | `insertIgnore` | crm_fx_rate | 是 | 否 | 合理（仅始终忽略表或目录） |
| `backend/src/main/java/com/tcrm/admin/integration/mapper/FxRateMapper.java:27` | Select | `selectLatest` | crm_fx_rate | 是 | 否 | 合理（仅始终忽略表或目录） |
| `backend/src/main/java/com/tcrm/admin/kol/mapper/KolAuthTokenMapper.java:16` | Select | `findLiveByHash` | crm_kol_auth_token | 否 | 是 | 合理（SQL 显式含 tenant_id） |
| `backend/src/main/java/com/tcrm/admin/kol/mapper/KolAuthTokenMapper.java:25` | Update | `markUsed` | crm_kol_auth_token | 否 | 否 | 依赖租户线追加（公司作用域）；系统作用域调用须自带条件——见调用方 |
| `backend/src/main/java/com/tcrm/admin/kol/mapper/KolAuthTokenMapper.java:32` | Update | `invalidateUnused` | crm_kol_auth_token | 否 | 否 | 依赖租户线追加（公司作用域）；系统作用域调用须自带条件——见调用方 |
| `backend/src/main/java/com/tcrm/admin/kol/mapper/KolBdHistoryMapper.java:13` | Update | `closeCurrent` | crm_kol_bd_history | 否 | 否 | 依赖租户线追加（公司作用域）；系统作用域调用须自带条件——见调用方 |
| `backend/src/main/java/com/tcrm/admin/kol/mapper/KolMapper.java:12` | Select | `nextCodeNumber` | — | 否 | 否 | 合理（序列/锁/目录，无业务租户行） |
| `backend/src/main/java/com/tcrm/admin/kol/mapper/KolMapper.java:16` | Select | `existsCode` | crm_kol | 否 | 否 | 依赖租户线追加（公司作用域）；系统作用域调用须自带条件——见调用方 |
| `backend/src/main/java/com/tcrm/admin/kol/mapper/KolMapper.java:20` | Select | `lockById` | crm_kol | 否 | 否 | 依赖租户线追加（公司作用域）；系统作用域调用须自带条件——见调用方 |
| `backend/src/main/java/com/tcrm/admin/kol/mapper/KolRegisterCodeMapper.java:18` | Select | `lockByBd` | crm_kol_register_code | 否 | 否 | 依赖租户线追加（公司作用域）；系统作用域调用须自带条件——见调用方 |
| `backend/src/main/java/com/tcrm/admin/kol/mapper/KolRegisterCodeMapper.java:22` | Update | `revokeActive` | crm_kol_register_code | 否 | 否 | 依赖租户线追加（公司作用域）；系统作用域调用须自带条件——见调用方 |
| `backend/src/main/java/com/tcrm/admin/link/mapper/ClickLogMapper.java:21` | Insert | `insertIgnore` | crm_click_log | 否 | 是 | 合理（SQL 显式含 tenant_id） |
| `backend/src/main/java/com/tcrm/admin/link/mapper/ClickLogMapper.java:42` | Select | `latestClickTimes` | crm_click_log | 否 | 否 | 依赖租户线追加（公司作用域）；系统作用域调用须自带条件——见调用方 |
| `backend/src/main/java/com/tcrm/admin/link/mapper/ClickLogMapper.java:65` | Select | `countSince` | crm_click_log | 否 | 否 | 依赖租户线追加（公司作用域）；系统作用域调用须自带条件——见调用方 |
| `backend/src/main/java/com/tcrm/admin/link/mapper/ClickLogMapper.java:89` | Select | `listPartitions` | — | 否 | 否 | 合理（序列/锁/目录，无业务租户行） |
| `backend/src/main/java/com/tcrm/admin/link/mapper/ClickLogMapper.java:102` | Update | `createPartition` | crm_click_log,if | 否 | 否 | 依赖租户线追加（公司作用域）；系统作用域调用须自带条件——见调用方；含 ${} 仅白名单分区名（ClickPartitions） |
| `backend/src/main/java/com/tcrm/admin/link/mapper/ClickLogMapper.java:113` | Update | `commentPartition` | — | 否 | 否 | 合理（仅始终忽略表或目录）；含 ${} 仅白名单分区名（ClickPartitions） |
| `backend/src/main/java/com/tcrm/admin/link/mapper/ClickLogMapper.java:118` | Update | `detachPartition` | crm_click_log | 否 | 否 | 依赖租户线追加（公司作用域）；系统作用域调用须自带条件——见调用方；含 ${} 仅白名单分区名（ClickPartitions） |
| `backend/src/main/java/com/tcrm/admin/link/mapper/ClickLogMapper.java:123` | Update | `dropPartition` | if | 否 | 否 | 依赖租户线追加（公司作用域）；系统作用域调用须自带条件——见调用方；含 ${} 仅白名单分区名（ClickPartitions） |
| `backend/src/main/java/com/tcrm/admin/link/mapper/LinkIndexOutboxMapper.java:19` | Select | `listDue` | crm_link_index_outbox | 是 | 否 | 合理（仅始终忽略表或目录） |
| `backend/src/main/java/com/tcrm/admin/link/mapper/LinkIndexOutboxMapper.java:30` | Select | `oldestCreateTime` | crm_link_index_outbox | 是 | 否 | 合理（仅始终忽略表或目录） |
| `backend/src/main/java/com/tcrm/admin/link/mapper/LinkIndexOutboxMapper.java:37` | Select | `listAttemptsAtLeast` | crm_link_index_outbox | 是 | 否 | 合理（仅始终忽略表或目录） |
| `backend/src/main/java/com/tcrm/admin/link/mapper/LinkIndexOutboxMapper.java:47` | Delete | `deleteIds` | crm_link_index_outbox | 是 | 否 | 合理（仅始终忽略表或目录） |
| `backend/src/main/java/com/tcrm/admin/product/mapper/KolItemMapper.java:17` | Select | `findCurrentByShopItem` | crm_kol_item | 否 | 否 | 依赖租户线追加（公司作用域）；系统作用域调用须自带条件——见调用方 |
| `backend/src/main/java/com/tcrm/admin/product/mapper/KolItemMapper.java:26` | Select | `findCurrentByKolProduct` | crm_kol_item | 否 | 否 | 依赖租户线追加（公司作用域）；系统作用域调用须自带条件——见调用方 |
| `backend/src/main/java/com/tcrm/admin/product/mapper/KolItemMapper.java:34` | Select | `existsCurrentShopItem` | crm_kol_item | 否 | 否 | 依赖租户线追加（公司作用域）；系统作用域调用须自带条件——见调用方 |
| `backend/src/main/java/com/tcrm/admin/product/mapper/KolItemMapper.java:59` | Select | `listCurrentLinksByKol` | — | 否 | 否 | 合理（仅始终忽略表或目录） |
| `backend/src/main/java/com/tcrm/admin/product/mapper/KolItemMapper.java:62` | Select | `listCurrentLinksByKolAndShop` | — | 否 | 否 | 合理（仅始终忽略表或目录） |
| `backend/src/main/java/com/tcrm/admin/product/mapper/KolItemMapper.java:68` | Select | `listCurrentLinksByProduct` | — | 否 | 否 | 合理（仅始终忽略表或目录） |
| `backend/src/main/java/com/tcrm/admin/product/mapper/KolItemMapper.java:75` | Select | `pageKolIdsWithCurrentProduct` | crm_kol_item | 否 | 否 | 依赖租户线追加（公司作用域）；系统作用域调用须自带条件——见调用方 |
| `backend/src/main/java/com/tcrm/admin/product/mapper/KolItemMapper.java:93` | Select | `pageKolIdsWithCurrentShop` | crm_kol_item | 否 | 否 | 依赖租户线追加（公司作用域）；系统作用域调用须自带条件——见调用方 |
| `backend/src/main/java/com/tcrm/admin/product/mapper/KolItemMapper.java:107` | Select | `getCurrentLink` | — | 否 | 否 | 合理（仅始终忽略表或目录） |
| `backend/src/main/java/com/tcrm/admin/product/mapper/KolItemMapper.java:113` | Select | `pageCurrentLinksByKol` | — | 否 | 否 | 合理（仅始终忽略表或目录） |
| `backend/src/main/java/com/tcrm/admin/product/mapper/KolItemMapper.java:130` | Select | `listItemIdsByKolProduct` | crm_kol_item | 否 | 否 | 依赖租户线追加（公司作用域）；系统作用域调用须自带条件——见调用方 |
| `backend/src/main/java/com/tcrm/admin/product/mapper/KolItemMapper.java:143` | Select | `listItemIdsByKol` | crm_kol_item | 否 | 否 | 依赖租户线追加（公司作用域）；系统作用域调用须自带条件——见调用方 |
| `backend/src/main/java/com/tcrm/admin/product/mapper/KolItemMapper.java:152` | Select | `filterCurrentItemIds` | crm_kol_item | 否 | 否 | 依赖租户线追加（公司作用域）；系统作用域调用须自带条件——见调用方 |
| `backend/src/main/java/com/tcrm/admin/product/mapper/ProductMapper.java:11` | Select | `nextCodeNumber` | — | 否 | 否 | 合理（序列/锁/目录，无业务租户行） |
| `backend/src/main/java/com/tcrm/admin/product/mapper/ProductMapper.java:15` | Select | `existsCode` | crm_product | 否 | 否 | 依赖租户线追加（公司作用域）；系统作用域调用须自带条件——见调用方 |
| `backend/src/main/java/com/tcrm/admin/product/mapper/ProductMapper.java:22` | Select | `existsAliveShopItem` | crm_product | 否 | 否 | 依赖租户线追加（公司作用域）；系统作用域调用须自带条件——见调用方 |
| `backend/src/main/java/com/tcrm/admin/product/mapper/ProductMapper.java:36` | Select | `lockShopItem` | pg_advisory_xact_lock | 否 | 否 | 合理（仅始终忽略表或目录） |
| `backend/src/main/java/com/tcrm/admin/product/mapper/SheetSourceItemMapper.java:18` | Insert | `upsert` | crm_sheet_source_item | 否 | 是 | 合理（SQL 显式含 tenant_id） |
| `backend/src/main/java/com/tcrm/admin/product/mapper/SheetSourceItemMapper.java:39` | Select | `pageMissing` | crm_product,crm_sheet_source_item | 否 | 否 | 依赖租户线追加（公司作用域）；系统作用域调用须自带条件——见调用方 |
| `backend/src/main/java/com/tcrm/admin/product/mapper/SheetSourceMapper.java:12` | Select | `lockById` | crm_sheet_source | 否 | 否 | 依赖租户线追加（公司作用域）；系统作用域调用须自带条件——见调用方 |
| `backend/src/main/java/com/tcrm/admin/product/mapper/ShopMapper.java:11` | Select | `countAlive` | crm_shop | 否 | 否 | 依赖租户线追加（公司作用域）；系统作用域调用须自带条件——见调用方 |
| `backend/src/main/java/com/tcrm/admin/product/mapper/ShopMapper.java:19` | Select | `lockForCreate` | pg_advisory_xact_lock | 否 | 否 | 合理（仅始终忽略表或目录） |
| `backend/src/main/java/com/tcrm/admin/product/mapper/ShopMapper.java:27` | Select | `countByWeidianId` | crm_shop | 否 | 否 | 依赖租户线追加（公司作用域）；系统作用域调用须自带条件——见调用方 |
| `backend/src/main/java/com/tcrm/admin/system/mapper/SysOperLogMapper.java:16` | Delete | `deleteOlderThan` | sys_oper_log | 否 | 否 | 依赖租户线追加（公司作用域）；系统作用域调用须自带条件——见调用方 |
| `backend/src/main/java/com/tcrm/admin/system/mapper/SysUserMapper.java:19` | Select | `countByUsername` | sys_user | 是 | 否 | 合理（sys_user 唯一性/计数；调用方须限制枚举——见清单说明） |
| `backend/src/main/java/com/tcrm/admin/system/mapper/SysUserTenantMapper.java:37` | Select | `pageMembers` | — | 否 | 是 | 合理（仅始终忽略表或目录） |
| `backend/src/main/java/com/tcrm/admin/system/mapper/SysUserTenantMapper.java:54` | Select | `getMember` | — | 否 | 是 | 合理（仅始终忽略表或目录） |
| `backend/src/main/java/com/tcrm/admin/system/mapper/SysUserTenantMapper.java:60` | Select | `listMembers` | — | 否 | 是 | 合理（仅始终忽略表或目录） |
| `backend/src/main/java/com/tcrm/admin/system/mapper/SysUserTenantMapper.java:71` | Select | `listByRole` | — | 否 | 是 | 合理（仅始终忽略表或目录） |
| `backend/src/main/java/com/tcrm/admin/system/mapper/SysUserTenantMapper.java:81` | Select | `listEnabledSuperAdminIds` | sys_user,sys_user_tenant | 是 | 是 | 合理（SQL 显式含 tenant_id） |
| `backend/src/main/java/com/tcrm/admin/system/mapper/SysUserTenantMapper.java:92` | Select | `listUsableBds` | sys_user_permission | 否 | 是 | 合理（SQL 显式含 tenant_id） |
| `backend/src/main/java/com/tcrm/admin/system/mapper/SysUserTenantMapper.java:110` | Select | `lockMember` | sys_user_tenant | 否 | 是 | 合理（SQL 显式含 tenant_id） |
| `backend/src/main/java/com/tcrm/admin/system/mapper/SysUserTenantMapper.java:120` | Select | `lockEnabledSuperAdmins` | sys_user_tenant | 否 | 是 | 合理（SQL 显式含 tenant_id） |
| `backend/src/main/java/com/tcrm/admin/system/mapper/SysUserTenantMapper.java:129` | Select | `listMemberships` | sys_tenant,sys_user_tenant | 是 | 是 | 合理（SQL 显式含 tenant_id） |
| `backend/src/main/java/com/tcrm/admin/system/mapper/SysUserTenantMapper.java:143` | Select | `countCompanies` | sys_user_tenant | 否 | 否 | 依赖租户线追加（公司作用域）；系统作用域调用须自带条件——见调用方 |

## Wrapper 片段（`.apply` / `.last` / `.setSql`）

| 位置 | 片段 | 结论 |
|---|---|---|
| `backend/src/main/java/com/tcrm/admin/integration/service/GoogleCredentialService.java:112` | `return credentials.selectOne(new LambdaQueryWrapper<GoogleCredentialDO>().last("LIMIT 1"));` | MP Lambda；公司作用域由租户线追加。 |
| `backend/src/main/java/com/tcrm/admin/kol/service/BdTransferService.java:338` | `.last("LIMIT 200"));` | MP Lambda；公司作用域由租户线追加。 |
| `backend/src/main/java/com/tcrm/admin/kol/service/KolAccountStoreService.java:39` | `.selectList(new LambdaQueryWrapper<KolDO>().apply("lower(email) = {0}", email))` | MP Lambda；公司作用域由租户线追加。大小写不敏感匹配，参数占位 {0}。 |
| `backend/src/main/java/com/tcrm/admin/kol/service/KolAccountStoreService.java:78` | `.setSql("credential_version = credential_version + 1")` | MP Lambda；公司作用域由租户线追加。含 setSql 字面量（非用户输入）。 |
| `backend/src/main/java/com/tcrm/admin/kol/service/KolAccountStoreService.java:92` | `.setSql("credential_version = credential_version + 1")` | MP Lambda；公司作用域由租户线追加。含 setSql 字面量（非用户输入）。 |
| `backend/src/main/java/com/tcrm/admin/kol/service/KolImportProcessor.java:177` | `} else if (kols.selectCount(new LambdaQueryWrapper<KolDO>().apply("lower(email) = {0}", email))` | MP Lambda；公司作用域由租户线追加。大小写不敏感匹配，参数占位 {0}。 |
| `backend/src/main/java/com/tcrm/admin/kol/service/KolLookupService.java:41` | `.last("LIMIT 1"));` | MP Lambda；公司作用域由租户线追加。 |
| `backend/src/main/java/com/tcrm/admin/kol/service/KolLookupService.java:78` | `.last("LIMIT " + limit))` | MP Lambda；公司作用域由租户线追加。 |
| `backend/src/main/java/com/tcrm/admin/kol/service/KolOwnershipLookup.java:33` | `.last("LIMIT 1"));` | MP Lambda；公司作用域由租户线追加。 |
| `backend/src/main/java/com/tcrm/admin/kol/service/KolSelfRegisterService.java:142` | `kols.selectCount(new LambdaQueryWrapper<KolDO>().apply("lower(email) = {0}", email))` | MP Lambda；公司作用域由租户线追加。大小写不敏感匹配，参数占位 {0}。 |
| `backend/src/main/java/com/tcrm/admin/kol/service/KolService.java:83` | `.apply(` | MP Lambda；公司作用域由租户线追加。 |
| `backend/src/main/java/com/tcrm/admin/kol/service/KolService.java:311` | `.apply("lower(email) = {0}", email)` | MP Lambda；公司作用域由租户线追加。大小写不敏感匹配，参数占位 {0}。 |
| `backend/src/main/java/com/tcrm/admin/kol/service/LinkTemplateService.java:149` | `.last("LIMIT 1"));` | MP Lambda；公司作用域由租户线追加。 |
| `backend/src/main/java/com/tcrm/admin/order/service/OrderService.java:238` | `new LambdaQueryWrapper<OrderDO>().eq(OrderDO::getId, id).last("FOR UPDATE"));` | MP Lambda；公司作用域由租户线追加。 |
| `backend/src/main/java/com/tcrm/admin/order/service/OrderWriter.java:59` | `.last("FOR UPDATE"));` | MP Lambda；公司作用域由租户线追加。 |
| `backend/src/main/java/com/tcrm/admin/order/service/OrderWriter.java:130` | `.last("FOR UPDATE"));` | MP Lambda；公司作用域由租户线追加。 |
| `backend/src/main/java/com/tcrm/admin/order/service/OrderWriter.java:202` | `.last("FOR UPDATE"));` | MP Lambda；公司作用域由租户线追加。 |
| `backend/src/main/java/com/tcrm/admin/product/service/KolItemService.java:74` | `.last("LIMIT 1"));` | MP Lambda；公司作用域由租户线追加。 |
| `backend/src/main/java/com/tcrm/admin/product/service/OrderMappingLookup.java:29` | `.last("LIMIT 2"));` | MP Lambda；公司作用域由租户线追加。 |
| `backend/src/main/java/com/tcrm/admin/product/service/ProductImportProcessor.java:159` | `.last("LIMIT 1"));` | MP Lambda；公司作用域由租户线追加。 |
| `backend/src/main/java/com/tcrm/admin/product/service/ProductService.java:64` | `.apply(` | MP Lambda；公司作用域由租户线追加。 |
| `backend/src/main/java/com/tcrm/admin/product/service/ProductService.java:189` | `.last("LIMIT 1"));` | MP Lambda；公司作用域由租户线追加。 |
| `backend/src/main/java/com/tcrm/admin/product/service/ShopService.java:129` | `.last("LIMIT 1"));` | MP Lambda；公司作用域由租户线追加。 |
| `backend/src/main/java/com/tcrm/admin/system/service/AccountStoreService.java:57` | `.apply("lower(username) = lower({0})", username));` | MP Lambda；公司作用域由租户线追加。大小写不敏感匹配，参数占位 {0}。 |
| `backend/src/main/java/com/tcrm/admin/system/service/AccountStoreService.java:135` | `.setSql("credential_version = credential_version + 1")` | MP Lambda；公司作用域由租户线追加。含 setSql 字面量（非用户输入）。 |
| `backend/src/main/java/com/tcrm/admin/system/service/BdUserService.java:74` | `.apply("lower(username) = lower({0})", username.strip()));` | MP Lambda；公司作用域由租户线追加。大小写不敏感匹配，参数占位 {0}。 |
| `backend/src/main/java/com/tcrm/admin/system/service/PlatformService.java:174` | `.apply("lower(username) = lower({0})", username.strip()));` | MP Lambda；公司作用域由租户线追加。大小写不敏感匹配，参数占位 {0}。 |
| `backend/src/main/java/com/tcrm/admin/system/service/PlatformService.java:286` | `.setSql("credential_version = credential_version + 1")` | MP Lambda；公司作用域由租户线追加。含 setSql 字面量（非用户输入）。 |
| `backend/src/main/java/com/tcrm/admin/system/service/UserService.java:220` | `.setSql("credential_version = credential_version + 1"));` | MP Lambda；公司作用域由租户线追加。含 setSql 字面量（非用户输入）。 |

## sys_user 查询路径（始终忽略）

| 位置 | 用途 | 可否被调用者跨公司枚举账号 |
|---|---|---|
| `SysUserMapper.java:19` `countByUsername` | 注册查重 | 否（只返回计数；创建接口需 SUPER_ADMIN/PLATFORM） |
| `AccountStoreService` Lambda `lower(username)=…` | 登录加载 | 否（精确用户名） |
| `PlatformService.findUser` | 平台精确查找 | 否（禁止部分匹配；PLATFORM_ADMIN 账号当缺失，`:164-176`） |
| `SysUserTenantMapper` JOIN `sys_user` | 成员分页/详情 | 否（`m.tenant_id=#{tenantId}` 显式） |
| `SysUserTenantMapper.countCompanies` | 多公司计数 | 否（仅入参 userIds；来自本租户列表） |

**结论**：未发现可被调用者用来**枚举**全部 `sys_user` 的管理端接口（静态阅读）。

## 依赖租户线、未写显式 tenant_id 的业务表 SQL（摘要）

`KolItemMapper`/`ProductMapper`/`ShopMapper.countAlive`/`SheetSourceMapper`/`KolMapper` 等：公司请求路径下由租户线追加。`ShopMapper.countByWeidianId`、`KolMapper.existsCode`、`ProductMapper.existsCode` 在 **callSystem** 中做全局唯一性检查（见跨公司清单），不返回他公司行内容。
