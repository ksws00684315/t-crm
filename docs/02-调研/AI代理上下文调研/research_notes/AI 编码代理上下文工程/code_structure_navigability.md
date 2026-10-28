# 代码结构与约定如何影响 AI 编码代理的导航与修改成本（Java / Spring 视角）

> 调研日期：2026-10-28。目标项目：Java 21 + Spring Boot 4.1 模块化单体（根包 `com.tcrm.admin`，13 个模块，模块内 `controller/service/mapper/model/api`，模块间只依赖 `api` 包，ArchUnit 强制），MyBatis-Plus、Flyway，Vue 3 前端。
> 说明：标注「观点」的为从业者意见，非实证；标注「厂商」的为利益相关方材料。OpenAI 原文 (openai.com) 抓取返回 403，相关细节来自二手转述，已逐条标注。

## 问题 1：深模块 / 窄接口（Ousterhout）与垂直切片架构对 AI 代理的意义

### Takeaway
从业者的共识（多为观点，非实证）是：模块少而深、接口小而明确、目录结构映射模块边界、边界处有测试，能让代理「读接口不读实现」（渐进式披露），从而降低导航成本；Thoughtworks Radar 把「AI-friendly code design」列为 Assess，并在 2026-04 将「codebase cognitive debt」列为 Caution。没有找到直接量化「深模块 vs 浅模块」对代理成功率影响的研究。

