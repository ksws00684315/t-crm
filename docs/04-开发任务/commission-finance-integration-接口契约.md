# 管理端佣金页面接口对接约定

日期：2026-10-24。根据 PR112 最新负责人评论，佣金/提现后端由架构任务 62–64 实现，任务 61 负责先合并 ADR-032、设计规格和前后端字段对照表。#112 仅维护管理端前端。在任务 61 合并前，任何已有分支、设计稿或示例路径都不是可依赖的最终 API 契约；本文列出的路径和字段只是待契约核对的适配清单，不代表已确认接口。

## 管理端路径

| 页面              | 方法与路径                                                         | 权限及数据范围                                            |
| ----------------- | ------------------------------------------------------------------ | --------------------------------------------------------- |
| 达人收入列表/汇总 | `GET /admin-api/commission/incomes`                                | `commission:view`，商务按 OWN 范围                        |
| 达人收入详情      | `GET /admin-api/commission/incomes/{kolId}`                        | 同上                                                      |
| 提现列表          | `GET /admin-api/commission/withdrawals`                            | `commission:view` 或 `withdrawal:review`，商务按 OWN 范围 |
| 提现详情          | `GET /admin-api/commission/withdrawals/{id}`                       | 同提现列表                                                |
| 提现关联订单分页  | `GET /admin-api/commission/withdrawals/{id}/orders?page=1&size=10` | 同提现列表；分页单位是不同订单                            |
| 提现审核          | `PUT /admin-api/commission/withdrawals/{id}/review`                | `withdrawal:review`；服务端校验状态及数据范围             |
| 线下付款登记      | `PUT /admin-api/commission/withdrawals/{id}/payment`               | 公司 SUPER_ADMIN；请求幂等                                |
| 提现导出          | `GET /admin-api/commission/withdrawals/export`                     | `commission:view` 与 `data:export`                        |
| 结算配置          | `GET/PUT /admin-api/commission/settlement-configs/current`         | 查询按 #116 权限；保存公司 SUPER_ADMIN                    |
| 订单结算明细      | `GET /admin-api/commission/settlement-details`                     | `commission:view`，商务按 OWN 范围                        |

管理端 HTTP 客户端自动加 `/admin-api` 前缀。任务 61 合并前不按表中路径接入真实 API；正式接入时由架构团队的字段对照表和实际 DTO 更新 `src/api/commission/finance.ts`。订单详情需要支持按不同订单分页，同单多商品聚合为一笔订单；最终是否使用独立分页端点以任务 61 契约为准。

## DTO 约定

- ID、金额以字符串传递；金额按 CNY 两位小数；分页 total 允许服务端数值或字符串，前端仅转成数字供分页组件使用。
- 提现订单页使用 `{items,total,page,size}`；每笔包含 `id/orderNo/products/occupied/remainingSnapshot`。同一订单多条商品行在服务端聚合；`remainingSnapshot` 是申请占用后该订单当时剩余的可提现金额，null 显示“待核实”，禁止用当前余额补算。
- 提现详情来自唯一权威 `crm_commission_withdrawal` 与 `crm_commission_withdrawal_allocation` 数据，不建立第二套详情投影表。收款账户必须是申请时快照并依权限脱敏。
- 达人商务、申请时估算/可提现摘要、分阶段备注和审核状态由任务 61 契约明确。前端不得从其他历史备注猜测，也不得将缺失值填成零。
- 达人名/编号筛选之外的商务名筛选由任务 61 契约确认；前端仅发送服务端明确支持的筛选字段。
- 审核状态以任务 61 确认并由任务 62/63 实现的领域服务为准。负责人已确认的产品流程是“达人申请—运营审核—财务审核—财务支付”；若 DTO 状态机不能表达此流程，佣金 API 开关保持关闭。

## 正式环境开关

`VITE_COMMISSION_API_ENABLED` 未设置或不为 `true` 时，佣金页面渲染“开发中”提示且不发佣金 HTTP 请求。任务 61 契约合并并且任务 62/63 后端能力部署后，完成前端适配和联调，才允许在部署配置中设为 `true`。本地预览另需 `MODE=commission-preview`、`VITE_COMMISSION_DEMO=true` 和 localhost/127.0.0.1/::1 三项门控；预览引擎应位于 `admin-ui/preview/`，不得进入 `src/` 或生产构建产物。
