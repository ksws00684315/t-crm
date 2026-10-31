# 代码地图、代码索引与检索工具：让 AI 编码代理低成本熟悉大型代码库

> 调研日期：2026-10-28。适用对象：t-crm（Java 21 + Spring Boot 4.1 模块化单体，约 760 个 Java 文件、13 个模块；两个 Vue 3 + TS 前端，约 390 个文件），主要使用 OpenAI Codex CLI，其次 Claude Code。
> 项目约束：禁止 GPL/AGPL 依赖（AGENTS.md 第 8 条）；关注是否需要把代码发到第三方云。
> 证据等级说明：厂商自测 = 厂商自己跑、未独立复现；观点 = 作者个人言论；论文 = arXiv/会议论文。

## 1. Aider repo map：原理与效果

### Takeaway
Aider 用 tree-sitter 解析出全仓库的定义与引用，在"文件为节点、依赖为边"的图上做排序，只把最重要的类和函数签名塞进固定 token 预算（默认 1k，可动态放大）。官方没有公开任何量化的准确率或 token 节省数据，收益都是定性描述。

### Cited Findings
- repo map 是"整个 git 仓库的精简概览"，列出最重要的类、函数及其类型和调用签名，每次请求修改时都随请求一起发给 LLM — [Aider docs: Repository map](https://aider.chat/docs/repomap.html)
- 排序在图上完成："每个源文件是一个节点，有依赖关系的文件之间连边"；优先选"被代码其他部分引用最多"的标识符。官方文章写的是"图排序算法"，没有点名 PageRank — [Aider blog 2023-10-22: Building a better repository map with tree sitter](https://aider.chat/2023/10/22/repomap.html)
- token 预算：`--map-tokens` 默认 1k；通常不超过这个值，但聊天里还没加入文件、模型需要更大视野时会明显放大 — [Aider docs](https://aider.chat/docs/repomap.html)
- 早期用 universal-ctags，后来改成 tree-sitter，原因是：能拿到完整调用签名；通过 `py-tree-sitter-languages` 支持的语言更多；用户不用再单独装 ctags。使用的是各语言 tree-sitter 项目里 `tags.scm` 的修改版 — [Aider blog](https://aider.chat/2023/10/22/repomap.html)
- 两篇官方文章都没有给出 repo map 对基准分数的量化影响 — [Aider docs](https://aider.chat/docs/repomap.html)；[Aider blog](https://aider.chat/2023/10/22/repomap.html)

### Inferences
- Aider 的思路（tree-sitter tags + 图排序 + token 预算）已经成为"静态代码地图"的通用模式，可以在不依赖 Aider 的情况下自行复用（如 repomix `--compress`、各类 tree-sitter MCP）。
- 对 t-crm 这类边界清晰的模块化单体，全仓库图排序带来的边际收益可能不如人工维护的模块清单加 `api` 包索引（模块之间只允许依赖 `api` 包，依赖图本身已经很稀疏、可预测）。

### Gaps
- 没能核实 Aider 源码（`repomap.py`）中具体用的是哪种排序算法、有哪些个性化权重（例如聊天中的文件、被提到的标识符）。官方文章没写，本次也没读源码。
- 没找到独立评测（无 repo map 对比有 repo map）的数据。
- 本次未核实 Aider 的许可证（印象中是 Apache-2.0，未验证）。

## 2. 通过 MCP 暴露的 LSP/符号级工具：Serena、mcp-language-server、JetBrains MCP

### Takeaway
Serena 是最成熟的"符号级检索 + 编辑"MCP，支持 Java、TypeScript、Vue，并明确支持 Codex 和 Claude Code。**但它的主程序现在是 GPL-3.0-or-later**，与项目"禁止 GPL"的规则直接冲突，是否可以作为不随产品分发的开发工具使用，需要项目负责人决定。JetBrains IDE 从 2025.2 起内置 MCP server，可以接入 Codex/Claude Code，但需要 IDE 一直开着。所有 token 节省说法都是厂商定性描述，没有独立数据。

### Cited Findings
- **Serena 许可证**：SolidLSP（`src/solidlsp`）是 MIT；Serena 应用本身是 GPL-3.0-or-later，二者合并分发时整体受 GPL 约束；贡献需签 CLA — [oraios/serena README](https://github.com/oraios/serena)。`pyproject.toml` 写的是 `license = "GPL-3.0-or-later"`，版本 `2.1.1.dev0` — [serena pyproject.toml](https://raw.githubusercontent.com/oraios/serena/main/pyproject.toml)
- Serena v2（基于 REPL 的新代理接口）已发布；仓库约 30.8k star — [oraios/serena](https://github.com/oraios/serena)
- 语言：40 多种，包括 Java、TypeScript、Vue。Vue 支持 Vue 3.x + TS，需要 Node.js v18+，能处理 `.vue` 单文件组件，并能识别 monorepo；TypeScript 默认用 typescript-language-server，也可选 TS 7 原生服务（`typescript_tsgo`）— [Serena 语言支持文档](https://oraios.github.io/serena/01-about/020_programming-languages.html)
- 文档没有写明 Java 用的是哪个语言服务器，也没写 JDK、Maven/Gradle 导入要求和索引耗时 — [Serena 语言支持文档](https://oraios.github.io/serena/01-about/020_programming-languages.html)；`pyproject.toml` 里也没有 jdtls 相关依赖，只有一个 pytest 标记"java: language server running for Java" — [pyproject.toml](https://raw.githubusercontent.com/oraios/serena/main/pyproject.toml)
- 语言服务器依赖在首次激活项目时下载；内网受限时可以用 `serena download-ls-dependencies` 预先下载，升级 Serena 后要重新下载 — [Serena 语言支持文档](https://oraios.github.io/serena/01-about/020_programming-languages.html)
- 工具：检索类有 find symbol、symbol overview、find referencing symbols、find declaration、find implementations、diagnostics；符号编辑类有 replace symbol body、insert before/after symbol、safe delete；重命名两种后端都支持。在 Claude Code/Codex 这类自带工具的环境里，基础工具（search_for_pattern、read_file、execute_shell_command 等）通常是关闭的 — [oraios/serena](https://github.com/oraios/serena)
- 记忆系统可以跨会话共享知识，可以关闭，常和 AGENTS.md 搭配使用 — [oraios/serena](https://github.com/oraios/serena)
- 安装：`uv tool install -p 3.13 serena-agent`，然后 `serena init`（加 `-b JetBrains` 使用 JetBrains 后端）；客户端列表明确包括 Claude Code 和 Codex — [oraios/serena](https://github.com/oraios/serena)
- 已知局限：find declaration 对外部依赖一般失效；find implementations 只支持部分语言；LSP 后端没有 move、inline、propagate deletion；调试和类型层级需要 JetBrains 插件（**付费**，有试用期）— [oraios/serena](https://github.com/oraios/serena)
- token 节省说法只有"比常见替代方案省很多 token"之类的定性描述和代理"推荐语"，没有独立数据 — [oraios/serena](https://github.com/oraios/serena)
- **jdtls 通用情况**（非 Serena 专属）：jdtls 为每个项目维护一个工作区数据目录，首次索引要解析完整 classpath 并编译，大型 Maven/Gradle 项目可能要一分钟以上；运行服务器本身需要 JDK 17+ — [oh-my-opencode Java LSP setup 说明](https://cdn.jsdelivr.net/npm/oh-my-opencode@4.18.2/dist/skills/lsp-setup/references/java/README.md)。jdtls 曾修过"多模块 Maven 项目导入顺序导致大型模块集合加载失败"的问题 — [eclipse.jdt.ls PR #3150](https://github-redirect.dependabot.com/eclipse-jdtls/eclipse.jdt.ls/pull/3150)。有 Neovim 用户反馈加 `-Xms4g -XX:+UseParallelGC` 能改善大型 Java 项目启动（个人经验）— [r/neovim 讨论](https://redlib.hbubli.cc/r/neovim/comments/1hsw3zs/neovim_big_java_project_lame/mdc72e8/?context=3)
- **JetBrains 内置 MCP**：从 2025.2 起集成进 IDE，允许 Claude Code、Codex 等外部客户端调用 IDE 工具，可以自动写入客户端配置 — [IntelliJ IDEA Help: MCP Server](https://www.jetbrains.com/help/idea/mcp-server.html)。本次看到的官方工具列表片段包括文件检查、文本替换、符号文档，没有确认有"查找用法"工具；第三方插件"JetBrains IDE Index"提供 find references，支持 Claude Code/Codex — [IntelliJ IDEA 2025.3 Help](https://www.jetbrains.com/help/idea/2025.3/mcp-server.html)；[JetBrains IDE Index（第三方）](https://lobehub.com/mcp/hechtcarmel-jetbrains-index-mcp-plugin)

### Inferences
- **许可证判断**：Serena 是在开发者机器上运行的独立工具，不进 Maven/pnpm 依赖，也不随产品交付。按通常理解，GPL 的传染性只在"分发衍生作品"时触发，单纯本地使用不受影响。但 AGENTS.md 第 8 条写的是"禁止 GPL/AGPL 许可证"，没有区分"产品依赖"和"开发工具"，所以这属于规范歧义，应**停下来报告**，由负责人决定，而不是由代理自行认定可用。如果要零许可证风险，可选 MIT 的 SolidLSP 层或其他 MIT 工具，但需要自己封装。
- t-crm 用 Maven 多模块（backend、redirect），代码量中等（约 760 个文件）。jdtls 首次导入预计是分钟级而不是小时级，但每个 git worktree 都有独立的 jdtls 工作区（项目规定每个任务一个 worktree，放在 `.worktrees/`），会**重复冷启动**，这是并行多代理时的真实成本。
- 前提是项目能干净编译：jdtls 索引质量依赖编译成功，Spring Boot 4.1 / Java 21 需要 jdtls 版本足够新。
- JetBrains MCP 的前提是 IDE 打开着对应项目，更适合人在电脑前的交互式会话，不适合无人值守的 Codex 任务。

### Gaps
- 没能核实 Serena 是从哪个版本开始从 MIT（我的印象是早期 MIT）改成 GPL 的，也没核实旧版是否仍以 MIT 可用。
- Serena + Java（jdtls）在真实 Spring Boot 项目上的索引耗时、内存占用、Lombok/注解处理器兼容性：没找到 Serena 专属的报告（t-crm 禁用 Lombok，这一点反而有利）。
- 没找到 mcp-language-server（isaacphi）的许可证、维护状态和 Java 支持情况——本次没有检索。
- 没找到任何独立评测符号级 MCP 的 token 节省或准确率提升。

## 3. 语义/向量代码检索：Claude Context、Cursor、Augment、Sourcegraph/Amp、Greptile、Codex 自带行为

### Takeaway
方法最透明、证据最强的是 Cursor 的 A/B 结果：语义检索 + grep 比只用 grep 的问答准确率平均高 12.5%，但对实际代码保留率只提高 0.3%（1000 个文件以上的代码库提高 2.6%）。Zilliz "~40% token 节省"和 Augment "70%+ 提升"都是厂商自测，方法不完整。Codex CLI 本身没有语义索引，只靠文件模糊查找和 shell grep。所有向量方案都需要把代码（或嵌入）发到某个嵌入服务或向量库，除非全部用本地组件。

### Cited Findings
- **Zilliz Claude Context**：MIT 许可证；MCP 插件，为 Claude Code 等代理加语义代码检索；需要 Milvus 或 Zilliz Cloud 向量库；嵌入可选 OpenAI、VoyageAI、Ollama、Gemini；默认示例用 OpenAI key + Zilliz Cloud；用 Merkle 树做增量重建索引；明确支持 Codex CLI（`~/.codex/config.toml`）；语言列表包含 Java、TypeScript，**没有提到 Vue**；有 AST 切分器，失败时回退到字符切分 — [zilliztech/claude-context](https://github.com/zilliztech/claude-context)
- Claude Context 的"~40% token 节省，检索质量相当"在 README 中只给了一张图，方法放在 `evaluation` 目录，README 里没写样本量、任务、模型和基线 — [zilliztech/claude-context](https://github.com/zilliztech/claude-context)
- 对应的 Zilliz 博客（作者 Cheney Zhang，Zilliz 算法工程师，2025-08-26，2026-07-09 更新）说"token 用量下降 40% 以上，召回无损失"；文中的证据是一个找 bug 的案例（grep 版 Claude Code 约 5 分钟）加一次未说明细节的对比；没有给出数据集、任务数、模型、召回定义和原始 token 数；作者所在公司销售该工具依赖的向量库 — [Zilliz blog](https://zilliz.com/blog/why-im-against-claude-codes-grep-only-retrieval-it-just-burns-too-many-tokens)（厂商自测/观点）
- **Cursor**（2025-11-06）：离线评测用自建的 Cursor Context Bench，在所有主流模型上有/无语义检索各测一次；线上 A/B 同一模型，一组有语义检索，对照组只有 grep 类工具。结果：问答准确率平均 +12.5%（按模型 6.5%–23.5%）；代码保留率整体 +0.3%，1000 个文件以上的代码库 +2.6%；没有语义检索时，"不满意的追问"增加 2.2%。嵌入模型用代理会话轨迹训练（让 LLM 给每一步最有用的内容排序，再让嵌入相似度去拟合这个排序）。结论：grep 和语义检索结合效果最好 — [Cursor blog: Improving agent with semantic search](https://cursor.com/blog/semsearch)（厂商自测，但方法相对透明）
- **Augment Context Engine MCP**：2026-02 作为 MCP 对外开放（之后 GA），可以接入 Claude Code、Cursor、Codex — [Augment blog](https://www.augmentcode.com/blog/context-engine-mcp-now-live)；[SiliconANGLE 2026-02-06](https://siliconangle.com/2026/02/06/augment-code-makes-semantic-coding-capability-available-ai-agent/)。厂商说法：在 300 个 Elasticsearch PR、共 900 次尝试上，代理表现提升 70% 以上；Claude Code + Opus 4.5 提升 80%，Cursor + Opus 4.5 提升 71%；不同页面引用的模型版本不一致（产品页写 Opus 4.6）；没有找到 Codex 的单独数字 — [Augment changelog](https://www.augmentcode.com/changelog/context-engine-mcp-in-ga)；[Augment 产品页](https://augmentcode.com/product/context-engine-mcp)（厂商自测）。Augment 是云端索引服务（推断依据：产品形态，本次未核实其数据处理条款）
- **Codex CLI**：不内置结构化或语义代码索引，原生工具是文件模糊查找、shell grep 和网页搜索 — [Daniel Vaughan: Code Isn't Memory…（二手资料）](https://codex.danielvaughan.com/2026/07/01/code-isnt-memory-structural-codebase-index-coding-agent-codex-cli-mcp-localization-multi-file/)。GitHub issue #5181 有人提议用 OpenAI 嵌入做本地语义索引和 `codex search` 命令，未见官方承诺 — [openai/codex#5181](https://github.com/openai/codex/issues/5181)；社区通过 MCP 补位：CocoIndex Code、SymDex、GitNexus 等 — [Daniel Vaughan 2026-04-27（二手资料）](https://codex.danielvaughan.com/2026/04/27/codex-cli-semantic-code-search-cocoindex-symdex-gitnexus-codebase-navigation/)

### Inferences
- Cursor 的数据说明：语义检索对"回答关于代码的问题"帮助明显，但对"最终写出能保留的代码"整体帮助很小，只有在大型代码库（1000 个文件以上）才变得可观。t-crm 约 1150 个源文件（760 Java + 390 前端），刚好在这个门槛附近，而且分属 3 个独立工程，单个工程都在 1000 个文件以下。
- 项目代码交付给客户。用 Zilliz Cloud / OpenAI 嵌入 / Augment 意味着代码或代码嵌入要发到第三方云，需要客户或负责人同意。要合规，就只能用 Milvus 本地部署 + Ollama 本地嵌入，又会多出一套需要维护的本地中间件（规范 08 第 9 节要求不得改动共享中间件，新增中间件也需要报批）。
- Claude Context 没有声明支持 Vue，两个前端的 `.vue` 文件可能只能走字符切分回退。

### Gaps
- Claude Context `evaluation` 目录下的具体方法（数据集、任务数、模型）本次没有打开核实。
- Sourcegraph/Amp、Greptile、DeepWiki 的检索机制、许可证和数据去向：本次没有检索，没有可靠来源。
- Cursor 的嵌入计算在哪里进行、隐私模式细节：本次没核实（Cursor 不是本项目的主力工具，优先级低）。
- 没核实 Codex 官方文档或 changelog 是否在 2026 年下半年新增了原生语义检索；以上 Codex 结论来自二手博客和 issue，可能已经过时。

## 4. "代理式搜索 vs RAG"之争：各方说法与证据

### Takeaway
Claude Code（Boris Cherny）和 Cline 选择 grep/glob 加文件读取，理由是准确性、新鲜度、安全/隐私和简单。但这些主张都基于内部经验，没有公开对照实验。反方证据（Cursor A/B、Zilliz、Augment）大多是厂商自测。2025–2026 年的学术和工业证据趋向于：小中型代码库上 grep 足够，大型或多文件修改场景下结构化/语义索引有增益，**最好的做法是两者都作为工具交给代理**，而不是二选一。

### Cited Findings
- Boris Cherny 称 Claude Code 早期版本用过 RAG + 本地向量库，但很快发现代理式搜索总体效果更好；理由包括准确性、新鲜度（不用等重新嵌入）、安全/隐私（"索引总要放在某个地方"，对企业是负担）。证据是内部使用和内部基准，不是公开对照实验 — 二手转述：[smartscope.blog（日文原文）](https://smartscope.blog/ai-development/practices/rag-debate-agentic-search-code-exploration/)；[dev.to: the representation problem](https://dev.to/chen115y/the-representation-problem-why-rag-vs-agentic-search-is-the-wrong-debate-4j8f)（观点；没找到 X 原帖链接）
- 反方意见：代理式搜索比一次向量查找更耗 token、更慢 — 同上二手来源；Zilliz 博客标题直接写"grep 检索太烧 token" — [Zilliz blog](https://zilliz.com/blog/why-im-against-claude-codes-grep-only-retrieval-it-just-burns-too-many-tokens)（厂商观点）
- AI Engineer Europe 2026 的一场演讲（Kuba Rogut，"RAG is dead, right?"）的摘要指出：这些说法并不能证明语义检索不适合所有编码代理 — [AI Engineer Europe 2026](https://www.ai.engineer/talks/UM6sFg_jdlE-rag-is-dead-right-kuba-rogut)
- **Cline（2025-05）**：不为代码建向量索引。理由：嵌入是你最有价值 IP 的第二份副本，必须存放在某处（安全）；代码频繁变化，需要持续维护新鲜索引（新鲜度）；瓶颈已经不是上下文长度而是上下文质量，所以给代理工程师用的工具让它自己探索。HN 讨论中 Cline 的 Nick Baumann 承认"我们仍然在做检索，区别在于怎么检索"（ripgrep + tree-sitter AST）— [Cline blog](https://cline.bot/blog/why-cline-doesnt-index-your-codebase-and-why-thats-a-good-thing)；[HN 讨论镜像](https://brianlovin.com/hn/44106944)（观点）
- **Augment 前工程师 Colin Flaherty**（Jason Liu 转述）：他们登顶 SWE-bench Verified 的代理里"grep 和 find 就够了"，因为代理会反复换方式搜索直到找到；改进嵌入模型不一定改善端到端结果。但 SWE-bench 仓库比真实代码库小，大约 90% 的题目熟练工程师一小时内能做完；嵌入在大型代码库、非结构化内容、模型没见过的第三方代码等场景才重要。建议：把现有检索（包括嵌入检索）作为工具放进代理循环，并补充文件/目录摘要 — [jxnl.co 2025-09-11](https://jxnl.co/writing/2025/09/11/why-grep-beat-embeddings-in-our-swe-bench-agent-lessons-from-augment/)（观点，没有量化对比）
- **LocAgent（arXiv 2503.09089，ACL 2025）**：把代码库建成异构图（文件/类/函数 + 包含/调用/继承/导入关系）给代理导航，基线包括 BM25 和多个代码嵌入模型；在 SWE-Bench-Lite 上用 Claude-3.5 达到文件级 Acc@5 94.16%、模块级 Acc@10 87.59%、函数级 Acc@10 77.37%；论文认为稠密检索需要持续维护整个代码库的向量表示 — [LocAgent arXiv](https://arxiv.org/abs/2503.09089)（论文）
- **"Code Isn't Memory: A Structural Codebase Index Inside a Coding Agent"（arXiv 2606.22417，2026-06-21）**：固定使用 Claude Opus 4.7，在 SWE-PolyBench Verified 和 SWE-bench Pro 上跑 3 个种子，对比三组：有结构化索引、无索引、代理式 grep。摘要说：索引带来大幅定位提升和统计上可区分的解决率提升，单题成本没有增加、每个成功解决的成本更低；与代理式 grep 相比解决率和定位不退步，每个解决的成本更低；作者认为部署时关键在于"任务里是否有多文件修改，能让结构化排序发挥作用"。摘要没有给出具体数字 — [arXiv 2606.22417](https://arxiv.org/abs/2606.22417)（论文，未经同行评审）

### Inferences
- 两边的证据都弱：Anthropic/Cline 没公开数据；Cursor/Zilliz/Augment 都是卖索引能力的厂商。目前唯一同时对比"索引 vs 代理式 grep"并控制模型的公开研究是 arXiv 2606.22417，它的结论是"索引不吃亏，多文件修改时有收益"，而不是"大幅省 token"。
- 对 t-crm：代码命名规范、分层固定（controller/service/mapper/model/api），grep 的命中率天然很高（类名后缀 `*Controller`、`*Service`、`*DO`，接口路径是 kebab-case）。这正是"grep 足够"论点成立的典型场景。真正浪费 token 的往往不是"找不到"，而是"每个任务都重新读一遍 AGENTS.md、规范和模块结构"。这更适合用静态地图或文档解决，而不是向量索引。

### Gaps
- 没找到 Boris Cherny X 原帖的 URL 和准确原话，只有二手转述。
- arXiv 2606.22417 正文的具体数字（定位准确率、$/solve）本次没读。
- 没有检索 RepoCoder、CodeRAG-Bench 等早期仓库级检索论文；它们主要针对代码补全，不是代理场景，和本问题关系较弱。

## 5. 生成式静态代码地图：ctags、repomix、code2prompt、tree-sitter 摘要、llms.txt、DeepWiki、Spring Modulith Documenter，以及保鲜方式

### Takeaway
静态地图是最便宜、最可控、零云依赖的方案。repomix（MIT）可以把仓库压缩成 tree-sitter 骨架（自称约 70% token 节省），并提供 MCP 和 CI token 预算闸门。Spring Modulith 的 `Documenter` 能直接从模块模型生成 PlantUML 依赖图和模块画布，和 t-crm 的模块化单体结构天然契合。保鲜方式是在测试或 CI 中生成，并设 token 上限。

### Cited Findings
- **repomix**：MIT；`--compress` 用 tree-sitter 抽取关键代码元素，保留结构、减少 token，标注为"实验性功能"，自称约 70% token 节省（厂商说法，没有方法说明）；README 没写 `--compress` 支持哪些语言（Java、Vue 只出现在 `--remove-comments` 的语言列表里）— [yamadashy/repomix](https://github.com/yamadashy/repomix)
- repomix `--mcp` 可作为 MCP server（`pack_codebase`、`grep_repomix_output` 等工具）；`--sandbox` 只注册只读工具，禁止绝对路径和 `..`（应用层限制，不是 OS 沙箱）；默认用 `o200k_base` 计 token；`--token-count-tree` 显示 token 树；`--token-budget <N>` 超出上限时返回非零退出码，可用于 CI；默认用 Secretlint 排除疑似密钥文件 — [yamadashy/repomix](https://github.com/yamadashy/repomix)
- **Spring Modulith Documenter**：基于 `ApplicationModules.of(...)` 生成；`writeModulesAsPlantUml()` 生成包含所有模块的 C4 组件图，`writeIndividualModulesAsPlantUml()` 为每个模块生成它和直接依赖的图；`writeModuleCanvases()` 为每个模块生成 Asciidoc 画布（基础包、Spring 组件、事件监听器、配置属性；2.0 文档还列出发布的事件、聚合根等）；`writeAggregatingDocument()` 生成 `all-docs.adoc`；默认输出到构建目录下的 `spring-modulith-docs`；2.0 有 `DiagramOptions` / `DiagramStyle.UML` — [Spring Modulith Reference 2.0: Documentation](https://docs.spring.io/spring-modulith/reference/2.0/documentation.html)；[Documenter Javadoc](https://docs.spring.io/spring-modulith/docs/current/api/org/springframework/modulith/docs/Documenter.html)
- Aider 已经从 ctags 换成 tree-sitter，因为 ctags 拿不到完整签名，且需要单独安装 — [Aider blog](https://aider.chat/2023/10/22/repomap.html)
- Augment 前工程师 Colin Flaherty 建议给代理提供"文件和目录摘要"、异步 LLM 预处理 — [jxnl.co](https://jxnl.co/writing/2025/09/11/why-grep-beat-embeddings-in-our-swe-bench-agent-lessons-from-augment/)

### Inferences
- t-crm 已有 ArchUnit 类架构测试（`mvn verify` 包含"架构测试"）。在同一层测试里加一个 Spring Modulith `Documenter` 测试，就能在每次 `mvn verify` 时重新生成模块依赖图和模块画布，实现"零额外流程的保鲜"。但前提是项目已经（或愿意）引入 `spring-modulith-docs` 依赖：按规范 07 需要说明理由并固定稳定版本，且要确认它的版本与 Spring Boot 4.1 兼容。这属于"新增依赖"，需要报告。
- 更轻的替代：写一个小脚本（或 repomix `--compress --include 'backend/**/api/**'`），只导出每个模块 `api` 包的公开签名和 controller 路由，生成 `docs/` 下的代码地图，用 `--token-budget` 在 CI（目前 CI 暂停，可在审查人本地复跑时执行）中卡住体积。
- 注意 git hook 生成会让每次提交都出现无关 diff；生成到构建目录或在 CI 中校验"是否最新"，比提交生成物更符合"精准修改"原则。

### Gaps
- code2prompt、universal-ctags 的现状，以及 llms.txt 规范在代码仓库中的使用证据：本次没有检索。
- DeepWiki（Cognition）的生成方式、是否需要公开仓库或上传代码：本次没有检索，没有可靠来源。
- 没有找到"静态代码地图 vs 无地图"对代理 token 或准确率影响的独立评测。
- 没核实 Spring Modulith 哪个版本对应 Spring Boot 4.x（2.0 文档存在，兼容矩阵没查）。

## 6. 针对 Java/Spring 的实践建议模式（jdtls 索引、Maven/Gradle 导入）

### Takeaway
在 Java 上，LSP 类工具的主要成本和故障点在 jdtls：首次导入要解析完整 classpath（大型项目分钟级），依赖项目能干净编译，多模块 Maven 导入曾有 bug，每个工作区都有独立索引目录。对于 t-crm 这种规模中等、命名高度规范的代码库，性价比最高的顺序大概是：维护好 AGENTS.md 和模块地图（静态、零依赖）→ 必要时加符号级工具 → 最后才考虑向量索引。

### Cited Findings
- jdtls 每个项目一个工作区数据目录；首次索引要解析完整 classpath 并编译，大型 Maven/Gradle 项目可能要一分钟以上，查询前要等索引完成；服务器本身需要 JDK 17+；索引问题常常源于编译错误，应先确认项目能编译 — [oh-my-opencode Java LSP setup](https://cdn.jsdelivr.net/npm/oh-my-opencode@4.18.2/dist/skills/lsp-setup/references/java/README.md)
- jdtls 修复过多模块 Maven 导入顺序的问题（大型模块集合加载失败）— [eclipse.jdt.ls PR #3150](https://github-redirect.dependabot.com/eclipse-jdtls/eclipse.jdt.ls/pull/3150)
- Serena 的 find declaration 对外部依赖一般失效（例如跳进 Spring 或 MyBatis-Plus 源码）— [oraios/serena](https://github.com/oraios/serena)
- JetBrains 后端（付费插件）提供类型层级、搜索项目依赖、move/inline 等 LSP 后端没有的能力 — [oraios/serena](https://github.com/oraios/serena)
- 结构化索引的收益集中在多文件修改场景 — [arXiv 2606.22417](https://arxiv.org/abs/2606.22417)

### Inferences
- **worktree 成本**：t-crm 规定每个任务一个 worktree（`.worktrees/`）。jdtls 的工作区按项目目录区分，所以每个新 worktree 都要冷启动一次索引。并行 3–5 个代理就是 3–5 个 JVM，每个占几百 MB 到数 GB 内存。这在本机（Mac mini）上是实际约束。
- **Codex 场景**：Codex 任务常常无人值守、短时运行，jdtls 冷启动的时间和失败（索引未完成就查询，结果不完整）比收益更显著；Claude Code 的交互式长会话更容易摊薄成本。
- **推荐的分层方案（供报告撰写者参考，需负责人决策）**：
  1. 第 0 层（立即可做，零依赖）：在 AGENTS.md 或 `docs/` 中维护"模块地图"：13 个模块各自的职责、`api` 包里的关键接口、主要表、controller 路由前缀。手工或脚本生成，控制在约 2–4k token。这直接针对"每个任务重读结构"的浪费。
  2. 第 1 层（低成本）：用 repomix（MIT）`--compress` 按需生成某个模块的骨架，或用脚本从 `api` 包和 controller 生成签名清单，再用 `--token-budget` 卡体积。
  3. 第 2 层（需决策）：Spring Modulith `Documenter` 生成模块依赖图（需新增依赖、核对 Boot 4.1 兼容性）。
  4. 第 3 层（需决策，有许可证问题）：Serena（GPL-3.0）或 JetBrains MCP，用于跨模块重命名、查找引用这类 grep 容易出错的操作。
  5. 第 4 层（暂不建议）：向量索引（Claude Context / Augment）。代码量处于 Cursor 数据显示收益开始可观的门槛附近；需要把代码发到云，或额外部署 Milvus + Ollama；Vue 支持不明确。
- 前端（Vue 3 + TS）方面：typescript-language-server 加 Vue 语言服务的组合在 Serena 中已有支持，但 `.vue` 文件的符号级精度通常不如纯 `.ts`（推断，未核实）。

### Gaps
- 没有找到在 Spring Boot 项目上实测 Serena/jdtls 的公开报告（索引时长、内存、准确率）。
- 没有找到 Codex CLI 在 Java 项目上使用 LSP MCP 的实测 token 节省数据。
- 本次没有在 t-crm 本地实际跑 jdtls 导入或 repomix `--compress` 测量 token 数；如有需要，这是最直接的下一步验证（只在分配的工作树中运行，不写主目录）。
