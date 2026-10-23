# 提现详情读取接口（2026-10-24）

本次只提供持久化详情读取基础，不是完整提现后端。已核查 origin/main 70331e8：没有真实提现申请写入模块。未新增申请、审核、付款或钱包写入接口，未创建第二套余额，未自动迁移演示 JSON 数据。当前预览演示服务与这套真实只读接口分离。必须由后续真实申请事务保存快照后，接口才有业务数据。

## 契约

- `GET /admin-api/commission/withdrawals/{id}`：统一 `R<WithdrawalDetailDTO>` 信封。ID 字符串；金额字符串；`type=COMMISSION`，`currency=CNY`。`account={type,account,holder,currency,network}` 为申请时快照。`operationsNote`、`financeNote` 分别来自明确运营、财务历史来源；未知保持 null，不从其他备注猜测。`history` 缺失返回空列表，不伪造历史。`allocations=[]`，订单数据通过分页端点读取。
- `GET /admin-api/commission/withdrawals/{id}/orders?page=1&size=10`：统一信封中的 `data={items,total,page,size}`；total 为字符串。page 为 1–10000；size 为 1–100（默认 10）。items 每笔为 `{id,orderNo,products:[{id,image,product,sku}],occupied,remainingSnapshot}`。分页单位是不同订单；同单多个商品属于同一条。按订单快照 ID 升序稳定排序。
- `remainingSnapshot` 是**本次申请占用后，该订单当时剩余的可提现人民币金额**。只有明确的订单级持久化历史值才能返回。缺失返回 null，前端展示待核实；禁止读取当前钱包、禁止把商品行剩余快照求和代替订单快照。
- 错误：参数非法 `COMMON_VALIDATION_FAILED`（400）；无权限 `AUTH_FORBIDDEN`（403）；记录不存在或越过当前商务数据范围 `COMMON_NOT_FOUND`（404）。

## 数据与安全

增量迁移 `V202610241230__withdrawal_detail_read_model.sql` 新增申请详情投影表及只追加订单占用快照表，字段均有注释，tenant_id 不可空；同租户、同申请、同订单唯一。订单分页使用 `(tenant_id,withdrawal_id,id)` 索引；申请按主键及租户读取。未修改已合并迁移。

详情文档保存姓名、编号、商务、申请金额、申请时间及显式阶段备注等历史展示值；收款快照单独使用现有 `SecretCipher` 加密，解密前完成授权。后续权威写入方必须确保 JSON 文档符合 DTO（金额及 ID 为字符串），并在同一真实申请事务中写入订单级剩余值；不得改写历史订单快照。生命周期状态投影的更新需来自权威业务事件，不是允许编辑历史。

所需权限：`commission:view` 或 `withdrawal:review`。预付权限不授予提现访问。平台管理员拒绝；租户只来自登录态；显式租户 SQL 与现有租户插件共同过滤；OWN 商务按达人当前商务归属校验，而展示的商务姓名/ID保留申请历史快照。详情读取有审计，日志不输出收款信息。完整收款快照仅公司 SUPER_ADMIN 返回，其他人员账号及持有人遮罩。财务备注仅 SUPER_ADMIN 或明确 FINANCE 岗位可见；普通商务/运营返回 null，PENDING_PAYMENT、PAID 及 FINANCE_* 历史备注（保留 PENDING_FINANCE_REVIEW 的运营备注）与 payment.financeNote 同步隐藏，预付权限不扩大该范围。detail_json 数据库约束禁止 account 键，收款信息只存加密专栏。

## 验收对应

| 要求 | 后端处理 |
| --- | --- |
| 订单编号复制 | 返回原始 orderNo，前端 CopyText 处理 |
| 长商品名、图及 SKU | products 明确分字段，不拼接截断内容 |
| 十单分页、同单多商品 | 唯一订单快照行及 products 数组 |
| 占用与剩余快照 | 独立金额字段，null 与 0.00 区分 |
| 运营及财务备注 | 独立 operationsNote / financeNote |
| 历史不可变 | 无快照修改接口、无钱包反推、来源要求明确 |

测试包括服务权限/归属/历史来源、MVC 参数及信封、隔离 PostgreSQL 上全量 Flyway 与实际 Mapper 分页及约束。实际运行结果由本次执行报告记录。



## 本次实际验证

- 本工作树缓存 JDK 21 / Maven 3.9.11 下编译通过。
- `mvn -f backend/pom.xml spotless:apply test -Dtest=WithdrawalDetailServiceTest checkstyle:check -q`：最终版本服务 5 项通过（包含 generic reviewNote 旁路阻断、真实 PENDING_FINANCE_REVIEW/PENDING_PAYMENT 事件区分）。格式及 Checkstyle 通过。
- `mvn -f backend/pom.xml spotless:apply test -Dtest=WithdrawalDetailServiceTest,WithdrawalDetailControllerTest,ArchitectureTest checkstyle:check -q`：服务 5、MVC 3、架构 9 项通过；此后 generic reviewNote 的单点修复已按上一条重跑服务验证。
- `mvn -f backend/pom.xml test -Dtest=WithdrawalDetailIT -Dsurefire.excludedGroups=weidian-live -q` 及后续整组 IT：真实 PostgreSQL IT 1 项通过；76 个 Flyway 迁移成功，验证订单分页、租户隔离、约束和历史 null。
- 合计覆盖 18 项不同测试（5 服务 + 3 MVC + 9 架构 + 1 PostgreSQL 集成）；未将本次针对性验证称为后端全量 verify。只创建临时测试容器，未修改共享预览数据库或停止共享服务。
