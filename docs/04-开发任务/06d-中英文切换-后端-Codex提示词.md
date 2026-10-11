# 任务 06d（后端）：按请求语言生成校验提示、导入模板与错误文件

> 本文件是交给编码代理执行的提示词。直接把本文件全文作为任务输入即可。
> **前置条件：任务 06c 后端（PR #29）已合并到 `main`。** 未合并时停止，不要开始。
>
> **并行说明**：本任务与《06d-中英文切换-管理端》《09a-微店接入基础》同时在不同工作树中执行。本任务只改 `backend/` 中与语言相关的部分；**不要修改** `admin-ui/`、`kol-portal/`、`backend/.../integration/` 包，也不要改动与语言无关的代码。

---

## 1. 背景

管理端与达人端都要支持中英文切换（设计说明书 §2.4、ADR-015；规范 03 第 6 节标注"任务 06d 起执行"的条款）。达人端已在 #28 完成，管理端由并行任务完成。前端会在每个请求上带 `Accept-Language`（`zh-CN` 或 `en-US`）。

本任务让后端产生的面向用户的文字跟随请求语言：

1. 参数校验的字段级提示；
2. 导入模板的表头与文件名；
3. 导入错误文件的表头和逐行错误原因。

**信封中的 `msg` 和 `crm_task.message` 保持英文，不翻译**：它们只用于排查，界面按 `errorCode` 显示文案（规范 02）。

## 2. 开始前必读

1. `AGENTS.md`、`docs/05-工程规范/`（重点：02、03 第 6 节、04、05）
2. 设计说明书 §2.4、§5.2 `crm_task`；ADR-015
3. 现有代码：
   - `shared.CommonConfiguration`（当前固定英文的 `englishValidationMessages`）、`shared.GlobalExceptionHandler`、`shared.FieldErrorVO`；
   - `test/.../shared/ValidationMessagesTest`；
   - 三类导入：`kol.service.KolImportColumn`、`KolImportProcessor`，`product.service.ProductImportColumn`、`ProductImportProcessor`、`MappingImportColumn`、`MappingImportProcessor`；
   - 三个导入控制器（模板下载与提交）；
   - `task.service.ImportTaskService`（`ERROR_COLUMN`、`ROW_ERROR`、`REASON_SEPARATOR`）、`ExcelFiles`；
   - 自定义校验注解（如 `kol.controller.CountryCode`）。

## 3. 执行环境与交付

- 你在独立的 git 工作树中工作，分支 `task/06d-backend` 已基于最新的 `main` 检出，直接在此分支上提交。
- 只修改：`backend/`（限本任务涉及的类、资源和测试）、`docs/03-设计/系统详细设计说明书.md`、你的执行报告。**不要修改** `docs/04-开发任务/README.md`（任务索引由项目负责人维护）。
- 能运行 Docker 时本地运行 `mvn -B verify -Pdocker-it`；否则 `*IT` 由 CI 运行。
- 完成后推送分支，按 PR 模板创建 PR，用 `gh pr checks --watch` 等待 CI 通过。**不要合并 PR，不要推送 `main`。**
- **迁移版本号**：创建 PR 前先合并最新的 `main`。若 `main` 上已有版本号更大的迁移（并行任务 09a 也会新增迁移），把你**尚未合并**的脚本改名为更晚的时间戳。
- 提交遵循规范 08，末尾加执行代理自己的 `Co-Authored-By` 行。规范 05：每项行为先写测试。

## 4. 已定的设计决定（不要自行改变）

### 4.1 请求语言

1. 只支持两种语言：`zh-CN`（`Locale.SIMPLIFIED_CHINESE`）和 `en-US`（英文）。
2. 从 `Accept-Language` 解析。缺失、无法识别或其他语言，一律按英文。
   - 支持 `zh-CN`、`zh`、`en-US`、`en` 等常见写法；
   - 带权重的多语言列表取第一个受支持的语言。
3. 使用 Spring 的 `LocaleResolver`（例如配置了受支持语言和默认语言的 `AcceptHeaderLocaleResolver`）。**结果不得受服务器默认 `Locale` 影响**：服务器默认中文时，缺失请求头仍是英文。
4. 文字资源用 Spring `MessageSource`：
   - 语言文件 `messages_zh_CN.properties` 与 `messages_en.properties`；
   - 关闭"回退到系统语言"（`fallbackToSystemLocale=false`）；
   - 两个文件的键必须完全一致，用测试检查。
   - 是否需要一个与英文内容相同的基础文件 `messages.properties`，由你决定，并在报告中说明。

### 4.2 参数校验提示

1. 把固定英文的消息插值改为按当前请求语言插值，只允许 4.1 的两种语言，其他按英文。Hibernate Validator 自带中英文的默认提示，可以直接使用。
2. 自定义校验注解的提示要有中英文两份。
3. 返回结构不变：`FieldErrorVO(field, message)` 中的 `message` 随语言变化；信封 `msg` 仍为英文。
4. `ValidationMessagesTest` 原来断言"不受请求语言影响、固定英文"，这条行为按 ADR-015 有意改变。请改写为新的断言（这不属于弱化测试，在报告中说明）：
   - `Accept-Language: zh-CN` 返回中文；
   - `en-US` 或缺失返回英文；
   - 服务器默认 `Locale` 为中文、缺失请求头时，仍返回英文。

