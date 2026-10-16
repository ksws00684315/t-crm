# 订单同步（任务 13）

使用 JDK 21。新增依赖：无。订单模块只通过其他模块的 API 读取映射、商务历史、店铺与授权状态。

## 启动配置

后端进程需要注入现有环境变量 `TCRM_IP_HASH_SALT`，与跳转服务使用同一个安全配置来源。不能为空；不在代码或本文件给出默认值。买家标识仅保存 `SHA-256(盐值 + 买家 ID)`，没有买家 ID 时不生成替代个人标识。

注意：当前 `deploy/docker-compose.prod.yml` 只向跳转服务传递该变量；上线本功能前，部署负责人需将同一变量传给后端容器。任务 13 明确禁止修改 `deploy/`，本次没有改动模板；可在部署时通过受控的 Compose override 配置后端环境变量，随后纳入部署任务。

## 运行行为

- 每 5 分钟按启用公司同步已授权且启用的店铺；每店 `order:sync:{shopId}` watchdog 锁，不等待。
- 首次同步回溯 24 小时，后续回溯各自成功游标之前 10 分钟；订单、退款整批成功才一起前移。
- 每 10 分钟按新增商务历史修正订单快照，检查点 `order:bd-snapshot:checkpoint`；失败不前移。
- 手动同步：`POST /admin-api/order/syncs`，权限 `order:manage`，`{shopId}`；取得锁后立即返回 202，后台执行；锁冲突 409。
- 查询：`GET /admin-api/order/orders`（`order:view`）、`GET /admin-api/order/unmatched`（`order:manage`）；筛选字段为 `shopId`、`payFrom`（含）、`payTo`（不含）、`kolCode`、`orderStatus`、`attributionStatus`、`page`、`size`。
- 人工匹配：`PUT /admin-api/order/orders/{id}/attribution`，`{kolId,reason}` 或 `{nonKol:true,reason}`；`POST .../{id}/reattribution` 显式重新自动归因。
- 所有请求均使用管理端已有令牌体系。OWN 按订单支付时的商务快照过滤；响应不含买家信息。

本任务不计算佣金，不写结算时间，不新增导入或前端页面。完整规则、状态映射、测试结果及 M0 操作步骤见 [执行报告](../docs/04-开发任务/13-订单同步与归因-后端-执行报告.md)。

五个端点的静态 OpenAPI 3 描述（权限、参数、示例、错误码）见 [openapi-order.yaml](openapi-order.yaml)。仓库 springdoc 接入仍按工程差距 I6 的既有例外暂缓；本任务没有新增运行时文档页面或依赖。
