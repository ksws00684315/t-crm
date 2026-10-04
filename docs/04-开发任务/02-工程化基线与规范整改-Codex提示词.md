# 任务 02：工程化基线与规范整改

> 本文件是交给编码代理（Codex）执行的提示词。直接把本文件全文作为任务输入即可。

---

## 0. 续做说明（2026-10-04 更新）

本任务第一次执行时，在第 7 步因 GitHub 依赖审查不支持私有仓库而按停止条件中止（见 PR #2 与执行报告）。该问题已由项目负责人决定解决：改用 OSV-Scanner（见 ADR-004，规范 06 第 6 节、08 第 4 节已更新）。

续做要求：

1. 先 `git pull origin main` 获取更新后的规范与本提示词，再切到已有分支 `task/02-engineering-baseline` 并合并最新 `main`（使用 merge，不要 rebase，不改写已推送历史）。
2. 在**原分支与原 PR #2** 上继续，不新建分支或 PR；删除不可用的 `dependency-review.yml`。
3. 执行报告在原文件上续写，新增"续做记录"一节，保留第一次执行的内容。
4. 第 1～8 步全部完成、CI 全部通过后，把 PR #2 从草稿改为可审查（`gh pr ready 2`），标题去掉 `[WIP]`。

## 1. 背景

项目已制定工程规范（`docs/05-工程规范/`）和 AI 代理规则（仓库根目录 `AGENTS.md`）。现有的 `backend/`、`redirect/` 是在规范制定前写的，与规范存在差距，差距逐项列在 `docs/05-工程规范/09-现状差距与整改清单.md`。

本任务做两件事：

1. **建立工程化基线**：把规范中能自动检查的条款落成构建插件、架构测试和 CI，让以后的每一次提交都被机器检查。
2. **整改现有代码**：按整改清单把 `backend/`、`redirect/` 改到符合规范。

**不新增任何业务功能。** 现有功能的行为（接口能力、登录规则、跳转规则）保持不变，只有整改清单中列出的接口路径、响应格式、表结构命名等按规范调整。

## 2. 开始前必读

1. `AGENTS.md`
2. `docs/05-工程规范/` 全部文件（01～09）
3. `docs/06-架构决策/` 全部 ADR
4. `docs/04-开发任务/01-项目骨架搭建-执行报告.md`（含第 9 节验证机结果）

## 3. 执行环境

- 执行机**不能运行 Docker**。带 `@Tag("docker")` 的集成测试在本机只需编译通过，由本任务新增的 **GitHub Actions CI** 实际运行（GitHub 托管机器自带 Docker）。
- 使用 JDK 21 构建。

## 4. 分支与交付

- 从最新的 `main` 创建分支 `task/02-engineering-baseline`，所有提交在该分支上进行。
- **禁止推送到 `main`**。完成后推送分支，并用 `gh pr create` 创建 PR 到 `main`，PR 描述按本任务新增的 PR 模板填写。
- 创建 PR 后用 `gh pr checks --watch` 等待 CI 结束。CI 失败时在同一分支修复并推送，直到全部通过；在合理排查后仍无法通过的，停止并在报告中说明。
- **不要合并 PR**，由项目负责人审查后合并。
- 提交遵循规范 08：Conventional Commits、一个提交只做一件事、提交后仍可构建；格式化提交与逻辑修改分开。每条提交末尾加：

  ```
  Co-Authored-By: Codex <noreply@openai.com>
  ```

## 5. 执行步骤

### 第 1 步：构建与检查插件（backend、redirect）

在两个工程的 `pom.xml` 中：

1. 先把 `pom.xml` 改为标准多行格式（规范 02 / C4），依赖按"框架 → 数据 → 工具 → 测试"分组。
2. 新增插件（直接依赖须为正式稳定版并写死版本，规范 07）：
   - **Spotless**（google-java-format）：`mvn spotless:check` 绑定到 `verify` 阶段；提供 `mvn spotless:apply`。
   - **Checkstyle**：使用仓库内的精简规则文件 `config/checkstyle/checkstyle.xml`（两个工程共用一份，放在仓库根目录 `config/` 下），至少包含：禁止通配符导入、禁止未使用导入、禁止 `System.out`/`System.err`、禁止 `printStackTrace`。违规即构建失败。不要启用与 google-java-format 重复或冲突的格式类规则。
   - **JaCoCo**：`verify` 阶段检查覆盖率门槛（规范 05 第 4 节）。后台整体行覆盖 ≥ 70%、分支 ≥ 60%；`security` 包行 ≥ 85%、分支 ≥ 80%；为 `commission`、`order`、`stats` 预先配置同样门槛（包不存在时规则不生效）；跳转服务行 ≥ 80%、分支 ≥ 70%。按规范排除启动类、配置类与纯数据类。`docker-it` profile 运行时同样生成报告。
