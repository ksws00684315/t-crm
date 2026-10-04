# ADR-005 数据库采用 PostgreSQL

- 状态：已接受（2026-10-05）
- 决策人：项目负责人
- 替代：设计说明书原定的 MySQL 8.4

## 背景

任务 02 接入 OSV-Scanner 后发现，MySQL 官方 JDBC 驱动 `mysql-connector-j` 的许可证为 GPLv2 附 Oracle Universal FOSS Exception。该例外只豁免与开源许可程序的组合，本项目交付给客户的是商业源代码，不适用；规范 07 禁止 GPL 许可证。驱动会被打包进后台应用一起交付。

## 决定

数据库由 MySQL 8.4 改为 **PostgreSQL 17**：服务端为 PostgreSQL License，JDBC 驱动 `org.postgresql:postgresql` 为 BSD-2-Clause，均在许可证白名单内。规范 04 按 PostgreSQL 重写。

## 备选方案及放弃原因

| 方案 | 放弃原因 |
|---|---|
| 继续使用 MySQL 驱动，登记许可证例外 | 交付场景下 GPL 义务的适用存在法律灰色地带，需要律师意见；与规范 07 的初衷冲突 |
| MySQL 服务端 + MariaDB 驱动（LGPL-2.1） | 官方不保证兼容 MySQL 8.4；Flyway 会按 MariaDB 识别数据库；LGPL 不在白名单 |

## 影响

- 时机：仅有 2 张系统表、未上线，迁移成本约 1 天（驱动、Flyway 模块、迁移脚本、Testcontainers、配置、部署编排）。
- 收益：带时区的时间类型（`TIMESTAMPTZ`）与项目"统一 UTC"规则一致；部分唯一索引适合"同一时间只有一条有效映射"的约束；声明式分区适合点击日志；`JSONB` 支持更好。
- 代价：《阿里巴巴 Java 开发手册》的数据库章节以 MySQL 为背景，规范 04 中已按 PostgreSQL 调整类型与语法；部署与备份工具改为 PostgreSQL 对应工具（`pg_dump`）。
- 签约报价未约定数据库产品，不涉及合同变更。