### Cited Findings
- 观点（Matt Pocock，文章 2026-02-26 更新）：代理像「没有记忆的新员工」，代码库设计比提示词或 AGENTS.md 更重要；糟糕设计导致三类问题：反馈循环慢、代码难以导航、开发者疲于手工修补 AI 输出 — [AIHero: How to make codebases AI agents love](https://www.aihero.dev/how-to-make-codebases-ai-agents-love)
- 观点（Pocock）：借用 Ousterhout《A Philosophy of Software Design》的深模块，"lots of implementation controlled by a simple interface"；提出 grey box 模块："You own the interface. AI owns the implementation."，由模块边界处测试验证行为 — [AIHero](https://www.aihero.dev/how-to-make-codebases-ai-agents-love)
- 观点（Pocock）：每个模块一个文件夹、一个公开接口，代理读类型即可理解模块，只在需要时深入实现（progressive disclosure）；顶层块数控制在约 7–8 个；在 PRD 到实现 issue 阶段就定义边界；推广 `/improve-codebase-architecture` skill — [AIHero](https://www.aihero.dev/how-to-make-codebases-ai-agents-love)
- 观点（Pocock 工作流，AI Engineer 2026）：深模块是整个流程的一环，流程还包括「Grill Me」澄清需求、写 PRD、垂直切片拆 issue、代理以 TDD 执行；二手摘要称他强调 LLM 的「smart zone / dumb zone」（约 100k token 后性能下降），注意该数字来自二手摘要 — [ai.engineer talk](https://ai.engineer/talks/ai-coding-workflow)；[二手摘要](https://cussid-huaz.github.io/agent-brain/queries/matt-pocock-ai-coding-workflow-2026/)
- 二手笔记归纳：浅而强耦合的大量小文件对代理「难导航、几乎无法测好」，深模块给代理清晰的工作面 — [acairns: Deep Modules and AI-Ready Codebases (2026-02-27)](https://acairns-co-uk.pages.dev/notes/2026/02/27/deep-modules-and-ai-ready-codebases)
- Thoughtworks Radar Vol.32（2025-04）将「AI-friendly code design」列为 Techniques / Assess，并强调警惕对 AI 生成代码的自满（此条已约 18 个月，可能过时，需核对后续卷次）— [Thoughtworks: AI technology radar vol 32](https://thoughtworks.com/insights/blog/machine-learning-and-ai/ai-technology-radar-vol-32)
- Thoughtworks Radar（2026-04-15，Vol.34）将「codebase cognitive debt」列为 Caution：实现与团队共同理解之间的差距在 AI 加速变更下扩大，建议用编码代理的 feedback sensors 和 architectural fitness functions 作为对策 — [Thoughtworks Radar: codebase cognitive debt](https://www.thoughtworks.com/radar/techniques/codebase-cognitive-debt)
- Radar Vol.34 二手摘要：重申 clean code、deliberate design、可测试性；部分团队「结合确定性结构规则与基于 LLM 的评估」减少架构漂移；建议给代理 LSP/AST 感知工具（如 Serena MCP）做符号级重构 — [Agentic AI Digest（第三方摘要）](https://agentic-ai.readthedocs.io/en/latest/AgenticTechStack/thoughtworks-radar-vol34/)
- 观点（Simon Willison，2026-03）：代理会几乎原样沿用已有模式（"they will follow those patterns almost to a tee"），因此早期示例必须干净；"Having poor quality code from an agent is a choice that you make." — [Simon Willison: Pragmatic Summit](https://simonwillison.net/2026/Mar/14/pragmatic-summit/)

### Inferences
- t-crm 的「根包直接子包 = 模块、模块间只经 `api` 包」已基本等同于 Pocock 所说的「一个文件夹一个公开接口」。代理处理跨模块需求时，读对方 `api` 包即可，不必读 `service/mapper`。13 个模块比 Pocock 建议的 7–8 个顶层块多，可以在 AGENTS.md 或代码地图中按业务域再分组，减轻认知负担（推断）。
- 模块内 `controller/service/mapper/model` 属于按技术分层，不是垂直切片。单个功能会横跨 4 个子包，但同一模块内路径固定，代理按约定就能定位，额外成本主要是要打开的文件多。没有证据表明在这种规模下把它改成按功能切片会更省（推断）。

### Gaps
- Kent Beck 关于「AI 与模块化/耦合」的论述：本轮未检索到可引用的一手来源。
- 没有找到在受控条件下比较深模块、浅模块或垂直切片对代理 token 消耗或成功率影响的研究。
- Thoughtworks Radar Vol.35（预计 2026 年 10–11 月）是否更新了相关 blip：未核实。

## 问题 2：Spring Modulith 能否为非 Modulith 项目自动生成每模块文档、充当代理代码地图（含 Boot 4.x 兼容性）

### Takeaway
可以，而且 t-crm 的包结构天然契合。Modulith 默认把主类所在包的直接子包识别为模块。`Documenter` 可以生成 C4/UML PlantUML 图和每模块 Canvas（AsciiDoc），`ApplicationModules.verify()` 负责校验边界。Boot 4.1 对应 Spring Modulith 2.1.x（2.1 GA 于 2026-06-11 发布，2.1.1 于 2026-08-26 发布）。注意两点：一是 Modulith 默认「模块基包 = API、子包 = 内部」，t-crm 需要用 `@NamedInterface` 显式声明 `api` 子包；二是 Canvas 的大部分栏目针对 Spring Data repository、jMolecules 和领域事件，对 MyBatis-Plus 项目信息量有限。

### Cited Findings
- 默认检测：以 `@SpringBootApplication` 所在包为主包，每个直接子包是一个模块；`ApplicationModules.of(Application.class)` 分析结构 — [Spring Modulith 2.1.1 Fundamentals](https://docs.spring.io/spring-modulith/reference/fundamentals.html)
- 检测策略可通过 `spring.modulith.detection-strategy` 配置：默认直接子包、`explicitly-annotated`（仅 `@ApplicationModule` 或 jMolecules `@Module` 标注的包）、自定义 `ApplicationModuleDetectionStrategy` — [Fundamentals](https://docs.spring.io/spring-modulith/reference/fundamentals.html)
- 有子包的「高级模块」：基包是 API，子包（如 `order.internal`）是内部包；Java 编译器不阻止跨模块引用内部 public 类型，由 verify 捕获 — [Fundamentals](https://docs.spring.io/spring-modulith/reference/fundamentals.html)
- 命名接口：在子包 `package-info.java` 上标 `@NamedInterface("spi")` 将其暴露为 API；`@ApplicationModule(allowedDependencies = {"order :: spi"})` 限定可依赖的模块/接口；`Type.OPEN` 开放模块用于渐进迁移遗留代码 — [Fundamentals](https://docs.spring.io/spring-modulith/reference/fundamentals.html)
- `ApplicationModules.of(...).verify()` 校验内部包访问、allowedDependencies、命名接口；可用 ArchUnit 谓词排除包；`@Modulithic` 可选（`systemName`、`sharedModules`、`additionalPackages`）— [Fundamentals](https://docs.spring.io/spring-modulith/reference/fundamentals.html)
- `Documenter`：`writeModulesAsPlantUml()`（全模块 C4 组件图）、`writeIndividualModulesAsPlantUml()`（每模块及其直接依赖）、`DiagramOptions.defaults().withStyle(DiagramStyle.UML)`、`writeModuleCanvases()`、`writeAggregatingDocument()`；默认输出到构建目录下的 `spring-modulith-docs`；通常写成一个测试方法运行 — [Spring Modulith 2.1.1 Documentation](https://docs.spring.io/spring-modulith/reference/documentation.html)
- Module Canvas 栏目：基包、API/命名接口中的 Spring 组件（按 stereotype 分组）、聚合根（有 repository 的实体或 jMolecules 声明）、发布的事件（jMolecules `@DomainEvent`）、监听的事件（`@EventListener`/`@TransactionalEventListener` 等）、`@ConfigurationProperties`（需 `spring-boot-configuration-processor`）；官方文档未提 Markdown/JSON 输出 — [Documentation](https://docs.spring.io/spring-modulith/reference/documentation.html)
- 社区项目 `li.selman:modulith-docs-markdown` 自称镜像 Documenter 输出但生成 Markdown，POM 目标 Boot 4.1.0 + Modulith 2.1.0（社区项目，未验证质量）— [Maven Central: modulith-docs-markdown](https://central.sonatype.com/artifact/li.selman/modulith-docs-markdown)
- 版本：Modulith 2.0 GA（2025-11）升级到 Boot 4.0 / Framework 7.0 — [newreleases: 2.0.0](https://newreleases.io/project/github/spring-projects/spring-modulith/release/2.0.0)；2.1 里程碑跟随 Boot 4.1 里程碑（2.1 M4 用 Boot 4.1 M4）— [Spring blog 2026-03-27](https://spring.io/blog/2026/03/27/spring-modulith-2-1-m4-2-0-5-and-1-4-10-released/)；2.1 GA 2026-06-11 — [Spring blog mirror 2026-06-11](https://spring-io-website.pages.dev/blog/2026/06/11/spring-modulith-2-1-ga-2-0-7-and-1-4-12-released)；2.2 M1 与 2.1.1 发布于 2026-08-26 — [Spring blog 2026-08-26](https://spring.io/blog/2026/08/26/spring-modulith-2-2-m1-2-1-1-2-0-8-and-1-4-13-released/)；官方 reference 页面标注 2.1.1 为 Stable — [Documentation](https://docs.spring.io/spring-modulith/reference/documentation.html)

### Inferences
- t-crm 接入方式（推断，未在仓库中试验）：只需要以 test 范围引入 `spring-modulith-core`/`spring-modulith-docs`（或 `spring-modulith-starter-test`，具体 artifact 名需核对），不需要改成「Modulith 项目」。写一个 `ModularityTests`，调用 `ApplicationModules.of(CrmAdminApplication.class)` 后再调用 `verify()` 和 `Documenter`。在每个模块的 `api` 子包加 `package-info.java` 并标注 `@NamedInterface("api")`，`controller/service/mapper/model` 就会被视为内部包。这样 verify 的语义与现有 ArchUnit 规则「只能依赖对方 api」等价，可以作为第二道校验，也可以替代部分自写规则。
- 风险点（推断）：(1) `shared` 包（`shared.ErrorCode`）会被识别为普通模块，需要声明为 `sharedModules`、把相关子包标为命名接口，或者用 OPEN 类型处理。(2) 如果 `model` 包里的 DTO 被别的模块直接使用，verify 会报违规，需要先确认现有 ArchUnit 规则是否已经禁止这种用法。(3) 按 AGENTS.md 第 8 条，新增依赖须说明理由并停下来报告，即使只在 test 范围也一样。
- 作为代理代码地图（推断）：PlantUML 文本（`.puml`）本身就是可读的依赖图，代理读文本即可得到「谁依赖谁」。Canvas 在 MyBatis-Plus 项目里，「聚合根」栏很可能为空，因为 mapper 不是 Spring Data repository；「事件」栏只有使用 Spring 事件时才有内容。Spring 组件和配置属性两栏仍有价值。生成的文档要么在 CI 中重新生成，要么由测试校验与代码一致，否则会变成过期地图，这与 OpenAI 的 doc-gardening 思路一致（见问题 4）。
- Boot 4.1 GA 的具体日期未在本轮检索中确认，但 Modulith 2.1 GA 已经依赖 Boot 4.1 线，t-crm 用 2.1.x 是合理选择。2.2 仍是里程碑版本，按项目规范不能使用。

### Gaps
- 未实测 Documenter 对 t-crm 现有包结构的实际输出质量。
- Canvas 是否能识别 MyBatis-Plus `BaseMapper` 或实体：文档未说明，推测不能。
- `modulith-docs-markdown` 的维护状况和许可证：未核实。

## 问题 3：减少探索成本的约定，以及代理在 Java/Spring 中的难点

### Takeaway
从业者意见集中在以下几点：干净的参考实现或模板（代理会照着抄）、已有测试作为风格样例和可执行文档、在 AGENTS.md 中写明栈和规则、提供 LSP/AST 级的符号工具。关于 Spring 注解魔法、DI 装配、MyBatis XML 对代理的影响，只找到零散博客轶事，没有严格基准。

### Cited Findings
- 观点（Willison）：多数项目从模板克隆起步，模板预置测试、README、CI；只要有一两个你喜欢风格的测试，代理就会按那个风格写新测试 — [Simon Willison: Pragmatic Summit](https://simonwillison.net/2026/Mar/14/pragmatic-summit/)
- 观点（Willison）：会话开始先告诉代理如何跑测试，再要求 red-green TDD；被问到某功能时，代理大概率会找到并阅读相关测试；"tests are no longer even remotely optional" — [Pragmatic Summit](https://simonwillison.net/2026/Mar/14/pragmatic-summit/)；[Agentic Engineering Patterns: First run the tests](https://simonwillison.net/guides/agentic-engineering-patterns/first-run-the-tests/)
- Thoughtworks Radar Vol.34（二手摘要）：给代理 LSP/AST 感知工具做符号级重构；「mapping code smells to refactoring techniques」 — [Agentic AI Digest](https://agentic-ai.readthedocs.io/en/latest/AgenticTechStack/thoughtworks-radar-vol34/)
- 轶事（博客）：含糊的提示会导致代理破坏项目约定。建议在任务说明中写明使用 XML 形式的 MyBatis mapper、统一返回包装和禁止改动的范围 — [besthub: Codex misuse for Spring Boot](https://www.besthub.dev/articles/why-90-of-developers-misuse-codex-for-spring-boot-the-critical-first-mistake-ac9020b94202)；另一篇建议用 AGENTS.md 固定栈与规则 — [codenote（日文）](https://codenote.net/ja/posts/java-spring-boot-ai-coding-agents-acceleration/)
- 轶事：本地代理实验中，代理漏改了 Spring XML bean 定义，也漏掉了注释里的一处引用。这是「配置/XML 魔法」导致静默遗漏的个例 — [Svitla](https://svitla.com/blog/building-a-local-first-ai-coding-agent-with-spring-boot/)
- 轶事：在含 MyBatis XML、Redis、MQ、Spring Security 的多模块项目中，代理同步修改了实体、XML mapper 和索引（作者认为成功）— [besthub: master-child agent](https://www.besthub.dev/articles/how-a-master-child-ai-agent-took-over-a-spring-boot-project-and-transformed-the-development-team-a8d6e412eb3a)
- 研究（间接）：给代理提供仓库结构的可视化图，可在保持或提升解决率的同时把输入 token 减少最多 26%，收益主要出现在故障定位阶段（SWE-bench Verified）— [arXiv 2606.14061（Emergent Mind 摘要）](https://www.emergentmind.com/papers/2606.14061)；把语义与拓扑结合的仓库图编码在 SWE-bench Verified 上达到 93.7% Acc@5 定位准确率 — [arXiv 2602.02084](https://arxiv.org/pdf/2602.02084)
- 研究（间接）：多文件变更定位中，探索结构是「二阶因素」，强制多代理会诊反而降低准确率（以 ansible 为样例，范围窄）— [arXiv 2606.11976](https://arxiv.org/abs/2606.11976)

### Inferences
- 对 t-crm 的推断：
  - 「golden path」参考实现最划算。指定 1 个模块（完整的 CRUD + 列表 + 权限码 + Flyway 迁移 + 单测 + IT）作为样板，写进 AGENTS.md，让代理照着抄，比让代理自行探索 13 个模块更便宜。代理会原样复制模式，所以样板模块必须本身合规。
  - 注解驱动行为（`@Transactional`、Sa-Token 权限注解、MyBatis-Plus 自动填充/拦截器如 tenant_id 注入、Jackson 序列化 ID/金额为字符串的全局配置）属于代理「看不到调用点」的行为。应该在代码地图或 AGENTS.md 中用一行话列出「隐式横切机制及其所在类」，否则代理容易重复实现，或者漏掉这些机制。
  - MyBatis-Plus 以 `BaseMapper` + Wrapper 为主时，SQL 在 Java 中，对代理可见。如果存在 XML mapper，需要约定 XML 与接口同名同路径，便于 grep 定位。tenant_id 若由插件注入，属于隐式行为，要显式写进文档。
  - 结构图（Modulith PlantUML）与上面「结构图可降 token」的研究方向一致，但该研究面向 Python 仓库和 SWE-bench，不能直接外推到 Java。

### Gaps
- 没有找到量化 Spring 注解/反射、DI 装配、MyBatis XML 对代理成功率或 token 影响的研究，只有轶事。
- 没有找到关于文件大小或文件行数与代理成功率关系的一手研究（CodeScene 的 Code Health 指标部分涵盖函数/文件复杂度，见问题 5）。
- 前端（Vue 3）方面的同类证据未检索（超出本轮范围）。

## 问题 4：把护栏（ArchUnit、linter、测试、类型检查）当反馈，少做前置阅读

### Takeaway
2026 年的主流框架是「harness engineering」。OpenAI 的做法是：AGENTS.md 只是约 100 行的目录，`docs/` 作为记录系统，用自定义 linter 和结构测试机械地强制分层，lint 报错里直接带修复指引。Böckeler（martinfowler.com）把护栏分为前馈（guides）和反馈（sensors）两类。Thoughtworks 和 Factory 都主张把确定性质量门禁接进代理循环。ArchUnit 提供分层/洋葱、切片无环、`because()`、`FreezingArchRule`、PlantUML 图即规则等能力，可直接用于 Java 端。

### Cited Findings
- OpenAI「Harness engineering: leveraging Codex in an agent-first world」，Ryan Lopopolo，2026-02-11；首个 commit 在 2025-08 下旬，由 Codex CLI + GPT-5 生成脚手架，初始 AGENTS.md 也由 Codex 编写（原文 403 未能直接读取，以下为二手转述）— [OpenAI（原文链接）](https://openai.com/index/harness-engineering/)；[二手汇总](https://www.theneuron.ai/explainer-articles/openais-harness-engineering-playbook-how-to-ship-1m-lines-of-code-without-writing-any/)
- 据第三方评述转述 OpenAI 原文：AGENTS.md 约 100 行，作为目录指向结构化的 `docs/`（架构、设计文档、执行计划、产品规格、参考、质量/可靠性/安全策略）；执行计划作为签入仓库的一等工件；用 linter、CI 和「doc-gardening」代理保持文档新鲜 — [ai-playbooks 评述](https://ai-playbooks.pages.dev/reviews/harness-engineering-leveraging-codex-in-an-agent-first-world)
- 同一来源转述：分层 Types → Config → Repo → Service → Runtime → UI，横切关注点经 Providers 进入；只校验层间边界，不微观管理实现；自定义 linter 的报错信息附带修复指令供代理消费；结构测试强制架构边界；反复出现的人工反馈从文档「升格」为代码或工具规则；后台代理持续「垃圾回收」，扫描模式漂移并开小型重构 PR；失败时不要求「try harder」，而是问缺了什么能力、如何让它对代理可读且可强制执行；约 1,500 个 PR / 5 个月 — [ai-playbooks 评述](https://ai-playbooks.pages.dev/reviews/harness-engineering-leveraging-codex-in-an-agent-first-world)
- 「约 100 万行、零手写」的数字来自二手报道，未在 OpenAI 原文中核实 — [theneuron](https://www.theneuron.ai/explainer-articles/openais-harness-engineering-playbook-how-to-ship-1m-lines-of-code-without-writing-any/)
- Birgitta Böckeler「Harness engineering for coding agent users」（martinfowler.com，短文 2026-02-17，全文 2026-04-02）：要让代理在更少监督下工作，需要提升对其结果的信心；二手摘要称其把 harness 分为 guides（前馈：架构规则、编码规范、示例、任务边界）与 sensors（反馈：测试、静态分析、运行时日志、评审），另一维是 computational vs inferential 控制；区分厂商 harness 与用户 harness（AGENTS.md、linter、CI、hooks）— [syscoi 转载](https://stream.syscoi.com/2026/04/05/harness-engineering-for-coding-agent-users-bockeler-2026)；[Thoughtworks 播客](https://www.thoughtworks.com/insights/podcasts/technology-podcasts/what-harness-engineering)（原文 martinfowler.com/articles/harness-engineering.html 本轮未直接抓取）
- Thoughtworks Radar Vol.34（二手摘要）：feedforward 控制提高首轮正确率，feedback 控制形成自纠循环；把编译器、linter、类型检查、测试作为确定性质量门禁接入代理工作流，在人工评审前自动纠错 — [Agentic AI Digest](https://agentic-ai.readthedocs.io/en/latest/AgenticTechStack/thoughtworks-radar-vol34/)
- 厂商（Factory，2026-01-20）：代理表现参差多是代码库而非模型的问题；8 大支柱（Style & Validation、Build System、Testing、Documentation、Dev Environment、Code Quality、Observability、Security & Governance），5 级成熟度（Functional、Documented、Standardized、Optimized、Autonomous），Level 3「Standardized」为首要目标（E2E 测试、文档维护、安全扫描、可观测性）；提供 Droid CLI `/readiness-report` — [Factory: Agent Readiness](https://factory.com/news/agent-readiness)；[Factory: Measuring agent readiness](https://factory.ai/articles/measuring-agent-readiness)
- 开源替代：Kodus `@kodus/agent-readiness`（MIT，本地运行，7 支柱 39 项检查）— [kodustech/agent-readiness 镜像](https://gh.r-mj.com/kodustech/agent-readiness)
- ArchUnit 用户指南（1.5.1）：`layeredArchitecture()` / `onionArchitecture()`（8.1）、`slices().matching(..).should().beFreeOfCycles()`（4.7/8.2）、`because(...)` 控制规则文本（7.6）、`FreezingArchRule` 记录现有违规只让新违规失败（8.6）、`adhereToPlantUmlDiagram` 把 PlantUML 组件图当规则（8.5）、JUnit 5/6 `@AnalyzeClasses` + `@ArchTest`（9.1）— [ArchUnit User Guide](https://www.archunit.org/userguide/html/000_Index.html)

### Inferences
- 对 t-crm 的推断：
  - 「让工具告诉代理」在 Java 端的最低成本做法是：给每条 ArchUnit 规则补 `.because("见 docs/05-工程规范/01 §x：模块间只能依赖 api 包；改为调用 xxx.api.XxxApi")`。规则文本中写明「为什么」和「怎么改」，等同于 OpenAI 的「带修复指引的 lint 报错」。是否显示在失败信息中，指南页面未明说，需要实测。
  - 规范里能机械化的条目（禁止通配符导入、Lombok、字段注入、`System.out`、`*DO` 出现在接口签名、Controller 类级 `@Validated`、金额必须 `BigDecimal`、时间必须 `Instant`）都适合做成 ArchUnit 或 Spotless/Checkstyle 规则。做完之后 AGENTS.md 可以只保留规则的出处，不必复述细节，实现 OpenAI 所说的「从文档升格为工具」。
  - 当前 `mvn verify` 已包含格式、静态检查、单测、架构测试和覆盖率，按 Factory 的划分大致在 Level 3。主要短板可能是反馈速度：代理每改一次就跑全量 verify 成本高。可以提供一条只跑 ArchUnit 和受影响模块单测的快速命令（推断，需评估是否属于「新增配置」而须报告）。
  - Modulith 的 `adhereToPlantUmlDiagram` 与 `Documenter` 能形成闭环：图既是文档也是规则，从机制上避免代码地图过期。

### Gaps
- OpenAI 原文全文未能直接读取（403），分层、linter 报错、100 行 AGENTS.md 等细节均为二手转述，引用前应在原文核实。
- Böckeler 全文未直接抓取。
- 没有找到「护栏反馈 vs 前置文档」对代理 token 或成功率影响的对照实验。

## 问题 5：代码质量/模块化与代理表现的实证研究

### Takeaway
最直接的实证是 CodeScene 团队在 FORGE 2026 发表的 arXiv 2601.02200。研究发现人类导向的 CodeHealth 指标与 LLM 重构后的语义保持存在「有意义的关联」，厂商新闻稿称不健康代码中 AI 的缺陷风险至少高 30%。但数据集是 5,000 个 Python 竞赛编程文件，与工业 Java 代码差距很大，而且作者来自厂商。没有找到直接以「模块化、耦合度」为自变量、测量代理成功率的研究。

### Cited Findings
- Borg、Hagatulah、Tornhill、Söderberg，"Code for Machines, Not Just Humans: Quantifying AI-Friendliness with Code Health Metrics"，arXiv 2601.02200，2026-01-05 提交，录用于 FORGE 2026；方法：让 LLM 重构代码并检查语义是否保持，与 CodeHealth 对比；数据：5,000 个 Python 竞赛编程文件；结论：CodeHealth 与重构后语义保持有有意义关联，人类易懂的代码也更适合 AI，可用 CodeHealth 判断哪里可放心交给 AI、哪里需人工复核（摘要页未给出效应量和模型名）— [arXiv 2601.02200](https://arxiv.org/abs/2601.02200v1)
- 厂商新闻稿（2026-01-28）：不健康代码中 AI 编码助手使缺陷风险「至少 30%」上升，遗留系统中可能「far higher」；Code Health 为 10 分制，7.0 以下为「truly unhealthy」，基于非线性推断破坏率陡增；未给出代理的推荐分数阈值；loveholidays 案例称 5 个月内代理辅助代码从 0 到 50% — [PRNewswire（CodeScene 新闻稿）](https://tools.prnewswire.com/en-us/live/20813/release/20260128EN71904)
- 矛盾：第三方博客称该研究涵盖 25,000 个 C++/Java/Python 文件，与论文摘要的 5,000 个 Python 文件不符，以论文为准 — [arXiv 2601.02200](https://arxiv.org/abs/2601.02200v1)；[第三方博客](https://codex.danielvaughan.com/2026/06/06/ai-ready-code-health-codescene-research-codex-cli-agent-performance-quality-gates/)
- CodeScene 白皮书注明研究只覆盖 Code Health ≥ 7.0 的代码，未测试最差的代码（厂商材料）— [CodeScene whitepaper: AI-Ready Code](https://codescene.com/hubfs/whitepapers/AI-Ready-Code-How-Code-Health-Determines-AI-Performance.pdf)
- arXiv 2507.00788 研究 AI 协同开发是否影响可维护性（使用 CodeHealth），实验在 2024 年底进行，早于编码代理普及，已部分过时 — [arXiv 2507.00788](https://arxiv.org/pdf/2507.00788v3)
- 间接证据：仓库结构的显式表示（图、可视化）可降低 token 并帮助定位 — [arXiv 2606.14061](https://www.emergentmind.com/papers/2606.14061)；[arXiv 2602.02084](https://arxiv.org/pdf/2602.02084)；探索方式本身是二阶因素 — [arXiv 2606.11976](https://arxiv.org/abs/2606.11976)

### Inferences
- 现有证据支持「代码健康，也就是函数和文件级的复杂度、重复、嵌套等，会影响 AI 修改的正确率」，但只是相关性，而且语言、规模与 t-crm 不同。「模块化降低导航成本」目前主要靠从业者共识和机制推理支撑，缺少实证。
- 对 t-crm（推断）：项目已有 Spotless、静态检查和覆盖率门槛，CodeScene 所测的那类函数级健康度大概率处于中上水平。收益更大的投入可能在两处：隐式机制的显式化（问题 3），以及护栏报错的可操作性（问题 4），而不是继续细化代码结构。

### Gaps
- 2601.02200 全文中的具体效应量、所用模型，以及是否覆盖 Java：本轮只看到摘要。
- 没有找到在 Java/Spring 工业代码库上测量代理成功率与模块化或耦合度关系的研究。
- 没有找到测量「AGENTS.md / 代码地图 vs 无文档」对代理 token 消耗影响的独立研究（可能由其他研究员覆盖）。
