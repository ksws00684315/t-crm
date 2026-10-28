# 让代理少读多查：t-crm 开工上下文瘦身方案

> 调研日期：2026-10-28。依据 `research_notes/AI 编码代理上下文工程/` 下五份笔记综合而成，另在仓库中核对了少量现状数据（标注为「本报告实测」）。证据等级用三档：**有对照证据**（有对照组的实验或论文）、**厂商自测**（卖相关产品的一方自己跑的数字，或厂商文档里的效果说法）、**从业者观点**（个人经验、演讲、博客）。厂商文档对**产品加载机制**的描述单独看待：这类描述可信度高，但版本会变，仍要在本机实测。

社区里最站得住的结论是：**开工成本应该靠「稳定的少量规则常驻 + 其余按需检索 + 硬规则交给工具报错」来压，不应靠「开工前通读更多文档」，也不应靠向量索引**。目前只有两项直接对照研究。ETH/LogicStar 在 ICLR 2026 发表的研究发现，上下文文件对成功率没有显著提升，推理成本却增加约 20%，代码库概览也没有让代理更快找到相关文件。Lulla 等只测了 Codex，发现有 AGENTS.md 时中位运行时间降低 28.64%，但没有测正确性。两项都基于 Python 或小改动样本，不能直接套到 Java 业务单体上。就 t-crm 而言，开工前的主要消耗不在根 AGENTS.md：它只有 5.6 KB，离 Codex 32 KiB 的上限很远。主要消耗在每份任务提示词里的「开工前先完整阅读」清单。以任务 76 为例，清单中的工程规范正文约 6.7 万字符，此外还有设计说明书的多个章节、前序报告和 ADR。每个任务都在独立 worktree 的新会话里重新读这些内容，而工具读出的文件内容位于对话中，跨任务基本无法命中提示词缓存，所以每次都按全价计费。建议的优先级如下：先用 Codex 会话的 token 记录建立基线；再把必读清单从「整份规范」改成「章节锚点加一句要点」，并把跨任务固定的内容前置；然后把能机械化的规范升格为带修复指引的 ArchUnit 报错，把流程性规范做成按需加载的 skill。Spring Modulith、Serena 这类工具放在最后，各有一个需要负责人拍板的问题：前者要新增依赖，后者是 GPL 许可。

## 开工成本的大头是必读清单，不是代码搜索