3. 新增测试依赖 **ArchUnit**（JUnit 5 版本）。

**验证**：`mvn spotless:apply` 后，`mvn verify` 在两个工程都通过（此时可能因覆盖率或架构测试暂未完成而失败——若失败原因只是后续步骤要整改的内容，先在报告中记录，完成整改后再要求全部通过）。

### 第 2 步：后台结构整改（清单 A1～A5、C1～C3）

1. 包结构按规范 01：
   - `common` → `shared`；
   - `crm.*` 下的占位包上移为根包的直接子包（`kol`、`product`、`link`、`sheet`、`order`、`commission`、`stats`、`integration`、`task`），保留 `package-info.java`；
   - `system`、`security` 按 `controller/service/mapper/model` 拆分子包；对其他模块公开的内容（如当前用户上下文）放在 `security.api`。
2. 对象模型按规范 01 第 4 节：`SysUser` → `SysUserDO`，`OperLog` → `SysOperLogDO`，`UserInfo` → `UserVO`；请求对象移到 controller 包的独立文件（`CreateUserRequest` 等），service 入参改用 `*Command`；service 不再依赖 controller 包。
3. 字段改为 `private` + getter/setter（不使用 Lombok），展开通配符导入，`QueryWrapper` 改为 `LambdaQueryWrapper`。
4. 新增 `ArchitectureTest`（ArchUnit），至少检查：
   - controller 不依赖 mapper；service 不依赖 controller；
   - 模块之间只能依赖对方的 `api` 包（`shared` 除外）；`shared` 不依赖任何其他模块；模块间无循环依赖；
   - `*DO` 只出现在 `model`、`mapper`、`service` 包中，不出现在 controller 的方法签名里；
   - 每个 controller 类或方法都带有角色注解，或显式标注为公开（规范 06 第 2 节）；
   - 不存在 `public` 非静态非 final 字段。

### 第 3 步：接口整改（清单 I1～I6、C6、S1～S2）

1. 路径改为复数 kebab-case：`/admin-api/system/users`、`/admin-api/system/oper-logs`（规范 03）。
2. 响应信封扩展为 `{code, msg, data, errorCode, traceId}`；新增 `shared.ErrorCode` 枚举，登记现有全部错误场景（至少包含规范 03 第 4 节示例中的错误码），`BusinessException` 改为基于 `ErrorCode` 构造；全局异常处理统一输出；参数校验失败时 `data` 返回字段级错误列表。
3. 分页响应改为 `{items, total, page, size}`。
4. 新增请求过滤器：生成 `traceId`，写入 MDC 与响应头 `X-Trace-Id`，并填入响应信封。
5. 安全响应头（`X-Content-Type-Options`、`Referrer-Policy`）与跨域白名单（来源域名来自环境变量 `TCRM_CORS_ALLOWED_ORIGINS`，逗号分隔，默认不允许任何跨域）。
6. 新增 springdoc OpenAPI：**仅当存在明确支持 Spring Boot 4.1 的正式稳定版时**引入；`dev`、`test` 环境开放，`prod` 关闭；为现有接口补充说明、所需角色和可能的 `errorCode`。若无合格版本，跳过并在报告中说明。

### 第 4 步：数据库整改（清单 D1～D5）

项目尚未上线、没有生产数据，本次允许一次性改写初始迁移脚本（规范 09 D1 的例外，**仅此一次**）：

1. `V1__init_system.sql` 重命名为 `V202610041200__init_system.sql`。
2. 主键与人员字段改为 `BIGINT UNSIGNED`；`del_flag` 改为 `is_deleted`（Java 字段 `deleted`）；所有表与字段补 `COMMENT`，枚举字段注释列出全部取值；`sys_oper_log` 在表注释中说明其为只追加表。
3. 同步修改实体映射与相关代码。

### 第 5 步：测试整改（清单 T1～T5）

1. 测试类移动到与被测类相同的包路径。
2. 集成测试重命名为 `*IT`，保留 `@Tag("docker")`；后台原来"一个大用例覆盖多个场景"的集成测试，拆成每个方法验证一个行为。
3. 测试方法统一命名 `should…_when…`，断言改用 AssertJ。
4. 补齐覆盖率：达到第 1 步配置的门槛；`security` 模块按规范 05 必测清单补全（登录成功与失败、锁定、令牌失效、停用用户、越权）。
5. 第 9 节验证机结果中修复过的 3 个缺陷，确保各有对应的回归测试。

### 第 6 步：跳转服务整改（清单 A6、C1～C5）

