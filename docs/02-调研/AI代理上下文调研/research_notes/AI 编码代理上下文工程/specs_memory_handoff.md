# 规格驱动开发、记忆库与任务交接文档（AI 编码代理）

> 调研日期：2026-10-28。来源以一手文档为主，二手博客/聚合站单独标注。"（观点）"表示从业者个人看法，不是实测结论。

## 问题 1：规格驱动工具（spec-kit、Kiro、Tessl、BMAD、OpenSpec）各自持久化什么、代理怎样加载

### Takeaway
这几种工具都把知识分成两层：一层是"长期上下文 / 记忆库"（constitution、steering、KNOWLEDGE.md），每次会话都加载；另一层是"单次变更规格"（spec/plan/tasks，或 requirements/design/tasks），按功能或变更保存。差别主要在于规格完成后还留不留、要不要回写：spec-kit 和 Kiro 实际上属于 spec-first；OpenSpec 用 delta + archive 把变更合并回 `specs/`，偏 spec-anchored；Tessl 是唯一明确走 spec-as-source 的。

### Cited Findings
- Böckeler 把规格驱动开发（SDD）分成三个层级。spec-first：先写规格再编码，规格只服务当前任务。spec-anchored：任务结束后保留规格，用来演进这个功能。spec-as-source：人只改规格，代码由规格生成。她评测过的工具都做到了 spec-first，但很少有工具真正承诺后两级，规格怎么长期维护多数说得含糊（2025-10-15）。— [martinfowler.com: Understanding SDD: Kiro, spec-kit, and Tessl](https://martinfowler.com/articles/exploring-gen-ai/sdd-3-tools.html)
- 同一篇文章区分了两个概念：spec 是面向行为、针对某个变更的自然语言产物；memory bank 是跨所有会话都适用的通用代码库上下文（规则、产品和架构描述）。— [martinfowler.com](https://martinfowler.com/articles/exploring-gen-ai/sdd-3-tools.html)
- **GitHub spec-kit**：CLI 生成工作区文件和斜杠命令。流程是 Constitution → Specify → Plan → Tasks（→ Implement）。constitution 是"对每次变更都适用的高层原则"，作者认为它实质上就是一个强力的规则文件。每个 spec 会产出 spec、plan、tasks、research、data-model、api 等多份文件，而且每个 spec 新开一条分支。作者据此判断 spec 绑定的是"变更请求"，不是功能的整个生命周期。— [martinfowler.com](https://martinfowler.com/articles/exploring-gen-ai/sdd-3-tools.html)
- spec-kit 的版本：二手来源称 2026-06-16 发布 0.11.0，支持 30 多种代理（Copilot、Claude Code、Cursor、Gemini CLI、Codex CLI 等）；另有来源称 2026-05-12 时最新是 v0.8.9。我没有核对官方 release 页面，2026-06 之后的版本情况不明。— [dev.to 二手](https://dev.to/rams901/github-spec-kit-how-104k-developers-are-making-ai-plan-before-it-codes-in-2026-5p2)；[ice-ice-bear 博客 二手](https://ice-ice-bear.github.io/posts/2026-05-13-github-spec-kit/)
- **AWS Kiro specs**：每个功能有 requirements.md（用户故事 + GIVEN/WHEN/THEN 验收条件）、design.md（组件图、数据流、数据模型、错误处理、测试策略）、tasks.md（任务清单，可追溯到需求编号）。— [martinfowler.com](https://martinfowler.com/articles/exploring-gen-ai/sdd-3-tools.html)
- **Kiro steering**（Kiro 的记忆库）：
  - 存放位置：工作区 `.kiro/steering/`，全局 `~/.kiro/steering/`，两者冲突时工作区优先。默认生成 product.md、tech.md、structure.md 三个文件。
  - 加载方式：用 YAML front matter 指定 `inclusion`，取值有 `always`（默认）、`fileMatch`（按 `fileMatchPattern` 匹配文件时加载）、`manual`（用 `#name` 手动引用）、`auto`（请求与 `description` 匹配时加载）。四种模式只有 IDE 和 CLI V3 完整支持，V1/V2 只会自动加载 `always`。
  - 文件引用：`#[[file:path]]` 可指向实际项目文件，也可以只引用一段行号（`:start-end`）；引用解析失败时会显示标记，不会静默丢弃。
  - AGENTS.md：支持，并且始终加载。
  - 自定义 agent：默认不加载 steering，需要在 `resources` 里声明。— [kiro.dev/docs/steering](https://kiro.dev/docs/steering/)
- **Tessl**（文章写作时处于私测）：CLI 同时也是 MCP server，会生成 `.tessl/framework`，记忆库由 KNOWLEDGE.md 和 AGENTS.md 组成。spec 带 `@generate`、`@test` 标签；`tessl build` 生成的代码文件头部标注 "GENERATED FROM SPEC - DO NOT EDIT"，当时是一份 spec 对应一个代码文件；`tessl document` 可以从现有代码反推 spec。作者观察到同一份 spec 多次生成的代码并不相同（非确定性）。— [martinfowler.com](https://martinfowler.com/articles/exploring-gen-ai/sdd-3-tools.html)
- **OpenSpec**：
  - 目录结构：`openspec/specs/` 保存"当前行为"，每个变更放在 `openspec/changes/<name>/` 下。
  - delta spec：变更里的 delta spec 只写差异，分 ADDED / MODIFIED / REMOVED 三类。
  - archive：归档时把 delta 合并进 `specs/`，变更目录移到 `changes/archive/` 并加日期前缀。归档会提示有未完成的任务，但不会阻止归档。
  - 已知缺口：归档之前，`specs/` 反映不出实际上线的内容。— [codemyspec 二手](https://codemyspec.com/blog/openspec-explained)；[besthub 二手](https://www.besthub.dev/articles/is-openspec-the-most-developer-friendly-spec-tool-more-detailed-than-cursor-plan-and-lighter-than-spec-kit-40366ed87e73)
- **BMAD Method**：
  - 名称与版本：全称 "Breakthrough Method for Agile Ai Driven Development"。稳定版 v6.12 只修 bug，已冻结；V7 处于 preview alpha，会有破坏性变更，计划 2026-10-16 发布。V7 是否已正式发布，我没有查到。— [github.com/bmad-code-org/BMAD-METHOD](https://github.com/bmad-code-org/BMAD-METHOD)
  - 当前文档的组织方式：以 skills 为单位，分"思考类"（澄清、调研、质疑、记录决策）和"构建类"（如 `bmad-build`）。小改动可以直接进入构建。已有代码库建议先运行 `bmad-project-context` 建立上下文。— [docs.bmad-method.org](https://docs.bmad-method.org/)

### Inferences
- 用户已有的产物和这些框架大致对得上：AGENTS.md 加工程规范 ≈ constitution / steering；设计说明书 ≈ 长期 spec（spec-anchored）；每个任务的提示词 ≈ spec + plan + tasks；执行报告 ≈ progress / 归档后的变更记录。在这个基础上再引入 spec-kit，主要新增的是模板和斜杠命令，知识层级本身没有增加。
- Kiro 的 `fileMatch` 和 Claude Code 的 path-scoped rules（见问题 2）都是"按需加载"。这一点比"全部常驻"更值得借鉴，能降低每次会话固定消耗的上下文。
- OpenSpec 的 delta → archive 合并，是这些工具里唯一有明确机制把变更回写到长期规格的。如果用户希望设计说明书跟着代码变，这是最值得参考的模式。

### Gaps
- spec-kit 2026-06 之后的版本，以及 constitution 的具体加载方式（是否每条命令都注入），没有找到官方 release notes 核实。
- BMAD 旧版里的 PRD / architecture / story 文件分片（sharding）机制在当前文档首页看不到，V7 是否已正式发布也未核实。
- OpenSpec 官方文档页（openspec.dev/docs/overview）返回 404，相关描述只来自二手来源。
- Tessl 2026 年的现状（是否已经结束私测、是否仍坚持 spec-as-source）未查到。

## 问题 2：记忆库类方案（Cline Memory Bank、Claude Code memory / auto memory、Codex memories）

### Takeaway
2026 年的主流做法分两层。第一层是人写、提交进仓库的指令文件（AGENTS.md / CLAUDE.md / steering），每次会话加载。第二层是工具自己生成、只存在本机的"回忆层"（Claude Code auto memory、Codex memories）。两家官方都明确说：必须遵守的规则放第一层，第二层不能作为规则的唯一来源。Cline Memory Bank 不是产品功能，而是一套提示词方法论，靠"每次任务开始都读完全部文件"来工作。

### Cited Findings
- **Cline Memory Bank**：
  - 性质：是"文档方法论"，不是内置功能。做法是把一段自定义指令放进 `.clinerules/memory-bank.md` 或全局指令里。
  - 核心文件（在 `memory-bank/` 下）：projectbrief.md（基础需求和目标，位于最上层）、productContext.md、systemPatterns.md、techContext.md、activeContext.md（当前焦点、最近改动、下一步，变动最频繁）、progress.md（哪些能用、还剩什么、已知问题）。
  - 读取规则：指令要求每次任务开始时必须读完全部文件。
  - 更新方式："update memory bank" 命令会触发一次全量审阅和更新；上下文快满时，建议先更新 memory bank 再开新会话。
  - token 成本：官方页没有量化。— [docs.cline.bot/features/memory-bank](https://docs.cline.bot/features/memory-bank)
- **Claude Code CLAUDE.md**：
  - 加载规则：工作目录及上级目录的 CLAUDE.md / CLAUDE.local.md 在启动时加载，子目录里的按需加载。
  - 篇幅建议：官方建议每个文件控制在 200 行以内，"越长越占上下文、遵守度越低"。`@path` 导入只是方便组织内容，并不减少上下文开销。
  - path-scoped rules：只针对部分代码的规则可以放进 `.claude/rules/`，用 `paths:` 指定，只在处理匹配文件时加载。
  - 指令冲突：两条指令互相矛盾时，Claude "可能任意选一条"。
  - 强制性：这些文件被当作上下文而不是强制配置；要强制禁止某个动作，应该用 PreToolUse hook。— [code.claude.com/docs/en/memory](https://code.claude.com/docs/en/memory)
- **Claude Code 读取 AGENTS.md**：需要 v2.1.277 及以上。默认行为是"存在 CLAUDE.md 就不读 AGENTS.md"，CLAUDE.local.md 也算在内。如果想两者都读，可以设 `claude-md-and-agents-md`，或者继续在 CLAUDE.md 里写 `@AGENTS.md` 导入（官方说保留这个导入不会导致重复加载）。— [code.claude.com/docs/en/memory](https://code.claude.com/docs/en/memory)
- **Claude Code `/doctor prompt-audit`**（v2.1.283+）：检查指令文件中的过时内容、引用了不存在的文件或命令、文件之间互相矛盾等问题。只给出修改建议，不会自动改文件。— [code.claude.com/docs/en/memory](https://code.claude.com/docs/en/memory)
- **Claude Code auto memory**：
  - 默认状态：本地会话默认开启。
  - 存储位置：`~/.claude/projects/<project>/memory/`，由 `MEMORY.md` 索引（每条一行）和每条记忆一个 topic 文件组成。
  - 加载范围：每次会话加载 MEMORY.md 的前 200 行或 25KB。
  - 记忆类型：分 user / feedback / project / reference 四类。官方说明会跳过能从代码推导出的内容（架构、文件路径、调试修复），也会跳过 CLAUDE.md 里已经写过的内容。
  - 共享范围：只在本机，同一仓库的各 worktree 共享，不跨机器同步；不受会话记录的清理周期影响。— [code.claude.com/docs/en/memory](https://code.claude.com/docs/en/memory)
- **Codex memories**：
  - 开启方式：本地默认关闭（"Local Codex memories are off by default"）。可在 Settings > Personalization 里开启，或在 `config.toml` 的 `[features]` 中设 `memories = true`；用 `/memories` 可以单独控制当前对话。
  - 存储与生成：存放在 `~/.codex/memories/`，被定义为 "generated state"，不建议手工编辑。只在对话空闲后才生成，跳过短会话，会对密钥做脱敏；`memories.disable_on_external_context` 可以排除用过 MCP 或 web 搜索的对话。
  - 官方建议："Keep required team guidance in `AGENTS.md` or checked-in documentation"，把 memories 当作 "helpful recall layer"。— [learn.chatgpt.com/docs/customization/memories](https://learn.chatgpt.com/docs/customization/memories?surface=app)（由 developers.openai.com/codex/memories 308 跳转而来）
- 二手来源称 Codex memories 按用户隔离，团队之间不能共享。— [codex.danielvaughan.com 二手](https://codex.danielvaughan.com/2026/04/18/codex-built-in-memory-system-deep-dive/)

### Inferences
- 用户的现状（AGENTS.md 加工程规范，以及 auto memory 里的 owner 决策和反馈）已经符合两家官方推荐的分层。风险在于 MEMORY.md 里的"项目状态"类条目（例如"29/30 merged"）会过时，而它每次会话都会加载。
- Cline 式的"每次读完全部文件"在文件变多后成本线性增长；Kiro 和 Claude Code 都转向按路径、按描述加载，说明业界在远离"全量常驻"。
- `/doctor prompt-audit` 是 Claude Code 内置的"防过时"工具，可以列入定期检查清单。

### Gaps
- Roo Code / Kilo Code 在 2026 年是否有官方的 memory bank 功能（而不是社区提示词）：本次没有检索到一手来源。
- Codex memories 的发布日期和版本状态（官方页面未写），也没有核实它是否仍属实验性功能。

## 问题 3：任务交接文档模式（progress 文件、research → plan → implement、长时运行代理 harness、活文档）

### Takeaway
最成熟的交接模式有三个共同点：用机器可读的进度清单（JSON）和 git 历史作为"状态真相"；每次会话开始先跑一段固定的定位流程；每个阶段结束时把状态压缩回文件。HumanLayer 认为人审研究文档和计划的杠杆比审代码更大。

### Cited Findings
- **Anthropic《Effective harnesses for long-running agents》**（2025-11-26，Justin Young）：
  - 两种会话：initializer 会话负责生成 `init.sh`、`claude-progress.txt`、功能清单和首次提交；之后的每个 coding 会话只做一个功能，结束时代码要处于可合并状态，并提交、更新进度。两种会话用同一个 harness，只是初始 prompt 不同。
  - 功能清单：用 JSON，清单示例超过 200 项，初始全部标为失败；coding 代理只能改 `passes` 字段。选 JSON 是因为模型改动或覆盖 JSON 的可能性比 Markdown 小。
  - 约束语：使用了强措辞，原文 "It is unacceptable to remove or edit tests"。
  - 会话开始流程：`pwd` → 读 git log 和进度文件 → 读功能清单，选优先级最高的未完成项 → 用 `init.sh` 启动服务并做一次端到端冒烟测试。
  - 效果：文章没有给出量化指标。— [anthropic.com/engineering/effective-harnesses-for-long-running-agents](https://www.anthropic.com/engineering/effective-harnesses-for-long-running-agents)
- **HumanLayer《Advanced Context Engineering for Coding Agents》**（frequent intentional compaction）：
  - 核心做法：上下文利用率保持在 40%–60%。流程是 research → plan → implement，每完成一个经过验证的阶段，就把状态压缩回 plan 文件。研究文档、计划和进度都是 markdown，由 "thoughts tool" 管理；commit message 也可以当作一种压缩。
  - 关于审阅杠杆：一行错误代码只是一处错，一行错误计划可能导致上百行错，一处错误研究可能导致上千行错。作者的做法是审大约 200 行计划，而不是 2000 行代码。— [github.com/humanlayer/advanced-context-engineering-for-coding-agents](https://github.com/humanlayer/advanced-context-engineering-for-coding-agents/blob/main/ace-fca.md)
- Anthropic 的另一篇工程文章《Effective context engineering for AI agents》把"structured note-taking"（代理把 NOTES.md 之类的笔记写到上下文之外，之后再读回）列为长时任务技术之一。（本次没有重新抓取原文核对，依据的是既有知识。）— [anthropic.com/engineering/effective-context-engineering-for-ai-agents](https://www.anthropic.com/engineering/effective-context-engineering-for-ai-agents)
- spec-kit 的模板和检查清单被描述为"definition of done"。— [martinfowler.com](https://martinfowler.com/articles/exploring-gen-ai/sdd-3-tools.html)
- Kiro 官方建议把 steering 的改动当作代码改动来对待，要经过评审；在 sprint 规划或架构变更时复查 steering；用文件引用指向实际文件，不复制内容。— [kiro.dev/docs/steering](https://kiro.dev/docs/steering/)

### Inferences
- 用户的"执行报告"相当于 claude-progress.txt 加 HumanLayer 的阶段压缩，但它是给人看的叙述体。可以借鉴 Anthropic 的做法：把"功能 / 验收项 → passes"拆成机器可读的清单，只允许代理改状态字段，用来防止代理改写验收标准。不过 tracker 的"功能说明"已经承担了需求来源的角色，再加一层要权衡重复维护的成本。
- HumanLayer 的"审计划而不是审代码"和用户在 PR 审查之前审任务提示词是同一个思路。

### Gaps
- HumanLayer `thoughts/` 目录的具体结构（shared/personal/searchable 等）这次没有抓到一手说明，ace-fca.md 只提到 "thoughts tool" 而没有展开细节。

## 问题 4：批评（文档腐烂、规格漂移、开销、生成文档误导代理）

### Takeaway
批评集中在四点：规格和代码不同步（被认为是最主要的失败模式）、审阅 markdown 的开销、"有控制感"其实是错觉（代理仍会忽略规格）、LLM 生成的上下文文件实测有害。最有力的量化证据来自 ETH 等机构 2026-02 的论文：上下文文件对成功率影响很小或为负，成本却上升约 20%。

### Cited Findings
- **Böckeler 的批评**（2025-10）：
  - 问题规模不匹配：Kiro 把一个小 bug 扩成 4 个用户故事、16 条验收条件，原话 "like using a sledgehammer to crack a nut"。
  - 审阅负担：原话 "I'd rather review code than all these markdown files"。
  - 控制感是错觉：代理仍会忽略指令，spec-kit 没有用上自己的 research 笔记，重复造了已有的类，有时又过度套用 constitution。
  - 和迭代开发冲突：小步迭代与 SDD 的前置设计相悖。
  - 与 MDD 类比：spec-as-source 类似模型驱动开发，可能"兼有 MDD 和 LLM 的缺点"。— [martinfowler.com](https://martinfowler.com/articles/exploring-gen-ai/sdd-3-tools.html)
- **arXiv 2602.11988《Evaluating AGENTS.md》**（2026-02-12，Gloaguen 等）：
  - 实验设置：新建 AGENTbench（12 个仓库、138 个实例），另用 SWE-bench Lite（300 题）；测试了 Claude Code（Sonnet-4.5）、Codex（GPT-5.2 / GPT-5.1 mini）、Qwen Code。
  - LLM 生成的上下文文件：成功率下降约 0.5%（SWE-bench Lite）和 2%（AGENTbench），引言写的是平均 −3%，与正文数字不一致；成本上升约 20% 和 23%。
  - 开发者手写的上下文文件：成功率平均约 +4%，但对 Claude Code 没有帮助；成本最多上升 19%，平均多出约 3.34 步。
  - 对代码导航的作用：上下文文件没有缩短代理找到相关文件所需的步数，作者结论是这类文件不是有效的仓库概览。
  - 原因：代理确实照着文件执行，结果多跑了测试、多探索了文件。
  - 作者建议：暂时不用 LLM 生成的上下文文件，只写最小化的人工要求（例如仓库特有的工具）。— [arxiv.org/html/2602.11988v1](https://arxiv.org/html/2602.11988v1)
- 一位从业者在用代理构建大型 C# 系统后称 "specification staleness was the primary failure mode"（观点 / 个人经验）。— [dev.to: Your AI spec is already stale（经搜索摘要转述，未全文核对）](https://dev.to/t3chn/your-ai-spec-is-already-stale-3h0a)
- 有作者认为，过时的规格会变成"回归生成器"：代理按过时的意图写代码，评审代理又读同一份过时规格（观点）。— [dev.to: Spec rot](https://dev.to/askpatrick/spec-rot-why-your-ai-agents-task-spec-becomes-a-liability-over-time-542p)
- Kiro 不会在执行过程中自动对齐规格，需要开发者自己发现漂移并触发刷新（厂商竞品方的说法）。— [augmentcode.com: living specs vs static specs](https://www.augmentcode.com/guides/living-specs-vs-static-specs)
- Claude Code 官方承认：互相矛盾的指令会被"任意"取舍，文件越长遵守度越低。— [code.claude.com/docs/en/memory](https://code.claude.com/docs/en/memory)
- HumanLayer 自己报告过失败案例：7 小时没能移除 parquet-java 的 Hadoop 依赖，原因是研究没有追踪到足够深的依赖树；一个竞态问题耗费了两周。作者强调方法 "not magic"。— [HumanLayer ace-fca.md](https://github.com/humanlayer/advanced-context-engineering-for-coding-agents/blob/main/ace-fca.md)

### Inferences
- 对用户最直接的启示有两条：AGENTS.md 里的"项目概览"类内容收益可能很低，"必须遵守"的规则和工具命令才是有效内容；任何由 LLM 生成、没有经过人工审定的上下文文件，都应该当作有害内容的候选来看待。
- 这篇论文测的是 SWE-bench 风格的单个 issue 修复，和"多任务长周期、需要业务决策上下文"的场景不同。它不能证明设计文档、ADR 没用，只能说明"仓库概览"类内容对定位代码帮助不大。

### Gaps
- 没有找到针对 Cline Memory Bank 本身的独立评测或系统性批评。
- 没有找到 Böckeler 或 martinfowler.com 在 2026 年发布的 SDD 后续文章，是否有更新不明。

## 问题 5：怎样防止这些文档过时（CI、从代码再生成、审阅清单）

### Takeaway
目前没有成熟的自动同步方案（有 IEEE 综述称这仍是未解决的问题）。常见做法有四类：把文档更新写进 definition of done 或评审要求；用指向实际文件的引用代替复制；用工具定期审计矛盾和失效引用；用 hook 或脚本在"代码改了、文档没改"时发出提醒。

### Cited Findings
- Kiro：steering 改动要像代码一样评审；在 sprint 规划或架构变更时复查；用 `#[[file:]]` 引用实际文件，不复制内容；目录重构后重新检查引用（解析失败的引用会显示标记）。— [kiro.dev/docs/steering](https://kiro.dev/docs/steering/)
- Claude Code：官方建议定期复查 CLAUDE.md、嵌套 CLAUDE.md 和 rules，删除过时或冲突的内容；`/doctor prompt-audit` 可以找出过时指令、不存在的文件或命令引用和互相矛盾的内容；auto memory 不记录能从代码推导出的内容，从源头减少会过时的条目。— [code.claude.com/docs/en/memory](https://code.claude.com/docs/en/memory)
- OpenSpec：用 archive 步骤把 delta 合并回 `specs/`，相当于强制回写。归档前 `specs/` 和实际上线内容不一致。— [codemyspec 二手](https://codemyspec.com/blog/openspec-explained)
- Tessl：`tessl document` 可以从代码反推 spec（反向再生成）。— [martinfowler.com](https://martinfowler.com/articles/exploring-gen-ai/sdd-3-tools.html)
- Anthropic harness：每个会话结束时必须提交代码并更新进度文件；会话开始先做端到端冒烟测试，发现上一会话留下的坏状态。— [anthropic.com/engineering](https://www.anthropic.com/engineering/effective-harnesses-for-long-running-agents)
- 从业者做法（观点 / 个人工具）：
  - 会话开始时用 hook 对比最近提交和"子系统 → 文件"映射，代码改了而规格没改时发出警告。
  - 规格 30 天未复审就标记为 stale。
  - 开源工具 stalebrain 会把 CLAUDE.md、AGENTS.md、.cursorrules 等对照当前代码做审计。
  - 以上做法均来自搜索摘要，没有逐一全文核对。— [dev.to: Your AI spec is already stale](https://dev.to/t3chn/your-ai-spec-is-already-stale-3h0a)；[dev.to: Spec rot](https://dev.to/askpatrick/spec-rot-why-your-ai-agents-task-spec-becomes-a-liability-over-time-542p)；[pypi: stalebrain](https://pypi.org/project/stalebrain/0.1.1/)
- 有说法称 IEEE 综述认为代码与文档的稳健同步"仍未解决"（经 Augment Code 转述，没有找到原始综述）。— [augmentcode.com](https://www.augmentcode.com/guides/living-specs-vs-static-specs)

### Inferences
- 对单人开发者，成本最低的组合是：
  - 在 PR 审查清单里加一项"本 PR 涉及的设计说明书 / ADR / AGENTS.md 是否同步"，用户的规范 12 已经要求文档同步；
  - 每隔若干个任务跑一次 `/doctor prompt-audit`；
  - 定期清理 MEMORY.md 里的状态类条目，或者改成指向进度跟踪表的链接；
  - 可选：写一个脚本检查 AGENTS.md 和规范文档里引用的路径、命令是否存在。
- "从代码再生成文档"在 Tessl 之外很少见，而且受非确定性影响，不适合作为合同交付的设计说明书。

### Gaps
- 没有找到在 CI 中自动检测"规格与代码不一致"的成熟开源方案或官方实践，只有个人脚本和 hook。

## 问题 6：减少探索、时间、token 的量化证据

### Takeaway
有对照实验的量化证据很少，而且是负面的（arXiv 2602.11988：上下文文件让步数和成本上升，并没有更快找到相关文件）。正面数字都是厂商或从业者的个案自述，没有对照组。

### Cited Findings
- 对照实验：上下文文件让平均步数增加约 2.45–3.92 步（LLM 生成的文件），成本上升 20%–23%；开发者手写的文件让步数增加约 3.34 步、成本最多上升 19%；两者都没有缩短找到相关文件所需的步数。— [arXiv 2602.11988](https://arxiv.org/html/2602.11988v1)
- HumanLayer 个案（无对照）：
  - 在 30 万行 Rust 的 BAML 项目里，作者不熟悉 Rust，修复的 PR 第二天就被批准；基于研究写出的计划比不做研究的计划产出了更好的修复。
  - 取消和 WASM 支持两个功能共约 3.5 万行，耗时约 7 小时（3 小时研究和计划，4 小时实现），维护者估计资深工程师每个功能需要 3–5 天。
  - 三人团队每月 Opus 花费约 1.2 万美元。— [HumanLayer ace-fca.md](https://github.com/humanlayer/advanced-context-engineering-for-coding-agents/blob/main/ace-fca.md)
- Anthropic harness：没有量化指标，只提到 `init.sh` 能避免代理"浪费 token 去弄清怎么运行应用"，以及 Puppeteer 端到端测试 "dramatically improved performance"（定性描述）。— [anthropic.com/engineering](https://www.anthropic.com/engineering/effective-harnesses-for-long-running-agents)
- 网上流传"Codex 记忆技巧让效率提升 90%"之类的说法，来源是营销性质的多语言落地页，没有方法说明，不可信。— [youmind.com 落地页](https://youmind.com/zh-CN/landing/x-viral-articles/codex-ai-memory-system-guide)

### Inferences
- 目前的证据支持"少而精、人写、可执行的规则和命令"，不支持"大而全的概览或记忆库"。交接文档的价值更多体现在多会话、长周期的状态延续，以及人审计划的杠杆上，而不是体现在单个任务的 token 节省上。

### Gaps
- 没有找到 spec-kit、Kiro、BMAD、OpenSpec、Cline Memory Bank 任何一个的对照实验或公开基准数据。
- Claude Code auto memory 和 Codex memories 对任务成功率或 token 的影响，官方都没有公开数据。
