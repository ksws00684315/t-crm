---
name: flyway-migration
description: 新增或修改数据库表结构、字段、索引、基础数据时使用（Flyway 迁移脚本、建表、加字段、加索引、tenant_id）。Use when a task adds a Flyway migration in backend/src/main/resources/db/migration.
---

# 新增 Flyway 迁移

依据：规范 04 第 1～4 节、规范 08 第 9.2 节、`本机任务通用要求.md` 第 2 节。本文只列步骤，动手写建表或索引前读规范 04 第 1～3 节原文（约 50 行）。

## 步骤

1. **版本号**：只用任务提示词分配的区间（例如 `V202610281000`～`V202610281019`）。文件放在 `backend/src/main/resources/db/migration/`，命名 `V{yyyyMMddHHmm}__{snake_case_描述}.sql`。
2. **先看现有写法**：`ls backend/src/main/resources/db/migration | tail -5`，打开最近一个建表脚本照着写。
3. **建表必查**（规范 04 第 1～3 节）：
   - 表名前缀 `sys_` / `crm_` / `agg_`；必备字段 `id`、`create_by`、`create_time`、`update_by`、`update_time`、`is_deleted`；
   - 业务表有 `tenant_id BIGINT NOT NULL`，唯一索引默认包含 `tenant_id`，按公司列表、排序的索引以 `tenant_id` 开头；
   - 表和每个字段都有 `COMMENT ON`，枚举字段在注释里列全部取值；
   - 金额 `NUMERIC(14,2)`，比例 `NUMERIC(6,4)`，时间 `TIMESTAMPTZ(3)`，枚举 `VARCHAR(n)` + `CHECK`；
   - 不建外键；约束、索引按 `pk_` / `uk_` / `idx_` / `ck_` 命名。
4. **一个脚本只做一件事**；结构变更与数据修正分开写；不写测试数据、密码、令牌。
5. **大表加字段、加索引**须在 PR 中说明锁表影响；在线建索引用 `CREATE INDEX CONCURRENTLY`，单独一个脚本且不在事务中执行（仓库里还没有先例，写之前停下来和架构负责人确认写法）。
6. **已合并到 `main` 的脚本不得修改**：`git log origin/main --oneline -- <脚本>` 有输出就新建脚本。
7. **共享库**：分支上的迁移会在联调时进入共享库。已在共享库执行过、合并前又修改的脚本，在 PR 描述里写明，由架构负责人修正校验和；不得自行改 `flyway_schema_history`。
8. **同步文档**：设计说明书 §5（表结构）同步更新；PR 模板「数据库变更说明」写清脚本、数据影响、索引依据。

## 验证

- `mvn -B verify -Pdocker-it`：Testcontainers 从空库跑完全部迁移。
- 新增查询条件的，在 PR 中附 `EXPLAIN` 或说明命中的索引（规范 04 第 3 节）。