先把问题看准。Codex 每次运行会从 Git 根逐级走到当前目录，构建一次 AGENTS.md 指令链，拼接总量达到 `project_doc_max_bytes` 后就停止加载，**默认上限 32 KiB**，超出部分不会被读入 ([Codex docs: AGENTS.md](https://learn.chatgpt.com/docs/agent-configuration/agents-md))。t-crm 的根 AGENTS.md 只有 5,578 字节（本报告实测），远没到截断线，也不是成本主因。真正的成本出在提示词里。任务 76 的「开工前先完整阅读」要求读完本机任务通用要求（6.7 KB），规范 01～06、10、11、12 共约 66,600 字符，规范 08 第 9 节，ADR-032 的若干条，设计说明书 §4.8、§5、§8.3、§8.4、§9（设计说明书全文 276 KB），以及任务 75 的两份报告和一个测试类（本报告实测）。这些内容都是代理用工具读进对话的，不在提示词前缀里。

这个区别决定了缓存帮不上忙。OpenAI 和 Anthropic 的缓存都是**精确前缀匹配**，前缀中任何一处变化，其后的内容全部重算 ([OpenAI 文档](https://developers.openai.com/api/docs/guides/prompt-caching))；([Anthropic 文档](https://platform.claude.com/docs/en/build-with-claude/prompt-caching))。据二手资料，Codex 的提示词顺序是系统指令、工具、sandbox 配置、环境上下文（含 cwd）、AGENTS.md，最后才是用户查询 ([Daniel Vaughan 博客，二手](https://codex.danielvaughan.com/2026/04/21/codex-cli-prompt-caching-maximise-cache-hits-cost-reduction/))。t-crm 每个任务都在不同的 `.worktrees/{任务}` 目录启动，cwd 不同，前缀就在环境上下文处分叉。之后读进来的规范全文，在每个任务里都是第一次出现，按全价计费。这一点是根据缓存机制推断的，没有官方确认，可以在第 0 阶段实测。因此，「AGENTS.md 多几十行」的边际成本很小，「每个任务把整份规范重读一遍」的成本则随规范和任务数线性增长。

钱之外还有质量问题。Chroma 测了 18 个模型，发现输入越长，可靠性越低，即使任务很简单也是如此。与问题看似相关的干扰项会放大这种退化。同样的信息，聚焦版（约 300 token）在所有模型上都显著优于完整版（约 113k token）([Chroma: Context Rot](https://www.trychroma.com/research/context-rot))。对一个后端计佣任务来说，前端视觉规范、通篇的接口规范里与本任务无关的条款，恰好就是这种同项目、同术语的干扰项。IFScale 测了 20 个模型：指令密度达到 500 条时，最好的前沿模型也只有 68% 准确率，而且模型偏向遵循靠前的指令 ([arXiv 2507.11538](https://arxiv.org/abs/2507.11538))。AGENTS.md、长提示词、十份规范叠在一起，总指令数很容易超过这个量级。

反过来看，「找代码」本身在 t-crm 上不贵。代码命名后缀固定（`*Controller`、`*Service`、`*DO`），分层固定，接口路径是 kebab-case，grep 的命中率天然很高。Augment 前工程师也说，他们登顶 SWE-bench Verified 的代理靠 grep 和 find 就够了，嵌入检索要到大型代码库或陌生的第三方代码才显出价值 ([jxnl.co](https://jxnl.co/writing/2025/09/11/why-grep-beat-embeddings-in-our-swe-bench-agent-lessons-from-augment/))（从业者观点）。所以本项目要解决的首先是「读什么」，其次才是「怎么找」。

## 证据分三档：两项对照研究方向相反，其余多为自测与观点

下表把笔记里的主要结论按证据等级排开。阅读顺序建议先看「有对照证据」，再用后两档补充机制解释。

| 结论或做法 | 证据等级 | 关键数字 | 外推到 t-crm 的限制 |
|---|---|---|---|
| 仓库级上下文文件（AGENTS.md 类）对成功率的影响 ([arXiv 2602.11988](https://arxiv.org/html/2602.11988)) | 有对照证据（ICLR 2026） | LLM 生成的文件 −0.5% / −2%，不显著，成本 +20% / +23%；开发者手写的文件 +2.4%，不显著（p=0.21），成本最多 +19%；手写比 LLM 生成高约 7%，显著；概览没有减少「首次触达相关文件前的步数」；删去测试章节后成本显著下降，准确率不变 | 只覆盖 Python，只测解决率；当前版本与 ICLR 页面数字有出入（+2.4% 对 +4%） |
| AGENTS.md 对 Codex 效率的影响 ([arXiv 2601.20404](https://arxiv.org/html/2601.20404v2)) | 有对照证据（5 页 workshop 论文） | 中位墙钟时间 −28.64%，输出 token −16.58%，均显著；总 token 约持平，不显著 | 只用一个代理和一个模型，PR ≤100 行，没测正确性 |
| 长上下文退化 ([Chroma](https://www.trychroma.com/research/context-rot)) | 有对照证据（非编码任务） | 18 个模型，约 19.4 万次调用；聚焦版约 300 token 优于完整版约 113k token | 测的是 2025 年中的模型，新模型的退化幅度可能已有变化 |
| 指令数量上限 ([IFScale](https://arxiv.org/abs/2507.11538)) | 有对照证据 | 500 条指令下最好的模型 68%，偏向遵循靠前的指令 | 测的是纯指令遵循，不是代理任务 |
| 代理内置结构化代码索引 ([arXiv 2606.22417](https://arxiv.org/abs/2606.22417)) | 有对照证据（预印本，3 个种子） | 定位和解决率提升，每个成功解决的成本更低；收益集中在多文件修改 | 摘要没给具体数字；没有 Java 样本 |
| 代码健康度与 AI 修改正确率 ([arXiv 2601.02200](https://arxiv.org/abs/2601.02200v1)) | 有对照证据，但只是相关性，作者来自厂商 | 两者有「有意义的关联」 | 数据是 5,000 个 Python 竞赛编程文件 |
| 语义检索 + grep ([Cursor](https://cursor.com/blog/semsearch)) | 厂商自测（方法相对透明） | 问答准确率 +12.5%；代码保留率整体 +0.3%，1000 个文件以上的代码库 +2.6% | t-crm 单个工程都不到 1000 个文件 |
| 向量检索省 token ([Zilliz](https://zilliz.com/blog/why-im-against-claude-codes-grep-only-retrieval-it-just-burns-too-many-tokens))；Context Engine ([Augment](https://www.augmentcode.com/changelog/context-engine-mcp-in-ga))；`--compress` ([repomix](https://github.com/yamadashy/repomix)) | 厂商自测 | 分别为 ~40%、70%+、~70% | 没有公开样本量、任务和基线 |
| 多代理研究系统 ([Anthropic](https://www.anthropic.com/engineering/multi-agent-research-system)) | 厂商自测 | 比单代理高 90.2%；token 用量约为聊天的 15 倍；明确说**大多数编码任务**不适合多代理 | 研究场景 |
| 文件 <200 行、`@import` 不省上下文、子目录文件按需加载 ([Claude Code memory](https://code.claude.com/docs/en/memory)) | 厂商文档的机制说明 | — | 可信，但要按本机版本核对 |
| 上下文利用率保持 40–60%，Research → Plan → Implement ([HumanLayer](https://github.com/humanlayer/advanced-context-engineering-for-coding-agents/blob/main/ace-fca.md)) | 从业者观点加个案 | 30 万行 Rust 项目的个案，没有对照组 | 「dumb zone ≈ 40%」只见于演讲摘要 |
| 深模块、窄接口让代理「读接口不读实现」 ([AIHero](https://www.aihero.dev/how-to-make-codebases-ai-agents-love)) | 从业者观点 | — | 没有量化研究 |
| grep 优于 RAG ([Cline](https://cline.bot/blog/why-cline-doesnt-index-your-codebase-and-why-thats-a-good-thing)) | 从业者观点（内部经验） | — | 没有公开对照 |
| AGENTS.md 约 100 行做目录，lint 报错附带修复指引 ([OpenAI Harness engineering，经二手评述](https://ai-playbooks.pages.dev/reviews/harness-engineering-leveraging-codex-in-an-agent-first-world)) | 厂商经验（原文 403，未能核实） | 约 1,500 个 PR / 5 个月 | 细节均为二手转述 |

两项直接研究可以调和起来看。ETH 测的是「对不对」和「花多少」：代理确实照文件执行，例如文件提到 `uv` 时平均每个实例用 1.6 次，没提到时不到 0.01 次 ([arXiv 2602.11988](https://arxiv.org/html/2602.11988))。所以文件写了「多跑测试、多看文件」，代理就会照做，成本跟着上升。Lulla 测的是 Codex「快不快」：有文件时收敛更快，输出更短 ([arXiv 2601.20404](https://arxiv.org/html/2601.20404v2))。合起来的结论是：**人写的、只写代码里推不出来的内容（命令、约定、禁令）有价值；概览和泛泛的鼓励没有价值；文件里的每一句话都会被执行，所以每一句都有成本。** 这和 Claude Code 官方「只写 Claude 读仓库读不出来的内容」的准则一致 ([Claude Code best practices](https://code.claude.com/docs/en/best-practices))。t-crm 的约定里有大量 README 推不出来的业务规则，例如 `tenant_id`、Flyway 命名、统一信封、ID 和金额序列化为字符串，正好属于 ETH 所说手写文件该写的那类内容。但目前还没有针对 Java/Spring 的直接证据，所以每一步改动都要自己做前后对照。

## 分层是共识：稳定规则常驻，流程按需加载，硬规则由工具报错

各家工具在 2026 年的设计已经趋同，可以分成三层。

**常驻层**写每个会话都必须知道的少量事实。Codex 每次运行加载一次 AGENTS.md 链 ([Codex docs](https://learn.chatgpt.com/docs/agent-configuration/agents-md))。Claude Code 启动时加载 CLAUDE.md，并明确说 `@import` 只是把文件拆开组织，不减少上下文占用 ([Claude Code memory](https://code.claude.com/docs/en/memory))。所以「把规范全部 `@` 进 CLAUDE.md」不会省 token。t-crm 现在的 `CLAUDE.md` 只写一行 `@AGENTS.md`，按文档不会重复加载，结构是对的。需要警惕的是 AGENTS.md 里那句「冲突时以工程规范为准」。AGENTS.md 指向规范用的是文字，不是导入，代理读不读规范由它自己决定。如果某条细则每个任务都必需，就应写进 AGENTS.md 正文；否则代理很可能只按 AGENTS.md 的摘要执行，而摘要与规范一旦不一致，错误会悄无声息地发生。

**按需层**写多步流程和只与部分代码相关的知识。Skills 启动时只注入 name 和 description，相关时才读完整的 SKILL.md。Codex 给初始技能清单的上限是上下文窗口的 2%，未知窗口时为 8,000 字符，仓库级 skill 放在 `.agents/skills/` ([Codex docs: Build skills](https://learn.chatgpt.com/docs/build-skills))。Claude Code 的官方分工是：多步流程或只与一部分代码库相关的条目，移到 skill 或带 path 限定的规则中 ([Claude Code memory](https://code.claude.com/docs/en/memory))。t-crm 规范里的 Flyway 迁移步骤、浏览器验收、`preview.sh` 启动、PR 流程都是典型的流程性知识，后端任务不需要每次都背着前端验收流程开工。不过目前**没有找到量化研究**比较 skill 与全文常驻的效果，这一项的收益要靠自己测。另外，Codex 是否隐式调用 skill 取决于 description 写得是否准确；如果不放心，可以在提示词里直接写 `$skill-name` 显式点名。

子代理也属于按需层，它的价值在于**隔离上下文**，而不是分角色干活。Codex 当前默认启用子代理，但本地版本只在用户直接要求，或 AGENTS.md/skill 指示委派时才派生，而且官方承认子代理比同等的单代理运行消耗更多 token ([Codex docs: subagents](https://learn.chatgpt.com/docs/agent-configuration/subagents))。所以，「查规范第几节怎么说」「这个模块的 `api` 有哪些方法」这类只读问题适合交给 explorer，并要求它返回「结论 + 文件:行号」，长度参照 Anthropic 给出的 1,000–2,000 token 摘要 ([Anthropic: context engineering](https://www.anthropic.com/engineering/effective-context-engineering-for-ai-agents))。目的是保住主线程的注意力，而不是省总账。Claude Code 的 Explore 和 Plan 子代理会跳过 CLAUDE.md ([Claude Code sub-agents](https://code.claude.com/docs/en/sub-agents))，所以架构师派给它们的检索任务必须在提示词里写明工作目录和项目约束。并行**写代码**的多代理不在此列：Cognition 的 Flappy Bird 例子说明，各个子代理隐含的决策会互相冲突，最后无法调和 ([Cognition](https://cognition.com/blog/dont-build-multi-agents))。

**工具层**负责把规则变成报错。Claude Code 官方说，如果一条指令反复加强调词仍然被忽略，它就应该变成 hook；CLAUDE.md 只是指引，模型可以判断它不适用 ([Claude Code best practices](https://code.claude.com/docs/en/best-practices))。据二手转述，OpenAI 的 harness 做法是让自定义 linter 的报错信息直接附带修复指令，并把反复出现的人工反馈从文档升格为工具规则 ([ai-playbooks 评述](https://ai-playbooks.pages.dev/reviews/harness-engineering-leveraging-codex-in-an-agent-first-world))。ArchUnit 用 `because(...)` 控制失败信息，还提供 `FreezingArchRule` 和 `adhereToPlantUmlDiagram` 等能力 ([ArchUnit User Guide](https://www.archunit.org/userguide/html/000_Index.html))。t-crm 的 `ArchitectureTest` 现有 9 个测试，只有「Controller 不加 `@Validated`」一条写了 `because`（本报告实测）。这正是最便宜的改进点：给每条规则写清「为什么」，再写上「改成调用 xxx.api」，代理就不必开工前通读规范 01。它违规时会收到报错和改法，按报错修正即可。

还有两件不需要新工具的小事，社区经验支持度较高。第一件是**参照实现**。Simon Willison 观察到，代理几乎会原样沿用已有模式 ([Simon Willison](https://simonwillison.net/2026/Mar/14/pragmatic-summit/))（从业者观点）。t-crm 提示词里已经偶尔写「写法照现有的 orderList」，可以把它制度化：按任务类型维护一份「样板清单」，列出 CRUD 接口、列表页、迁移脚本、看板分别照哪个文件写。前提是样板本身必须完全合规，否则代理会把错误一起复制过去。第二件是把**隐式横切机制**显式写出来，例如 tenant_id 由谁注入、ID 和金额的字符串序列化在哪里全局配置、Sa-Token 权限注解怎么生效。这些行为在调用点看不到，代理不读文档就容易重复实现或漏掉（笔记中的推断，没有直接证据）。用一两行注明「机制名 → 所在类」即可，不要写成架构概览，因为 ETH 已经测出概览对定位代码没有帮助。

## 五个决策点需要负责人拍板

**Serena 的 GPL 许可。** Serena 是目前最成熟的符号级检索和编辑 MCP，支持 Java、TypeScript、Vue，也明确支持 Codex 和 Claude Code。但它的主程序是 **GPL-3.0-or-later**，只有底层的 SolidLSP 是 MIT ([oraios/serena](https://github.com/oraios/serena))。按通常理解，本地运行开发工具、不随产品分发，不会触发 GPL 的传染条款。但 AGENTS.md 第 8 条写的是「禁止 GPL/AGPL 许可证」，没有区分产品依赖和开发工具，这是规范本身的歧义，代理不能自行认定可用。此外它还有工程成本：jdtls 首次索引要解析完整 classpath，大型项目要一分钟以上 ([oh-my-opencode Java LSP 说明](https://cdn.jsdelivr.net/npm/oh-my-opencode@4.18.2/dist/skills/lsp-setup/references/java/README.md))；而 t-crm 每个任务一个 worktree，每个都要冷启动一次 JVM 索引，并行多代理时 Mac mini 的内存会成为实际约束。建议负责人先在规范 07 中明确开发工具的许可证规则，再决定用不用。即使批准，也只建议用于 Claude Code 交互会话里的跨模块重命名和查引用，不用于无人值守的 Codex 任务。

**Spring Modulith 是新增依赖。** Modulith 默认把主类所在包的直接子包识别为模块，与 t-crm 的结构天然契合。`Documenter` 能生成 PlantUML 依赖图和每个模块的画布，`verify()` 能校验边界；与 Boot 4.1 对应的是 2.1.x（2.1.1 已标为 Stable）([Spring Modulith Documentation](https://docs.spring.io/spring-modulith/reference/documentation.html))；([Spring blog 2026-08-26](https://spring.io/blog/2026/08/26/spring-modulith-2-2-m1-2-1-1-2-0-8-and-1-4-13-released/))。但即使只在 test 范围引入，按规范 07 也要报批。接入时还有三点要处理：Modulith 默认「模块基包即 API」，t-crm 需要给各模块的 `api` 子包加 `@NamedInterface`；`shared` 包要单独处理；模块画布里的聚合根和事件两栏是为 Spring Data 和 jMolecules 设计的，在 MyBatis-Plus 项目里大概率是空的 ([Spring Modulith Fundamentals](https://docs.spring.io/spring-modulith/reference/fundamentals.html))。它的价值在于让「依赖图」由测试生成，不会过期；但它和现有 ArchUnit 规则在功能上有重叠。负责人要权衡的是：多一张自动保鲜的地图，是否值得多一个依赖。

**Codex 是否加载子目录 AGENTS.md，必须实测。** 官方文档只写了「从根逐级走到 cwd」，每次运行构建一次 ([Codex docs](https://learn.chatgpt.com/docs/agent-configuration/agents-md))。按这个描述，在 worktree 根目录启动的 Codex 不会自动加载 `backend/AGENTS.md`，除非它自己去读那个文件。这与 Cursor 和 agents.md 规范描述的「离被编辑文件最近的文件生效」不同 ([agents.md](https://agents.md/))，也与 Claude Code「读写子目录文件时加载」的行为不同 ([Claude Code memory](https://code.claude.com/docs/en/memory))。在实测之前，不要把规则拆到子目录 AGENTS.md 里，否则 Codex 很可能一条都看不到。

**代码出境与新增中间件。** 向量检索方案如果用 Zilliz Cloud、OpenAI 嵌入或 Augment，代码或代码嵌入就要发到第三方云 ([zilliztech/claude-context](https://github.com/zilliztech/claude-context))。t-crm 是交付给客户的代码，需要客户或负责人同意。走全本地方案则要部署 Milvus 和 Ollama，而规范 08 第 9 节限制改动共享中间件，新增中间件也需要报批。

**新增代理配置文件是否算「新增配置」。** `.agents/skills/`、`.codex/agents/*.toml`、Codex memories（本地默认关闭，官方定位为辅助回忆层，并建议必须遵守的规则留在 AGENTS.md ([Codex memories](https://learn.chatgpt.com/docs/customization/memories?surface=app))），以及一条只跑 ArchUnit 和受影响模块单测的快速校验命令，都是规范没有覆盖的新配置。建议负责人一次性明确：这类给代理用的配置是否允许提交进仓库，由谁维护。

## 分阶段落地：先量基线，再瘦身提示词，最后加工具

核心度量借用 ETH 的思路，称为「**开工成本**」：从会话开始到代理第一次修改文件之前，累计的输入 token 数和工具调用次数。同时记录整个会话的总输入、缓存命中的输入、输出 token，以及结果质量（`mvn verify` 是否一次通过、审查发现的问题数）。数据来源是 Codex 的会话记录：`codex exec --json` 的用量事件，或本机会话目录 `~/.codex/sessions/` 下 jsonl 中的 token 计数事件，字段名以本机 Codex 版本的实际输出为准；`-c log_dir=...` 可以审计实际加载了哪些指令文件 ([Codex docs](https://learn.chatgpt.com/docs/agent-configuration/agents-md))。Claude Code 一侧用 `/usage` 中的「Prompt cache (main)」行看命中率 ([Claude Code costs](https://code.claude.com/docs/en/costs))。任务之间差异大，单看前后两个任务的数字会被任务本身的难度淹没。更可靠的做法是**回放对照**：挑一个已合并的任务，在新建的 worktree 中检出它开工时的基线提交，用旧版和新版提示词各跑 2～3 次，比较开工成本、总 token 和验收结果。回放只在新建的 worktree 里进行，测试数据加 `tNN_` 前缀，不写主目录。

| 阶段 | 做什么 | 负责人决策 | 验证方法与通过标准 |
|---|---|---|---|
| 0 基线（1～2 天） | 从最近 5～10 个 Codex 任务的会话记录中提取上述指标；用 `log_dir` 确认实际加载的指令文件；做子目录 AGENTS.md 金丝雀实验：在回放用的 worktree 里临时放一个 `backend/AGENTS.md`，写入「最终回复末尾写 CANARY-BACKEND」，从 worktree 根目录启动一个后端任务，观察日志和回复 | 否 | 产出基线表；金丝雀实验得出是否加载的明确结论，决定后面能不能用嵌套文件 |
| 1 提示词瘦身（只改文档） | 「开工前先完整阅读」改为「章节锚点 + 一句要点 + 为什么要读」，只列本任务直接适用的条款；前序报告只指向「结论」段；提示词模板中跨任务不变的段落放最前面，本任务内容放后面，这样更容易命中缓存；按任务类型列出样板文件 | 只需负责人同意改提示词模板 | 回放对照：开工成本明显下降（目标由负责人定，例如 ≥30%），`mvn verify` 一次通过率和审查问题数不变差；不变差才推广 |
| 2 AGENTS.md 微调 | 加「隐式横切机制 → 所在类」几行；把「冲突以规范为准」改成对每个任务都必需的条款直接写进正文；可以由工具检查的条目只保留出处和 `mvn verify`；不写概览，不加强调词；控制在 200 行以内，用 `/doctor prompt-audit` 查矛盾和失效引用 ([Claude Code memory](https://code.claude.com/docs/en/memory)) | 需要：AGENTS.md 是全体协作者共用的项目规则 | 回放对照；另外检查 AGENTS.md 中引用的路径和命令都存在 |
| 3 护栏升格（单独开任务） | 给 9 个 ArchUnit 测试补 `because`，写明规范出处和改法；把规范里可以机械检查的条目（`*DO` 不出接口、时间用 `Instant`、Controller 规则等）逐条评估能否做成规则 | 否（属于测试代码，但要单独开任务，不顺带做） | 在回放用的 worktree 里故意制造违规，确认失败信息带出指引；`mvn verify` 耗时增幅可以接受 |
| 4 流程类 skill | 把迁移、浏览器验收、`preview.sh`、PR 流程做成 `.agents/skills/`，Claude Code 通过符号链接共用；提示词中按需显式写 `$skill` | 需要（新增代理配置） | 用 `log_dir` 确认启动时只加载 description；对照不涉及这些流程的任务，开工成本应下降 |
| 5 代码地图（可选） | 方案甲：Spring Modulith `Documenter` 加 `verify`，输出到构建目录，不提交；方案乙：用 MIT 的 repomix 或小脚本按需导出各模块 `api` 签名，用 `--token-budget` 控制体积 ([repomix](https://github.com/yamadashy/repomix)) | 方案甲需要（新增依赖）；方案乙需明确开发工具规则 | 只有在基线显示代理确实花很多步定位跨模块代码时才做；回放对照首次触达相关文件前的步数 |
| 6 符号级工具（视决策而定） | Serena 或 JetBrains MCP，仅用于交互式跨模块重构 | 需要（GPL 许可、本机资源） | 只在实际的重命名或查引用任务上对比 grep 方案的遗漏数 |

每个阶段都设了退出条件：回放对照没有改善，就回退，不往下推。这对应 Anthropic「先写最小提示，再根据观察到的失败逐步补充」的原则 ([Anthropic: context engineering](https://www.anthropic.com/engineering/effective-context-engineering-for-ai-agents))，也能避免为了「看起来先进」而堆工具。架构师写提示词这一步，本身就是 HumanLayer 所说的 research → plan 环节。按他们的说法，计划里的一行错误可能导致数百行坏代码，所以审计划的杠杆远高于审代码 ([HumanLayer](https://github.com/humanlayer/advanced-context-engineering-for-coding-agents/blob/main/ace-fca.md))（从业者观点）。照这个思路，规范里哪些条款与本任务有关，应该由已经掌握上下文的架构师在写提示词时挑出来，而不是让 Codex 每次从十份规范里自己重新筛。但要守住一条边界：提示词里只写锚点和一句要点，不粘贴规范全文。Cursor 建议引用文件而不是复制内容，以免内容过时 ([Cursor docs: Rules](https://cursor.com/docs/context/rules))；HumanLayer 也把「错误信息」列为比「缺失信息」更坏的情况。

## 这些流行做法在本项目不建议采用

| 做法 | 不建议的原因 |
|---|---|
| 用 `/init` 或让 LLM 生成、重写 AGENTS.md | ETH 实测：LLM 生成的文件成功率不升反降，成本增加约 20%，比手写的低约 7 个百分点 ([arXiv 2602.11988](https://arxiv.org/html/2602.11988)) |
| 在 AGENTS.md 里写代码库概览或架构介绍 | 概览没有减少首次触达相关文件前的步数；Claude Code 官方也说代理会自己读仓库 ([Claude Code best practices](https://code.claude.com/docs/en/best-practices)) |
| 用 `@import` 把全部规范导入 CLAUDE.md | 导入不减少上下文占用，只会让每个会话都背上全部规范 ([Claude Code memory](https://code.claude.com/docs/en/memory)) |
| 向量或语义代码索引（Claude Context、Augment） | 代码要出境，或者要新增 Milvus/Ollama 中间件；Claude Context 没有声明支持 Vue ([zilliztech/claude-context](https://github.com/zilliztech/claude-context))；Cursor 的数据显示对代码保留率的整体提升只有 0.3% ([Cursor](https://cursor.com/blog/semsearch))；命名规整的代码库用 grep 已经够用 |
| Cline 式 Memory Bank（每次任务读完全部记忆文件） | 机制就是「全量常驻」，成本随文件数线性增长；官方也没有给出量化数据 ([Cline Memory Bank](https://docs.cline.bot/features/memory-bank)) |
| 整套引入 spec-kit、Kiro、BMAD 等规格驱动框架 | 进度跟踪表的功能说明、任务提示词、执行报告已经承担了 spec、plan、progress 三层，引入后主要只是多出模板；Böckeler 指出这类框架会小题大做，代理仍会忽略规格 ([martinfowler.com](https://martinfowler.com/articles/exploring-gen-ai/sdd-3-tools.html)) |
| 把一个功能拆给多个子代理并行写 | 隐含决策（命名、错误码、样式）会冲突 ([Cognition](https://cognition.com/blog/dont-build-multi-agents))；Anthropic 也说大多数编码任务不适合多代理 ([Anthropic](https://www.anthropic.com/engineering/multi-agent-research-system)) |
| 把 Codex memories 或 Claude auto memory 当规则来源 | 两家官方都定位为本机的辅助回忆层，必须遵守的规则应放进仓库 ([Codex memories](https://learn.chatgpt.com/docs/customization/memories?surface=app))；而且记忆只在本机，Codex 本地默认关闭 |
| 给规则加 IMPORTANT 或全大写 | 官方说加强调词主要会让模型过度执行规则；必须 100% 遵守的规则应交给 hook 或 CI ([Claude Code best practices](https://code.claude.com/docs/en/best-practices)) |
| 用 git hook 生成代码地图并提交 | 每次提交都会产生无关 diff，违反「精准修改」；应生成到构建目录，或由测试校验是否最新 |
| 机器可读的 JSON 验收清单（Anthropic harness 的做法） | 不是不好，但功能说明加逐条复核表已经承担了同样的作用，再加一层就要重复维护；可以留作以后改进复核表格式时参考 ([Anthropic harness](https://www.anthropic.com/engineering/effective-harnesses-for-long-running-agents)) |

## Conclusion

这次调研改变了问题的提法。原来的问题是「代理每次都要重新熟悉代码，怎么让它熟得更快」。证据指向的答案是：熟悉代码本身不贵，贵的是开工前被要求读完的那一大摞「可能相关」的文档。这些文档跨 worktree 无法缓存，又恰好是最容易干扰模型的同项目文本。所以，最大的杠杆在架构师写提示词这一环，不在代理端加什么工具：把筛选工作前移给已经掌握上下文的人，把规则从「要读的文字」变成「会报错的测试」，代理开工时需要带的东西自然就少了。工程规模增长时，这个思路依然成立：增长的是代码和规范，不必让每个任务的必读量跟着增长。

还有一个需要正视的不确定性：目前所有对照证据都来自 Python 和公开基准，没有一项覆盖 Java 业务单体，也没有一项测过「章节锚点 vs 全文必读」。因此，本项目最有价值的产出可能不是某个工具，而是第 0 阶段建立的回放对照方法本身。有了它，后面每一项改动是否值得做（包括 Modulith 和 Serena），都能用本项目自己的 token 数和验收结果回答，而不必依赖厂商的百分比。
