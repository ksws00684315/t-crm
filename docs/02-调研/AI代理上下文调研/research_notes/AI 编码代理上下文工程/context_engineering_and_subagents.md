# 上下文工程原则与降低 token 成本的代理运行框架技术（面向 Codex CLI / Claude Code 派发长 Markdown 任务提示词的单人开发者）

> 调研日期：2026-10-28。所有事实均附来源；第三方博客、演讲摘要标注为「二手/观点」。时效性问题在各节「Gaps」或行内用【过时】标出。

## 1. Anthropic 三篇工程文章：上下文工程、多代理研究系统、长时运行代理的运行框架

### Takeaway
Anthropic 的核心观点是：上下文是有限的「注意力预算」，目标是用最少的高信号 token 达成目标。具体做法有：即时检索（只保留路径/链接等标识符，用到时再加载）、压缩（compaction）、结构化笔记（文件外存）、子代理返回 1,000–2,000 token 的浓缩摘要。多代理能显著提分，但 token 消耗约为聊天的 15 倍，而且 Anthropic 明确说大多数编码任务不适合多代理。长时运行代理靠「初始化代理 + 进度文件 + JSON 功能清单 + git + 每次只做一个功能」来跨会话保持状态。

### Cited Findings
**《Effective context engineering for AI agents》（2025-09-29）**
- 定义：上下文工程是在推理过程中「策划和维护最优 token 集合」的策略集合，是提示词工程的下一步，需要反复迭代 — [Anthropic](https://www.anthropic.com/engineering/effective-context-engineering-for-ai-agents)
- Context rot：token 越多，召回准确率越低，下降是渐进的，没有硬性断崖。Transformer 有 n² 的 token 两两关系，每个新 token 都在消耗有限的注意力预算。目标是用「最小的高信号 token 集合」 — [Anthropic](https://www.anthropic.com/engineering/effective-context-engineering-for-ai-agents)
- 系统提示词要找到「合适的高度（right altitude）」：介于写死的 if-else 逻辑和含糊指导之间。用 XML 标签或 Markdown 标题分区。先在最强模型上测一个最小提示词，再根据观察到的失败逐条补充指令 — [Anthropic](https://www.anthropic.com/engineering/effective-context-engineering-for-ai-agents)
- 工具集要精简、功能少重叠、输出省 token。工具集臃肿是常见失败模式。示例用少量多样的典型例子，不要堆砌边界情况规则 — [Anthropic](https://www.anthropic.com/engineering/effective-context-engineering-for-ai-agents)
- 即时检索（just-in-time）：上下文里只保留文件路径、查询、链接等轻量标识符，运行时再用工具加载。Claude Code 的例子：`CLAUDE.md` 预先加载，其余文件用 glob/grep 按需检索（混合策略）。默认建议是「do the simplest thing that works」 — [Anthropic](https://www.anthropic.com/engineering/effective-context-engineering-for-ai-agents)
- 长周期三板斧：(1) Compaction：在 Claude Code 中保留架构决策、未解决的 bug，以及最近访问的 5 个文件；调优时先保召回再求精度。(2) 工具结果清除（tool result clearing）：最轻量的压缩方式。(3) 结构化笔记：如 NOTES.md 或待办清单，Pokémon 示例中代理跨数千步保持计数。Sonnet 4.5 发布时，文件型 memory 工具进入公测 — [Anthropic](https://www.anthropic.com/engineering/effective-context-engineering-for-ai-agents)
- 子代理在干净的上下文中工作，每个可能用掉数万 token，但只向主代理返回约 **1,000–2,000 token** 的浓缩摘要。选型建议：长对话用 compaction，有明确里程碑的迭代开发用笔记，可并行探索的复杂研究用多代理 — [Anthropic](https://www.anthropic.com/engineering/effective-context-engineering-for-ai-agents)

**《How we built our multi-agent research system》（2025-06-13）**
- BrowseComp 上，三个因素解释了 **95%** 的性能方差，其中 token 用量单独解释 **80%**，另两个是工具调用次数和模型选择 — [Anthropic](https://www.anthropic.com/engineering/multi-agent-research-system)
- 代理的 token 用量约为聊天的 **4 倍**，多代理系统约为 **15 倍** — [Anthropic](https://www.anthropic.com/engineering/multi-agent-research-system)
- 多代理（Opus 4 主代理 + Sonnet 4 子代理）在内部研究评测上比单代理 Opus 4 高 **90.2%** — [Anthropic](https://www.anthropic.com/engineering/multi-agent-research-system)
- 不适合多代理的场景：所有代理需要共享同一上下文的领域；代理之间依赖多的任务；**大多数编码任务**（可并行的子任务比研究少）；实时协调与委派 — [Anthropic](https://www.anthropic.com/engineering/multi-agent-research-system)
- 子代理把产出写入外部系统（文件系统），只向协调者回传轻量引用，以减少信息损失和 token 开销。完成的阶段先摘要并存到外部，接近上限时可以另起一个干净上下文的子代理 — [Anthropic](https://www.anthropic.com/engineering/multi-agent-research-system)
- 按复杂度分配投入：简单事实查找用 1 个代理、3–10 次工具调用；对比类用 2–4 个子代理、每个 10–15 次；复杂研究用 10 个以上。并行起 3–5 个子代理，复杂查询耗时最多降低 90%。改进工具描述后任务完成时间降低 40% — [Anthropic](https://www.anthropic.com/engineering/multi-agent-research-system)
- 【过时】文中「超过 200,000 token 会被截断，所以主代理把计划存入 memory」是 2025 年中的模型窗口条件 — [Anthropic](https://www.anthropic.com/engineering/multi-agent-research-system)

**《Effective harnesses for long-running agents》（2025-11-26，Justin Young）**
- 问题：代理按离散会话工作，每次都从零记忆开始。即使是 Opus 4.5 + Agent SDK，只给高层提示词也做不出生产级 Web 应用。失败模式有两种：一次做太多、在功能中途耗尽上下文；后续会话过早宣布完成 — [Anthropic](https://www.anthropic.com/engineering/effective-harnesses-for-long-running-agents)
- 初始化代理：第一次会话生成 `init.sh`、`claude-progress.txt` 和初始 git commit。初始化代理和编码代理**只有初始用户提示词不同**，系统提示词、工具、运行框架完全相同 — [Anthropic](https://www.anthropic.com/engineering/effective-harnesses-for-long-running-agents)
- 功能清单用 JSON：claude.ai 克隆示例展开为 **200+ 个功能**，每项含 category、description、测试步骤和 `passes` 字段，初始全部为 false。编码代理只能修改 `passes`，提示词禁止删改测试。选 JSON 的理由是模型比较不会不当地改写 JSON，而 Markdown 容易被改 — [Anthropic](https://www.anthropic.com/engineering/effective-harnesses-for-long-running-agents)
- 每个会话只做一个功能。会话结束时写描述性 commit 和进度摘要，git 用来回滚到可用状态。端到端测试用 Puppeteer MCP 以真人方式验证，单测和 curl 会漏掉端到端 bug — [Anthropic](https://www.anthropic.com/engineering/effective-harnesses-for-long-running-agents)
- 会话开场「快速上手」步骤：`pwd` → 读 git log 和进度文件 → 读功能清单 → 选优先级最高的未完成功能 → 用 `init.sh` 起服务 → 先跑一次基础端到端检查，再开始新工作 — [Anthropic](https://www.anthropic.com/engineering/effective-harnesses-for-long-running-agents)

### Inferences
- 对每次派发一个长 Markdown 任务的单人开发者，「初始化代理 / 编码代理只差初始提示词」意味着：项目规则（AGENTS.md）应保持稳定、跨任务共用，任务专属内容只出现在初始用户消息里。这既符合注意力预算原则，也是缓存友好的结构（见第 5 节）。
- 「token 用量解释 80% 方差」说的是研究类任务里「花更多 token 探索」会提分。它**不等于**「预先塞更多文档」会提分（见第 7 节 Context Rot）。正确的理解是：把 token 花在按需检索上，而不是开场灌入。
- 任务提示词里可以借鉴「JSON 验收清单 + 只允许改状态字段」的做法，防止代理改写验收条款。这和本项目「逐条复核表」的要求一致。

### Gaps
- Anthropic 没有公开 compaction 的具体保留比例或质量评测数字，只有定性描述。
- 「1,000–2,000 token 摘要」是研究系统的经验值，没有找到编码场景的对应测量。

## 2. Manus：KV-cache 命中率、文件系统即上下文、只追加、todo 复述

### Takeaway
Manus 认为 KV-cache 命中率是生产代理最重要的单一指标：其输入:输出约为 100:1，缓存价与非缓存价差 10 倍。围绕它的做法有：稳定前缀、只追加、确定性序列化、工具「遮蔽而非删除」、用文件系统作为无限外部记忆并做可恢复压缩、反复重写 todo.md 把目标拉到上下文末尾、保留错误、避免 few-shot 惯性。

### Cited Findings
- 作者 Yichao 'Peak' Ji，2025-07-18。KV-cache 命中率决定延迟和成本，是生产代理最重要的指标 — [Manus](https://manus.im/blog/Context-Engineering-for-AI-Agents-Lessons-from-Building-Manus)
- Manus 平均**输入:输出 token 约 100:1**。以 Claude Sonnet 为例，缓存输入 **$0.30/MTok**，非缓存 **$3/MTok**，相差 10 倍 — [Manus](https://manus.im/blog/Context-Engineering-for-AI-Agents-Lessons-from-Building-Manus)（【过时】当前 Anthropic 对 Opus 5.5 / Sonnet 5.5 的缓存读取倍率是 0.05×，见第 5 节 — [Anthropic 文档](https://platform.claude.com/docs/en/build-with-claude/prompt-caching)）
- 前缀要稳定：一个 token 的变化就会让其后的缓存全部失效。不要在系统提示词开头放精确到秒的时间戳。上下文只追加，不修改历史动作和观察。序列化要确定性，JSON 键序变化会悄悄破坏缓存。需要时显式设置缓存断点，至少包含系统提示词的结尾 — [Manus](https://manus.im/blog/Context-Engineering-for-AI-Agents-Lessons-from-Building-Manus)
- 工具「遮蔽而非删除」：任务中途不增删工具，改为按状态遮蔽 logits。工具名用统一前缀（`browser_`、`shell_`）以便分组约束 — [Manus](https://manus.im/blog/Context-Engineering-for-AI-Agents-Lessons-from-Building-Manus)
- 文件系统即上下文：它无限、持久、代理可操作。压缩必须可恢复：只有在 URL 或文件路径还保留时，才丢弃网页或文档正文 — [Manus](https://manus.im/blog/Context-Engineering-for-AI-Agents-Lessons-from-Building-Manus)
- 复述目标：Manus 不断重写 todo.md，把目标放到上下文末尾。典型任务约 **50 次工具调用**，复述用来对抗目标漂移（lost-in-the-middle） — [Manus](https://manus.im/blog/Context-Engineering-for-AI-Agents-Lessons-from-Building-Manus)
- 保留失败的动作和堆栈，让模型更新判断、避免重犯。避免 few-shot 惯性：重复的上下文会导致模仿和漂移（如连续审 20 份简历），可在序列化和措辞上引入小的结构化变化 — [Manus](https://manus.im/blog/Context-Engineering-for-AI-Agents-Lessons-from-Building-Manus)
- 框架重写了 4 次，作者自嘲为「Stochastic Graduate Descent」 — [Manus](https://manus.im/blog/Context-Engineering-for-AI-Agents-Lessons-from-Building-Manus)

### Inferences
- 编码代理的输入:输出比同样极端，因为每轮都要重发全部历史（Claude Code 文档也这样描述，见第 5 节）。所以对单人开发者，「缓存命中率」比「提示词字数」更值得关注：长但稳定的 AGENTS.md 前缀主要按缓存价计费，而每次任务都变动的内容如果放在前面，会让全部内容按全价计费。
- 「可恢复压缩」可以直接用在任务提示词上：提示词里只放文档路径和章节锚点（例如「见 docs/05-工程规范/03 第 2 节」），不粘贴全文，让代理按需读取。
- todo 复述对应 Codex/Claude Code 的计划工具和进度文件。长任务应要求代理维护进度文件，而不是依赖开场提示词一直被注意到。

### Gaps
- Manus 没有公布实际命中率数值，只说它是首要指标。

## 3. Cognition「Don't Build Multi-Agents」与 Anthropic 子代理观：只读探索子代理何时有益、何时有害

### Takeaway
双方的共识是：并行**写代码**的多代理容易因隐含决策冲突而失败；**只读、回答明确问题**的子代理的价值在于隔离上下文，让探索噪声不进入主线程。分歧在于 Anthropic 在研究场景下认可多代理并行，而 Cognition 默认单线程线性代理加历史压缩。2026 年两家工具（Claude Code、Codex）的官方文档都推荐子代理做只读/读多写少的工作，并警告并行写入冲突和 token 成本上升。

### Cited Findings
- Cognition（Walden Yan，2025-06-12）两条原则：(1) 共享上下文，要传完整的代理轨迹而不只是单条消息；(2) 动作隐含决策，决策冲突会导致坏结果。Flappy Bird 例子：两个子代理分别做出马里奥风格背景和动作不对的小鸟，最终代理无法调和 — [Cognition](https://cognition.com/blog/dont-build-multi-agents)
- Cognition 推荐单线程线性代理。超长任务可以用单独的模型把历史压缩成关键细节、事件和决策（Cognition 为此微调过小模型），但承认这很难做好，终究还是会碰到上限 — [Cognition](https://cognition.com/blog/dont-build-multi-agents)
- Cognition 对 Claude Code 子代理的观察（2025-06）：子代理不与主代理并行写代码，通常只用来回答一个明确定义的问题；好处是其调查过程不进入主代理历史，主轨迹可以更长 — [Cognition](https://cognition.com/blog/dont-build-multi-agents)（【过时】现在的 Claude Code 子代理默认在后台运行、可嵌套 3 层、并发上限 20，见第 4 节 — [Claude Code 文档](https://code.claude.com/docs/en/sub-agents)）
- Anthropic 同样承认：需要共享上下文、依赖多的任务和大多数编码任务不适合多代理 — [Anthropic](https://www.anthropic.com/engineering/multi-agent-research-system)
- HumanLayer/Dex Horthy：子代理的意义是「控制上下文，不是角色扮演」。用一个新上下文窗口去搜索或总结，避免父代理被搜索和读取调用塞满 — [HumanLayer ACE-FCA](https://github.com/humanlayer/advanced-context-engineering-for-coding-agents/blob/main/ace-fca.md)
- Claude Code 官方的取舍建议：频繁来回、阶段间共享大量上下文、快速定点修改、对延迟敏感的工作留在主会话；输出冗长（测试、日志、搜索）、需要限制工具权限、自包含且能返回摘要的工作交给子代理。很多子代理各自返回长结果，本身也会占用大量主上下文 — [Claude Code 文档](https://code.claude.com/docs/en/sub-agents)
- Codex 官方：子代理把嘈杂工作移出主线程、返回摘要，以避免 context pollution 和 context rot。推荐用于并行的读多型工作（探索、测试、分诊、总结）；并行写多型工作要谨慎，因为同时改代码会冲突。子代理比同等单代理运行「消耗更多 token」 — [Codex 文档](https://learn.chatgpt.com/docs/agent-configuration/subagents)

### Inferences
- 对单人派发任务，有益的场景：主代理需要在大代码库中定位文件或符号、读长日志或测试输出、核对需求原文与实现是否逐条对应。这些产出可以压缩成结论，适合交给只读子代理。
- 有害的场景：(1) 把一个功能拆给多个子代理并行写，隐含决策（命名、错误码、样式）会冲突；(2) 子代理没拿到关键约束（规范、范围），得出与主线程不一致的结论；(3) 任务本来很小，子代理冷启动重新推导上下文反而更贵。Claude Code 的 Explore/Plan 跳过 CLAUDE.md，正是为了省 token，代价是它不知道项目规则。需要遵守项目规则的子代理任务，必须在派发提示词里写明约束。
- 实操规则：子代理返回值要限长（参照 1,000–2,000 token），要求返回「结论 + 文件路径/行号」，不返回文件全文。

### Gaps
- 没有找到在编码任务上比较「主代理 + 只读子代理」与「单代理」的 token 和成功率的公开量化评测，只有定性建议。
- Cognition 2026 年是否更新立场：文章本身没有更新说明，侧栏的 2026 文章未确认相关。

## 4. Claude Code 与 Codex 的子代理、compaction、/compact 与自动压缩质量（截至 2026-10）

### Takeaway
Claude Code 内置 Explore（只读，quick/medium/very thorough 三档，跳过 CLAUDE.md 和 git status 以节省 token）、Plan、general-purpose 三类子代理，并支持 fork（继承父会话、可读父缓存）、后台运行、嵌套和恢复。Codex 当前默认启用子代理（default/worker/explorer），但只在用户或 AGENTS.md/skill 明确要求时才派生。两者都有手动和自动 compaction。压缩会重写历史、使会话层缓存失效，最好在任务之间的自然断点主动执行，而不是等到任务中途自动触发。

### Cited Findings
**Claude Code**
- Explore：快速只读，Write/Edit 被禁用，由调用方指定 quick/medium/very thorough。为保持低成本，跳过 CLAUDE.md 和 git status 快照。可以用同名自定义子代理覆盖（例如 `model: haiku`）。Plan：plan mode 下的只读研究代理，同样跳过 CLAUDE.md — [Claude Code 文档](https://code.claude.com/docs/en/sub-agents)
- 子代理在独立的上下文窗口中运行，大量输出留在子代理内，只把摘要返回主会话。子代理请求计入同一用量额度 — [Claude Code 文档](https://code.claude.com/docs/en/sub-agents)
- 默认情况下从主会话派生的子代理在后台运行。Fork 继承父会话的系统提示词、工具和历史。子代理可以嵌套，默认最多 3 层（`CLAUDE_CODE_MAX_SUBAGENT_SPAWN_DEPTH`），并发上限 20。Explore/Plan 是一次性的，不能恢复 — [Claude Code 文档](https://code.claude.com/docs/en/sub-agents)
- 普通子代理的首个请求读不到父会话缓存（前缀不同），fork 可以读到。子代理不属于主会话的 TTL 档位，即使是订阅用户也默认 5 分钟 TTL — [Claude Code 提示词缓存文档](https://code.claude.com/docs/en/prompt-caching)
- 降低 token 的官方建议：无关任务之间用 `/clear`；`/compact Focus on ...` 或在 CLAUDE.md 写「Compact instructions」定制压缩要保留的内容；CLAUDE.md 控制在 **200 行以内**，专用流程移到按需加载的 skills；MCP 工具定义默认延迟加载，优先用 `gh` 等 CLI；用 hook 预处理输出（例如 10,000 行日志只返回 ERROR 行，上下文从「数万 token 降到数百」）；用子代理处理冗长操作；写具体提示词，避免「改进这个代码库」这类触发大范围扫描的请求 — [Claude Code 成本文档](https://code.claude.com/docs/en/costs)
- 成本基线：企业部署中平均约 **$13/开发者/活跃日**，**$150–250/月**，90% 的用户低于 $30/活跃日。Agent teams 在 plan mode 下约为标准会话的 **7 倍** token — [Claude Code 成本文档](https://code.claude.com/docs/en/costs)
- `/compact` 本身是一次大请求：缓存还热时它从缓存读前缀，成本只是上下文规模的一小部分；空闲超过 TTL 后执行，则按未缓存价重处理全部历史。官方建议在自然断点主动 `/compact`；要放弃一条路径时用 `/rewind`（回到已缓存的前缀）；只想重新开始时 `/clear` 不花钱 — [Claude Code 提示词缓存文档](https://code.claude.com/docs/en/prompt-caching)、[成本文档](https://code.claude.com/docs/en/costs)
- 会话中途编辑 CLAUDE.md 不会让缓存失效，但也不会生效，要到 `/clear`、`/compact` 或重启后才加载 — [Claude Code 提示词缓存文档](https://code.claude.com/docs/en/prompt-caching)
- Anthropic 工程文章：Claude Code 的 compaction 保留架构决策、未解决的 bug 和最近访问的 5 个文件 — [Anthropic](https://www.anthropic.com/engineering/effective-context-engineering-for-ai-agents)

**Codex**
- 当前 Codex 默认启用子代理（`agents.enabled = true`）。本地版本只在直接请求，或适用的 AGENTS.md/skill 指示委派时才派生。内置 `default`、`worker`（实现与修复）、`explorer`（读多型探索）。自定义代理是 `~/.codex/agents/` 或 `.codex/agents/` 下的 TOML，必填 `name`、`description`、`developer_instructions`，可选 `model`、`model_reasoning_effort` 等。子代理继承父级的 sandbox/approval，不能覆盖。CLI 里用 `/subagents` 查看和切换线程。非交互流程中需要新审批的动作会失败，并把错误返回给父流程 — [Codex 文档](https://learn.chatgpt.com/docs/agent-configuration/subagents)
- 配置项有 `max_concurrent_threads_per_session`、`default_subagent_model`、`default_subagent_reasoning_effort`；`agents.max_threads` 是旧别名 — [Codex 文档](https://learn.chatgpt.com/docs/agent-configuration/subagents)。搜索摘要中出现的 `max_threads = 6`、`max_depth = 1` 来自旧版或第三方，当前官方页面未提到 `max_depth` — 【待核实】
- AGENTS.md：先读全局 `~/.codex`（优先 AGENTS.override.md），再从 git 根逐级走到 cwd，每个目录最多取一个文件，由根到叶拼接（越靠近 cwd 越靠后、优先级越高）。指令链**每次运行构建一次**。`project_doc_max_bytes` 默认 **32 KiB**，合计达到上限后不再加入更多文件 — [Codex AGENTS.md 文档](https://learn.chatgpt.com/docs/agent-configuration/agents-md)
- Codex compaction：Michael Bolin《Unrolling the Codex agent loop》（2026-01-23）摘录显示，每个新请求都是上一个请求的精确前缀延伸，这让提示词缓存成为可能；超过 `auto_compact_limit` 时自动调用压缩端点，用更短的条目列表替换历史 — [OpenAI（经搜索摘录，原文 403 未能直接读取）](https://openai.com/index/unrolling-the-codex-agent-loop)
- 自动压缩阈值说法不一（二手）：有说默认为窗口的 80%，有说按模型在 180k–244k 之间，有说有效上限不超过窗口的 90%；手动用 `/compact` — [Daniel Vaughan 博客（二手）](https://codex.danielvaughan.com/2026/04/16/codex-cli-context-compaction-tuning-long-sessions/)

### Inferences
- 自动压缩质量的风险：压缩是有损摘要，Anthropic 也说要「先调召回」。长任务提示词里的硬约束（范围、禁止事项、验收条目）在压缩后可能被弱化。缓解办法：硬约束放在 AGENTS.md（Claude Code 在压缩后会从磁盘重载项目上下文层；Codex 是否在压缩后重新注入 AGENTS.md 未经证实，见 Gaps），任务状态写进仓库内的进度文件，而不是只存在对话里。
- 派发长任务给 Codex 时，若希望使用 explorer 子代理，需要在 AGENTS.md 或任务提示词里**明确要求**委派，否则 Codex 本地不会自己派生。
- 32 KiB 的 AGENTS.md 上限约合 8k token 量级（按约 4 字节/token 粗估；中文按 UTF-8 每字 3 字节，实际字符数更少）。规则文件超限会被静默截掉后面的部分，所以项目规则应放在前面、简明扼要，细则用路径引用。

### Gaps
- 没有找到关于 Claude Code 或 Codex 自动压缩「质量」的公开量化评测（例如压缩后指令遵从率）。第三方博客有讨论（如 CompressAgent），但未核实。
- Codex 压缩后是否重新注入 AGENTS.md、Codex 默认自动压缩阈值：没有找到官方原文。
- Codex cloud / `codex exec` 与子代理的交互细节：官方页面未覆盖。

## 5. 编码代理的提示词缓存经济学：OpenAI 自动缓存、Anthropic 缓存定价、稳定前缀结构；Codex CLI 是否跨会话复用缓存

### Takeaway
两家都是**精确前缀匹配**：前缀中任何一处变化，其后全部重算。OpenAI 自动缓存（GPT-5.6 及以后：1,024 token 起，缓存读 0.1×，最低保留 30 分钟）；Anthropic 是写入 1.25×（5 分钟）或 2×（1 小时）、读取 0.1×（Opus/Sonnet 5.5 为 0.05×）。结构原则：静态内容（指令、工具、AGENTS.md、参考资料）在前，任务专属和动态内容（时间戳、任务正文）在后。跨会话复用在 API 层面可行（缓存按组织/前缀，不按会话）。Claude Code 文档明确说同目录并行会话能互读缓存，顺序会话只有 git status 快照相同时才共享前缀。Codex CLI 的跨会话复用没有官方确认，但可以推断：同一目录、同一版本、同一 AGENTS.md、在 TTL 内启动的新会话可以命中系统指令 + AGENTS.md 前缀。

### Cited Findings
**OpenAI**
- 支持的模型默认自动缓存。GPT-5.6 及以后前缀至少 **1,024 token**；更早的模型阈值因请求设置而异，`cached_tokens` 按 **128** 的倍数向下取整 — [OpenAI 文档](https://developers.openai.com/api/docs/guides/prompt-caching)
- 折扣「最高 95%」：GPT-5.6 及以后缓存读为输入价的 **0.1×**（GPT-6.1 Sol/Terra 为 0.05×），更早模型按各自的缓存价 — [OpenAI 文档](https://developers.openai.com/api/docs/guides/prompt-caching)
- 保留期：更早模型 in_memory 约 5–10 分钟（最长 1 小时），`24h` 扩展保留通常约 30 分钟、最长 24 小时；GPT-5.6 及以后默认最低 **30 分钟**，Responses API 可通过 `prompt_cache_options.ttl` 选 12 小时（写入更贵） — [OpenAI 文档](https://developers.openai.com/api/docs/guides/prompt-caching)
- 静态内容（指令、工具定义、参考资料）放前面，动态内容（时间戳、用户相关细节）放后面。缓存不跨组织、不跨区域处理边界。同一前缀 + key 超过约 15 次/分钟会溢出到其他机器、可能未命中。`prompt_cache_key` 在 GPT-5.6 及以后主要用于缓存记账 — [OpenAI 文档](https://developers.openai.com/api/docs/guides/prompt-caching)
- Codex 代理循环是只追加的，新请求是旧请求的精确前缀延伸 — [OpenAI Bolin（经摘录）](https://openai.com/index/unrolling-the-codex-agent-loop)
- 二手：Codex 提示词顺序为系统指令 → 工具/MCP → sandbox 配置 → 环境上下文 → config 中的开发者指令 → AGENTS.md → cwd/shell → 用户查询；会话中途修改配置时，以追加新消息的方式应用，不改前缀；中途增删 MCP、在指令中放时间戳、跨会话修改 AGENTS.md 都会破坏前缀匹配 — [Daniel Vaughan 博客（二手，引用了 OpenAI 原文但未核实）](https://codex.danielvaughan.com/2026/04/21/codex-cli-prompt-caching-maximise-cache-hits-cost-reduction/)。该文称 Codex 缓存「5 折」，并给出 GPT-5.3-Codex 价格（$1.75 / $0.875 / $14 每 MTok），【过时】与 GPT-5.6+ 的 0.1× 不符 — 同上

**Anthropic / Claude Code**
- 定价倍率：5 分钟写入 1.25×，1 小时写入 2×，读取 0.1×（Fable/Mythos 5.1 为 0.025×，Opus 5.5 / Sonnet 5.5 为 0.05×）。默认 TTL 5 分钟，命中即续期。最多 4 个显式断点，每个断点向前回看 20 个 block。层级为 tools → system → messages，改动会让本层及之后全部失效。最小可缓存长度：Opus 5.5 / Sonnet 5.5 / Haiku 5.5 为 512 token，Opus 4.5/4.6、Haiku 4.5 为 4,096。缓存按组织隔离，Claude API 上还按 workspace 隔离 — [Anthropic 文档](https://platform.claude.com/docs/en/build-with-claude/prompt-caching)
- Claude Code 的请求分层：系统提示词 + 工具定义（工具集变化时才变）→ 项目上下文 CLAUDE.md/memory（会话开始、`/clear`、`/compact` 时变）→ 对话。导致缓存失效的操作：切换模型（每个模型独立缓存）、多数模型上改 effort、切 fast mode、增删非延迟加载的 MCP 工具、compaction、累积过多图片、升级 Claude Code。保持缓存的操作：编辑仓库文件、会话中途编辑 CLAUDE.md、改权限模式、调用 skills（作为消息追加）、`/rewind`、派生子代理 — [Claude Code 提示词缓存文档](https://code.claude.com/docs/en/prompt-caching)
- TTL：订阅用户在套餐额度内，主会话 1 小时，其余（子代理、compaction 等）5 分钟；API key 或云厂商默认都是 5 分钟，可以用 `promptCacheTtl` 设成 1h — [Claude Code 提示词缓存文档](https://code.claude.com/docs/en/prompt-caching)
- **跨会话范围**：Claude Code 的缓存实际上限定在「同一台机器 + 同一目录」，因为系统提示词嵌入了 auto memory 路径，开头还有工作目录、平台、shell、OS 声明。同目录并行会话可以互读缓存；顺序会话只有启动时的 git status 快照（分支、最近提交）相同才共享前缀 — [Claude Code 提示词缓存文档](https://code.claude.com/docs/en/prompt-caching)
- `/usage` 会显示「Prompt cache (main)」行：命中比例、未命中次数及可能原因（v2.1.251+） — [Claude Code 成本文档](https://code.claude.com/docs/en/costs)
- 长会话用量上涨的原因：每次请求都要重发全部历史（以缓存价重读）；休息超过 TTL 后首条消息未命中；compaction 本身也是大请求 — [Claude Code 成本文档](https://code.claude.com/docs/en/costs)

### Inferences
- **结构建议（适用于 Codex 和 Claude Code）**：稳定的项目规则放 AGENTS.md（每次运行都相同、在前缀里）；任务提示词只放任务专属内容，并放在用户消息里；任务提示词内部也把「跨任务复用的固定段落（例如通用验收流程模板）」放在开头、本任务细节放后面，这样同一天多次派发的任务之间可能共享更长的前缀（推断，前提是前面的环境上下文也完全一致）。
- **Codex CLI 跨会话**：OpenAI 缓存按组织 + 精确前缀，不绑定会话。Codex 前缀依次包含系统指令、工具、sandbox、环境上下文（含 cwd）和 AGENTS.md（据二手来源）。因此：同一 Codex 版本、同一工作树目录、相同 AGENTS.md 和 MCP 配置、在 TTL（GPT-5.6+ 至少 30 分钟）内启动的新会话，理论上能命中这一段前缀。不同 git worktree 的 cwd 不同，环境上下文就不同，前缀从那里开始分叉，只有 cwd 之前的部分（系统指令 + 工具）能共享。这是推断，没有官方确认，可用 `codex exec --json` 等输出的 cached_tokens 实测。
- 会话中途不要切换模型、reasoning effort（Claude Code 5.5 系列例外）或 MCP 配置。改 AGENTS.md 要在任务间隙改，不要在任务进行中改。
- 由于缓存读取只要 0.05–0.1×，「AGENTS.md 多几百行」的边际成本远小于「每次任务重新探索代码库」的成本。但它仍然占用注意力预算（见第 7 节），所以 AGENTS.md 精简的理由主要是质量，其次才是费用。

### Gaps
- 没能直接读取 OpenAI《Unrolling the Codex agent loop》原文（HTTP 403），所以 Codex 提示词顺序、压缩与缓存的交互只有摘录和二手来源。
- 没有找到 Codex CLI 的官方实测缓存命中率，也没有官方说明 Codex 订阅（ChatGPT 套餐）计费下缓存折扣如何体现在用量额度上。
- 《Lessons from building Claude Code: Prompt caching is everything》博客两次抓取失败（连接关闭），未能核实其中细节。

## 6. 频繁有意压缩（Frequent Intentional Compaction）/ Research-Plan-Implement（HumanLayer，Dex Horthy）与 12-factor agents；上下文利用率保持在 40–60%

### Takeaway
Dex Horthy 主张围绕上下文管理设计整个开发流程：把利用率保持在约 40–60%，把重上下文的工作（搜索、理解代码流）蒸馏成研究文档和计划文件，分 Research → Plan → Implement 三步，每一步用新上下文启动。人的审查精力应集中在研究和计划上（杠杆最高）。「~40% 之后进入 dumb zone」来自其后续演讲的二手摘要，数字本身没有严格依据。

### Cited Findings
- 「Frequent intentional compaction」：围绕上下文管理设计整个工作流，「keeping utilization in the 40%-60% range」（视问题复杂度而定）。压缩是把重上下文的内容蒸馏成结构化产物（进度文件、研究文档）；流程分为 research、plan、implement，可以跳过或重复，计划文件在每个验证通过的阶段后更新状态 — [HumanLayer ACE-FCA](https://github.com/humanlayer/advanced-context-engineering-for-coding-agents/blob/main/ace-fca.md)（约 2025 年 8 月下旬，作者推断为 Dex Horthy）
- 占用上下文的主要来源：搜索文件、理解代码流、应用编辑、测试/构建日志、工具返回的大 JSON — [HumanLayer ACE-FCA](https://github.com/humanlayer/advanced-context-engineering-for-coding-agents/blob/main/ace-fca.md)
- 上下文质量的优化目标是正确性、完整性、大小、轨迹。最坏的情况依次是：错误信息 > 缺失信息 > 过多噪声 — [HumanLayer ACE-FCA](https://github.com/humanlayer/advanced-context-engineering-for-coding-agents/blob/main/ace-fca.md)
- 审查杠杆：一行坏代码只是一行；计划里的一行坏内容可能导致数百行坏代码；研究里的一行坏内容可能导致数千行。所以人应该审研究和计划 — [HumanLayer ACE-FCA](https://github.com/humanlayer/advanced-context-engineering-for-coding-agents/blob/main/ace-fca.md)
- 报告结果：在 30 万行的 Rust 代码库（BAML）中，作者作为 Rust 新手提交的修复 PR 第二天即获批；和同事约 7 小时（3 小时研究和计划、4 小时实现）完成约 3.5 万行代码的取消与 WASM 支持（估算相当于高级工程师每个功能 3–5 天）；三人团队在 Opus 上每月约 $12k；也承认竞态条件、移除依赖（parquet-java）的尝试失败了 — [HumanLayer ACE-FCA](https://github.com/humanlayer/advanced-context-engineering-for-coding-agents/blob/main/ace-fca.md)
- 12-factor agents（Dex Horthy，约 2025 年 4 月）：Factor 3「Own your context window」（上下文工程入口），Factor 9「Compact Errors into Context Window」，Factor 10「Small, Focused Agents」——代理范围控制在「3-10, maybe 20 steps max」，因为「As context grows, LLMs are more likely to get lost or lose focus」 — [12-factor agents](https://github.com/humanlayer/12-factor-agents)、[Factor 10](https://github.com/humanlayer/12-factor-agents/blob/main/content/factor-10-small-focused-agents.md)
- 二手/观点：在后续演讲《No Vibes Allowed》（AI Engineer，2025 年 11–12 月）中，Horthy 把约 40% 利用率之后称为「dumb zone」。不同摘要给出的数字不一致（40%、40%/70%，有的把「dumb zone」一词归给 Geoffrey Huntley，也有说约 100k token） — [Recapio 摘要（二手）](https://recapio.com/digest/no-vibes-allowed-solving-hard-problems-in-complex-codebases-dex-horthy-humanlayer-by-ai-engineer)、[quevin 博客（二手）](https://quevin-com.pages.dev/blog/2025-12-13-context-engineering-smart-zone)

### Inferences
- 对本项目的派发模式，RPI 可以这样落地：(1) 研究阶段用只读子代理或单独会话产出研究文档（文件路径 + 结论，写入工作树）；(2) 计划由负责人审阅；(3) 实现会话只加载计划和必要规范，从干净上下文开始。长 Markdown 任务提示词本质上就是「计划」产物，所以它应该是经过压缩的计划，而不是原始资料的堆砌。
- 「40–60%」是经验法则，不是测量阈值。具体模型和任务应以第 7 节的退化证据为准，并自行观察（Claude Code 状态栏、`/context`）。
- 错误信息比缺失信息更坏：任务提示词中过时的说明（例如已改的接口路径）比不写更有害。提示词里引用的文档要保持更新，或者只给路径，让代理读最新版本。

### Gaps
- 「dumb zone ≈ 40%」没有找到原始实验数据，只有演讲摘要。
- 没有找到 RPI 与「单次长会话」在 token 成本上的对照测量。

## 7. Chroma「Context Rot」与长上下文退化证据（与任务开场塞入大量文档相关）

### Takeaway
Chroma 测试了 18 个模型：即使是简单任务，性能也随输入长度增加而下降；与问题语义相似度低的信息、看似相关的干扰项会放大退化；同样的信息，聚焦版（约 300 token）明显优于完整版（约 113k token）。结论：在任务开场灌入大量「可能相关」的文档，会同时增加成本和错误率。更好的做法是精选少量相关内容，其余给路径按需读取。

### Cited Findings
- Chroma 技术报告（Kelly Hong、Anton Troynikov、Jeff Huber，2025-07-14），测试 **18** 个 LLM（GPT-4.1、Claude 4、Gemini 2.5、Qwen3 等），约 **194,480** 次调用 — [Chroma](https://www.trychroma.com/research/context-rot)
- 模型「do not use their context uniformly」，输入越长可靠性越低，即使任务很简单 — [Chroma](https://www.trychroma.com/research/context-rot)
- needle 与问题的语义相似度越低，随长度退化越快。1 个干扰项就低于基线，4 个叠加更差，且影响随长度放大。Claude 模型幻觉最少（倾向弃答），GPT 模型幻觉最多 — [Chroma](https://www.trychroma.com/research/context-rot)
- LongMemEval：306 个提示，完整版平均约 **113k token**，聚焦版约 **300 token**，所有模型在聚焦版上得分都显著更高，Claude 的差距最大（主要来自弃答） — [Chroma](https://www.trychroma.com/research/context-rot)
- 反直觉发现：所有 18 个模型在打乱顺序的 haystack 上表现都好于连贯文本。作者明确说这不是可用技巧 — [Chroma](https://www.trychroma.com/research/context-rot)
- 重复词任务：输入和输出同时变长时性能下降，Gemini 2.5 Pro 在约 500–750 词时开始输出随机词 — [Chroma](https://www.trychroma.com/research/context-rot)
- Anthropic 也把 context rot 列为上下文工程的出发点：召回随 token 增加而渐进下降 — [Anthropic](https://www.anthropic.com/engineering/effective-context-engineering-for-ai-agents)
- Claude Code 文档建议 CLAUDE.md 控制在 200 行以内，专项说明移到按需加载的 skills — [Claude Code 成本文档](https://code.claude.com/docs/en/costs)
- 二手/观点：有第三方文章讨论「AGENTS.md 臃肿让代理更差」，以及 ICSE 2026 关于 AGENTS.md 降低 token 和运行时间的研究，但未核实原文 — [Daniel Vaughan 博客（二手）](https://codex.danielvaughan.com/2026/03/27/agents-md-bloat-problem/)、[同上](https://codex.danielvaughan.com/2026/08/07/agents-md-efficiency-impact-icse-2026-jaws-codex-cli-token-savings-runtime-reduction/)

### Inferences
- 规范文档中与本任务无关的章节，就是 Chroma 意义上「看似相关的干扰项」（同一项目、术语相同），危害可能大于完全无关的文本。任务提示词应只摘录本任务直接适用的条款，其余用路径引用。
- Chroma 的任务被刻意设计得很简单，真实编码任务的退化可能更严重（报告本身也这样提示）。这支持「先少后多、按需检索」，而不是「先全量后筛选」。
- 实操：任务提示词控制在「目标、范围、验收清单、相关文件路径、必须遵守的少数条款」之内；长背景材料放进仓库文件，让代理按需读。

### Gaps
- Chroma 的测试模型截至 2025 年中（GPT-4.1、Claude 4、Gemini 2.5），没有找到针对 2026 年模型（如 GPT-5.6+、Claude 5.5 系列）的同类复测，退化幅度可能已有变化。【可能过时】
- 没有找到专门测量「编码代理开场注入 N 份文档」对成功率影响的公开研究。