1. 包结构改为 `controller/service/config`。
2. 同样接入 Spotless、Checkstyle、JaCoCo、`traceId`、统一的测试命名与 `*IT` 命名。
3. 新增该工程的 ArchUnit 分层测试（controller 不直接访问 Redis 操作类，必须经过 service）。
4. 跳转服务不使用统一信封（它返回 302 和纯文本健康检查），保持现有对外行为不变。

### 第 7 步：CI、PR 模板与依赖检查（清单 P2、P3、S4）

1. `.github/workflows/backend.yml`：在 `backend/**`、`config/**` 或工作流本身变更时运行；步骤为 JDK 21、Maven 缓存、`mvn -B verify`、`mvn -B verify -Pdocker-it`，上传 JaCoCo 报告为构建产物。
2. `.github/workflows/redirect.yml`：同上，作用于 `redirect/**`。
3. `.github/workflows/osv-scanner.yml`：在 PR、合并到 `main`、每周定时三种情况下运行 OSV-Scanner，扫描全仓库（递归，包含两个 `pom.xml`，以及以后的 `pnpm-lock.yaml`）：
   - 直接使用 `google/osv-scanner-action` 提供的 Action 运行扫描命令，**不要使用会上传 SARIF 到代码扫描的可复用工作流**，或者显式关闭 SARIF 上传（私有仓库没有 Advanced Security，上传会失败）；扫描结果以表格输出到日志，并作为构建产物上传；
   - 开启许可证检查，白名单为规范 07 第 3 节列出的许可证（SPDX 标识：`MIT`、`Apache-2.0`、`BSD-2-Clause`、`BSD-3-Clause`、`ISC`、`EPL-2.0`，以及实际扫描中出现、确属宽松许可证的同类标识——每新增一个标识须在报告中说明）；
   - 发现漏洞或许可证违规即失败；
   - 现有依赖若已有漏洞：优先升级到修复后的稳定版；无法升级的，在根目录 `osv-scanner.toml` 登记豁免并写明 `reason` 与 `ignoreUntil`（不超过 90 天），并在报告中逐项列出；
   - 先确认 OSV-Scanner 对 Maven 依赖能扫描到**传递依赖**（例如报告中已知的 `redis-authx-core`），在报告中给出证据；若只能扫描直接依赖，停止并报告。
4. `.github/dependabot.yml`：Maven（两个工程）与 GitHub Actions 每周检查一次。
5. `.github/pull_request_template.md`：包含规范 08 第 3 节要求的全部栏目，以及规范 08 第 5 节完成标准的勾选清单。
6. 工作流中引用的所有 Action 固定到完整 40 位提交 SHA，并在行尾注释标明版本号（规范 08 第 4 节）；SHA 须取自该 Action 官方仓库正式发布版本对应的提交。

**验证**：PR 上所有检查通过，包括 `docker-it` 集成测试。

### 第 8 步：文档与报告

1. 同步受影响的文档：根 `README.md`（如不存在则新建，内容见下）、`docs/03-设计/系统详细设计说明书.md` 中涉及的接口路径、包结构、表字段名（只改与本次整改直接相关的内容）。
2. 根 `README.md`（中文）：项目简介、目录说明、各工程版本、本地开发命令、规范与 AGENTS.md 的位置。前端与部署部分写"待任务 03 补充"。
3. 在 `docs/05-工程规范/09-现状差距与整改清单.md` 末尾追加"整改结果"一节：逐项标注"已整改 / 部分整改 / 未整改（原因）"。这是本任务唯一允许修改的规范文件。
4. 写执行报告 `docs/04-开发任务/02-工程化基线与规范整改-执行报告.md`：结论；版本与依据（新增插件、依赖的版本和支持依据）；每一步的改动与验证结果；整改清单完成情况；CI 运行结果与 PR 链接；预发布传递依赖清单；偏离与遗留事项。

## 6. 禁止事项

- 不新增业务功能、业务表、业务接口。
- 不修改 `docs/05-工程规范/` 中 09 以外的文件；对规范有异议时写进报告，由项目负责人决定。
- 不为通过检查而降低门槛、排除不该排除的类、`@Disabled` 测试或放宽 ArchUnit 规则。
- 不引入 Lombok 或任何后台脚手架。
- 不推送 `main`，不合并 PR，不 `push --force`，不改写已推送历史。

## 7. 停止条件

- 某个需要新增的直接依赖或插件没有支持 Spring Boot 4.1 / JDK 21 的正式稳定版（springdoc 除外，见第 3 步）；
- 整改要求与规范、设计说明书之间存在冲突；
- CI 在合理排查后仍无法通过；
- 本机缺少 JDK 21、Maven 或 GitHub CLI 且无法安装。

停止时：已完成的提交推送到任务分支，创建 PR（标题加 `[WIP]` 前缀），在报告中说明停止位置与原因。