### 4.3 导入模板

1. 三类导入（达人、原商品、对应关系）的列定义改为语言包的键，不再在枚举里写死中文。
   - 现有中文表头原样作为 `zh-CN` 的值；
   - 英文表头由你翻译，简洁、首字母大写，例如 `KOL Code`、`Name`、`Email`、`Country`、`BD Username`、`Default Commission Rate (%)`。全部英文表头列入报告，供项目负责人审阅。
2. **模板下载**：表头与文件名都使用请求语言，例如 `kol-import-template.xlsx`、`达人导入模板.xlsx`。原有中文文件名不变。
3. **导入识别**：表头整行与中文列表完全一致，或与英文列表完全一致，都能识别；中英混用、缺列、多列，仍返回原有的 `TASK_FILE_HEADER_MISMATCH`。识别与提交时的请求语言无关。

### 4.4 导入错误文件

1. `crm_task` 新增 `locale VARCHAR(8) NOT NULL`，`CHECK (locale IN ('zh-CN','en-US'))`。
   - 迁移时已有行设为 `zh-CN`（此前都是中文）；
   - 迁移后不保留默认值，新任务在提交时按请求语言显式写入；
   - 写表注释和字段注释。
2. 处理器不再返回写死的中文句子，改为返回"消息键 + 参数"；生成错误文件时按任务的 `locale` 渲染。以下都随语言变化：
   - 错误原因列的表头（`错误原因` / `Error`）；
   - 多个原因之间的分隔符（`；` / `; `）；
   - 系统错误时的整行提示；
   - 每一种行级错误原因。
3. 现有中文句子原样作为 `zh-CN` 的值。英文由你翻译，全部列入报告。
4. 后台线程没有请求语言。渲染只能使用任务行上的 `locale`，不得读取当前线程或服务器的 `Locale`。

### 4.5 不在本任务范围

| 事项 | 归属 |
|---|---|
| 管理端界面文字 | 并行任务 06d 管理端 |
| 邮件模板 | 达人登录与邀请任务 |
| 操作日志的 `module` / `action` | 保持英文固定值，由界面翻译 |
| 业务数据 | 不翻译 |
| 信封 `msg`、`crm_task.message` | 保持英文 |

## 5. 执行步骤

1. 语言解析与 `MessageSource`（4.1），以及键一致性测试。
2. 校验提示（4.2）。
3. 迁移：`crm_task.locale`（4.4 第 1 条）。
4. 导入模板与表头识别（4.3）。
5. 错误文件按任务语言渲染（4.4）。
6. 文档与执行报告（第 7 节）。

## 6. 测试要求

| 场景 | 期望 |
|---|---|
| 语言解析 | `zh-CN`、`zh`、`en-US`、`en`、`fr-FR`、缺失、多语言列表各自得到正确结果；服务器默认中文时，缺失请求头为英文 |
| 校验提示 | 同一个非法请求，中文请求头返回中文字段提示，英文请求头返回英文；信封 `msg` 两种情况都是英文 |
| 自定义注解 | 中英文提示都存在 |
| 模板下载 | 三类模板在两种语言下的表头和文件名正确 |
| 表头识别 | 全中文、全英文都能导入；中英混用返回 `TASK_FILE_HEADER_MISMATCH` |
| 错误文件 | 用英文提交的任务，错误文件为英文；用中文提交的为中文。在后台线程中渲染，与服务器默认 `Locale` 无关 |
| 存量任务 | 迁移后已有任务的 `locale` 为 `zh-CN` |
| 键一致 | 两个语言文件的键完全一致；代码中引用的每个键都存在（例如遍历列枚举和错误原因枚举） |

现有测试除 4.2 第 4 条外，不得删除或弱化。覆盖率门槛不降低。

## 7. 文档与报告

1. **设计说明书**：
   - §2.4 写明落实方式：解析规则、资源文件名、模板文件名；
   - §5.2 `crm_task` 加上 `locale`，删除"任务 06d 增加"之类的过渡措辞。
2. **执行报告** `docs/04-开发任务/06d-中英文切换-后端-执行报告.md`：
   - 每条设计决定的落实位置；
   - 中英文对照表：三类模板表头、错误文件中的全部文字；
   - `ValidationMessagesTest` 改写的说明；
   - 迁移锁表评估；
   - 测试清单与结果；
   - 偏离与遗留事项。

## 8. 禁止事项与停止条件

**禁止**：

- 修改已合并的迁移；
- 降低覆盖率门槛，放宽 ArchUnit 规则，`@Disabled` 或弱化测试（4.2 第 4 条的改写除外）；
- 新增依赖；
- 修改 `admin-ui/`、`kol-portal/`、`redirect/`、`integration` 包或任务索引；
- 推送 `main` 或合并 PR；使用 `push --force`、`reset --hard`。

**遇到以下情况停下来**：推送已完成的提交，以 `[WIP]` 标题创建 PR，在执行报告中说明后结束。

- 校验框架无法按请求语言插值，且不新增依赖就无法解决；
- 设计说明书、本提示词、规范之间存在冲突；
- 验证在合理排查后仍然失败。
