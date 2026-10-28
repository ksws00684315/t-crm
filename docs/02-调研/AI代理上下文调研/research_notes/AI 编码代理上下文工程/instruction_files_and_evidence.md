# 代理指令文件分层、渐进式披露与量化证据（AGENTS.md / CLAUDE.md / Cursor rules / Skills）

> 调研日期：2026-10-28。所有官方文档为本次抓取的当前版本。早于 2025 年的资料已单独标注"可能过时"。博客 / X 观点标注为"实践者观点"。

## 问题 1：精确加载语义（Codex CLI、Claude Code、Cursor）

### Takeaway
三家工具的规则都是：根级文件在会话开始时全量加载，子目录文件按路径就近合并或按需加载。Codex 每次运行只构建一次指令链，合计上限 32 KiB，超出部分静默截断。Claude Code 的子目录 CLAUDE.md 和带 `paths` 的规则只在读写匹配文件时才加载；`@import` 只是把文件拆开组织，不会减少上下文占用。

### Cited Findings

**Codex CLI（OpenAI）**
- 全局层：读取 `~/.codex`（或 `CODEX_HOME`）。有 `AGENTS.override.md` 时优先用它，否则用 `AGENTS.md`；这一层只取第一个非空文件。— [Codex docs: AGENTS.md](https://learn.chatgpt.com/docs/agent-configuration/agents-md)（原 developers.openai.com/codex/guides/agents-md，308 重定向）
- 项目层：从项目根（通常是 Git 根）逐级往下走到当前目录。每个目录依次查 `AGENTS.override.md` → `AGENTS.md` → `project_doc_fallback_filenames` 列出的名字，每个目录**最多取 1 个文件**。找不到根时只查当前目录。— [同上](https://learn.chatgpt.com/docs/agent-configuration/agents-md)
- 拼接：按从根到当前目录的顺序用空行连接，越近的目录越靠后，因此能覆盖上层的宽泛指引；空文件跳过。— [同上](https://learn.chatgpt.com/docs/agent-configuration/agents-md)
- 大小上限：拼接总量达到 `project_doc_max_bytes` 就停止加载，**默认 32 KiB**（文档示例改为 `65536`）。文档给出的处理办法是调高上限，或把指令拆到嵌套目录。— [同上](https://learn.chatgpt.com/docs/agent-configuration/agents-md)
- 加载时机：**每次运行构建一次**指令链，TUI 中通常是每个会话一次。没有缓存，改了文件要重启 Codex 才生效。用 `-c log_dir=...` 可写出明文 TUI 日志，审计实际加载了哪些文件。— [同上](https://learn.chatgpt.com/docs/agent-configuration/agents-md)
- `project_doc_fallback_filenames` 示例：`["TEAM_GUIDE.md", ".agents.md"]`，不在列表里的文件名一律忽略。— [同上](https://learn.chatgpt.com/docs/agent-configuration/agents-md)

**Claude Code（Anthropic）**
- 工作目录及其上级目录中的 CLAUDE.md、CLAUDE.local.md 在启动时加载；子目录中的文件按需加载。— [Claude Code docs: memory](https://code.claude.com/docs/en/memory)
- 子目录的 CLAUDE.md / CLAUDE.local.md 不在启动时加载，而是在 Claude 对该子目录里的其他文件执行读、写或编辑时才加载。用 Bash 对单个文件 `cat` / `head` 也算读。— [同上](https://code.claude.com/docs/en/memory)
- `@path/to/import`：支持相对路径（相对于发起导入的文件）和绝对路径，递归导入最多 **4 跳**。代码块和反引号里的 `@` 不会被解析。路径含空格时，每个空格前要加反斜杠；用引号包住的路径不会被导入。— [同上](https://code.claude.com/docs/en/memory)
- **导入不省上下文**："Imports help you organize a long file but don't reduce its context cost"。被导入文件和发起导入的 CLAUDE.md 一起加载。— [同上](https://code.claude.com/docs/en/memory)
- `.claude/rules/*.md`（递归发现）：没有 `paths` frontmatter 的规则在启动时加载，优先级同 `.claude/CLAUDE.md`；有 `paths` glob 的规则在 Claude 用 Read/Write/Edit 处理匹配文件时才加载。`paths` 是唯一被读取的字段；YAML 解析失败时，规则会被当作无 `paths` 而全量加载。— [同上](https://code.claude.com/docs/en/memory)
- 用户级规则 `~/.claude/rules/` 先于项目规则加载，但两者没有覆盖关系："Claude may follow either one"。— [同上](https://code.claude.com/docs/en/memory)
- Auto memory：每个会话加载 MEMORY.md 的前 **200 行或 25KB**。— [同上](https://code.claude.com/docs/en/memory)
- AGENTS.md 兼容（v2.1.277+）：默认模式 `claude-md-or-agents-md` 下，工作目录或其上级**没有** CLAUDE.md / `.claude/CLAUDE.md` / CLAUDE.local.md 时才读 AGENTS.md。只要存在 `CLAUDE.local.md`，就不再读 AGENTS.md。Claude Code **不读** `AGENTS.override.md`、`AGENTS.local.md`，也不读 `.agents/` 下的任何内容。`CLAUDE.md` 中写 `@AGENTS.md` 不会导致重复读取。— [同上](https://code.claude.com/docs/en/memory)
- 若 CLAUDE.md 只是"用文字告诉 Claude 去读 AGENTS.md"，则 "Claude sees AGENTS.md only if it decides to open the file"；应改为 `@AGENTS.md` 导入。— [同上](https://code.claude.com/docs/en/memory)
- `claudeMdExcludes` 可按 glob 排除其他团队的祖先 CLAUDE.md（大 monorepo）。— [同上](https://code.claude.com/docs/en/memory)

**Cursor**
- 项目规则是 `.cursor/rules/` 下的 `.mdc` 文件（同目录的 `.md` 被忽略），frontmatter 有 `description`、`globs`、`alwaysApply` 三个字段。四种类型：Always Apply（每次对话都带）；Apply to Specific Files（globs 匹配的文件进入上下文时自动附加）；Apply Intelligently（只有 description，由 Agent 决定是否拉取）；Apply Manually（需 @ 提及）。— [Cursor docs: Rules](https://cursor.com/docs/context/rules)
- Cursor 也读嵌套 AGENTS.md，父子文件合并，更具体的优先。规则来源的优先级为 Team > Project > User，冲突时排在前面的来源胜出。— [同上](https://cursor.com/docs/context/rules)
- 被引用的文件不会内联进 prompt，由 Agent 用工具自行读取。— [同上](https://cursor.com/docs/context/rules)

**agents.md 开放格式**
- 嵌套规则："closest file to the edited file wins"，用户在对话中的显式指令覆盖一切。官方自述超过 6 万个开源项目在用，OpenAI 主仓库据称有 88 个 AGENTS.md。目前由 Linux Foundation 旗下的 Agentic AI Foundation 托管。— [agents.md](https://agents.md/)

### Inferences
- 对本项目（Codex 执行长 Markdown 任务提示词）的影响：Codex 的 32 KiB 上限只算 AGENTS.md 链，不包括用户 prompt。只要根 AGENTS.md 加上目录链超过 32 KiB，后面的内容就会**静默截断**。如果计划在 `backend/`、`admin-ui/` 等子目录增加 AGENTS.md，从仓库根启动的 Codex 不会加载它们，因为发现顺序是"根 → cwd"，而不是"根 → 被编辑的文件"。只有在子目录启动，或由 Codex 自行读取时才生效。这一点与 Cursor、agents.md 描述的"按被编辑文件就近"不同，需要实测确认（见 Gaps）。
- Claude Code 的按需加载是"触碰子目录文件时"触发，比 Codex 更细粒度。同一套嵌套文件在两种工具里生效的时机不同。
- 本项目现状是 `CLAUDE.md` 只写一行 `@AGENTS.md`，加上一个 `CLAUDE.local.md`。按文档，这样不会重复加载，也不会因 CLAUDE.local.md 的存在而漏读 AGENTS.md，因为 AGENTS.md 是通过导入加载的。
- 用 `@` 把 `docs/05-工程规范/` 导入 CLAUDE.md 不会节省 token。要省 token，只能用 path-scoped rules、子目录文件或 skills。

### Gaps
- Codex 运行中读取子目录文件时，是否会自动追加该子目录的 AGENTS.md？本次抓取的文档只写了"根 → cwd"，没有说明运行时的动态发现，需要用 `log_dir` 实测。
- 32 KiB 截断是否有警告提示，文档未写。
- Cursor 旧版 `.cursorrules` 的当前状态，本次抓取的页面未提及。

## 问题 2：Agent Skills 作为渐进式披露

### Takeaway
Skills 分三层加载：启动时只注入 name + description（Codex 还会带上路径），判断相关时才读取完整 SKILL.md，附属文件再按需读取。Codex 给初始技能清单的上限是上下文窗口的 2%，未知窗口时为 8,000 字符。官方建议：每个会话都需要的事实放 AGENTS.md / CLAUDE.md；多步流程或只与部分代码相关的知识放 skill 或 path-scoped rule。

### Cited Findings
- 三层加载：Level 1 "pre-loads the `name` and `description` of every installed skill into its system prompt"；Level 2 在相关时 "reading its full `SKILL.md` into context"；Level 3+ 按需读取附属文件。文章发布于 2025-10-16；2025-12-18 更新称 Agent Skills 已作为开放标准发布。— [Anthropic Engineering: Equipping agents with Agent Skills](https://www.anthropic.com/engineering/equipping-agents-for-the-real-world-with-agent-skills)
- Anthropic 建议先在代表性任务上运行代理，找出卡住的地方再写 skill。确定性操作应放进脚本，因为比让模型生成更可靠、更省。name / description 决定是否触发，需要认真写。只从可信来源安装 skill。— [同上](https://www.anthropic.com/engineering/equipping-agents-for-the-real-world-with-agent-skills)
- Codex skills 目录结构：`SKILL.md` 必须包含 `name` 和 `description`，可选 `scripts/`、`references/`、`assets/`、`agents/openai.yaml`。发现位置有四处：仓库 `$CWD/.agents/skills` 及其上级直到 `$REPO_ROOT/.agents/skills`；用户 `$HOME/.agents/skills`；管理员 `/etc/codex/skills`；系统内置。— [Codex docs: Build skills](https://learn.chatgpt.com/docs/build-skills)
- Codex 调用方式：显式用 `/skills` 或 `$skill-name`；隐式按 description 匹配，可用 `policy: allow_implicit_invocation: false` 关闭。初始技能清单最多占上下文窗口的 **2%**（未知窗口时 **8,000 字符**）；超出时先压缩 description，再省略部分技能并给出警告。可用 `skills.max_context_tokens` 调整，最高 10,000 tokens。— [同上](https://learn.chatgpt.com/docs/build-skills)
- Claude Code 官方分工："If an entry is a multi-step procedure or only matters for one part of the codebase, move it to a skill or a path-scoped rule"。CLAUDE.md 只放每个会话都要用的事实：构建命令、约定、布局、"always do X"。— [Claude Code docs: memory](https://code.claude.com/docs/en/memory)
- "A long release procedure sitting in a skill doesn't weigh on your unrelated bug fix the way it would in CLAUDE.md"。有副作用的流程设 `disable-model-invocation: true`，只允许人工 `/release` 触发。— [Claude Code docs: best practices](https://code.claude.com/docs/en/best-practices)
- 规则文件在每个会话或打开匹配文件时加载；"For task-specific instructions that don't need to be in context all the time, use skills"。— [Claude Code docs: memory](https://code.claude.com/docs/en/memory)

### Inferences
- 本项目 `docs/05-工程规范/` 里的流程性规范（迁移脚本、PR 流程、浏览器验收、preview.sh）适合做成仓库级 skill，放在 `.agents/skills/`，Codex 能发现。Claude Code 的 skills 位置是 `.claude/skills/`，两者位置不同，要么双放，要么用符号链接（Codex 跟随 symlink 已有文档确认）。
- 渐进式披露依赖 description 写得准。如果 description 模糊，Codex 隐式调用就不可靠，此时应在任务提示词里显式写 `$skill-name`。

### Gaps
- Claude Code 的 skill description 总预算（字符或 token 上限）本次未抓取原文。
- 没有找到"skills 和 AGENTS.md 全文相比节省多少 token、提升多少成功率"的量化对照研究。

## 问题 3：写什么、不写什么（厂商与实践者指引）

### Takeaway
各方共识：只写代理读代码推不出来的内容，例如命令、非显而易见的约定、禁止事项；不写架构概览，不写代码片段，不把代理当 linter。长度方面，Claude Code 建议每个文件 < 200 行，Cursor 建议规则 < 500 行，HumanLayer 建议 < 300 行（其自身根文件 < 60 行）。必须 100% 执行的规则应交给 hook、CI 或 linter，而不是加大写的 IMPORTANT。

### Cited Findings
- 官方准则："Only write down what Claude can't find by reading the repo, or what it would otherwise spend time rediscovering every session." — [Claude Code docs: best practices](https://code.claude.com/docs/en/best-practices)
- "every line in it takes a little of Claude's attention on *every* task, whether it's relevant or not"；用 `/init` 生成初稿后，"the work is mostly removing lines"，并保持在 200 行以下。— [同上](https://code.claude.com/docs/en/best-practices)
- 反模式表：不要在 CLAUDE.md 里描述架构和文件布局，因为 "Claude reads the repo itself"；不要给规则加 IMPORTANT 或全大写，因为现代模型 "piling on emphasis mostly makes Claude over-apply the rule"；不要写"keep going / don't be lazy"之类的话，这更可能导致过度工作。— [同上](https://code.claude.com/docs/en/best-practices)
- "If you keep adding emphasis to a CLAUDE.md line and Claude still misses it, that's a sign the line belongs in a hook." 必须 100% 执行的规则用 hook、deny rule 或 managed setting；CLAUDE.md 是 guideline，"Claude is allowed to decide a guideline doesn't apply"。— [同上](https://code.claude.com/docs/en/best-practices)
- 长度：每个 CLAUDE.md 文件 < 200 行，"Longer files consume more context and reduce adherence"。两条指令互相矛盾时，"Claude may pick one arbitrarily"。`/doctor prompt-audit` 可审计指令文件。— [Claude Code docs: memory](https://code.claude.com/docs/en/memory)
- Cursor：规则 < 500 行；大规则拆成可组合的小规则；引用文件而不是复制内容，以免过时；不复制完整风格指南（交给 linter）；不写常用命令和罕见边界情况；从简单开始，代理重复犯同一个错时再加规则。— [Cursor docs: Rules](https://cursor.com/docs/context/rules)
- HumanLayer（2025-11-25，实践者观点）：< 300 行，自家根文件 < 60 行。前沿推理模型大约能稳定遵循 150–200 条指令，其中 Claude Code 系统提示自身约占 50 条（作者估计）。Harness 会把 CLAUDE.md 包在 "may or may not be relevant" 的提醒里，因此与多数任务无关的内容更容易被忽略。建议的做法：用 `agent_docs/` 目录做渐进式披露，CLAUDE.md 只列文件名和一句话描述；用 `file:line` 引用替代代码片段；不把 LLM 当 linter，改用 Stop hook 跑格式化；不用 `/init` 自动生成。— [HumanLayer: Writing a good CLAUDE.md](https://www.humanlayer.dev/blog/writing-a-good-claude-md)
- Anthropic 上下文工程（2025-09-29）：追求 "the smallest possible set of high-signal tokens"。先写最小提示，再根据观察到的失败逐步补充。Claude Code 是混合模式：CLAUDE.md 预加载，glob/grep 即时检索。子代理在干净上下文中工作，返回约 1,000–2,000 tokens 的摘要。— [Anthropic Engineering: Effective context engineering](https://www.anthropic.com/engineering/effective-context-engineering-for-ai-agents)
- agents.md 推荐的章节：项目概览、构建与测试命令、代码风格、测试说明、安全注意事项，可加 PR 规范、部署步骤；定位为 "living documentation"。注意：其中"项目概览"与下文 ETH 研究的结论相冲突。— [agents.md](https://agents.md/)
- Simon Willison 在 2025-10-25 转述 Anthropic 文档：在 CLAUDE.md 里用 `@AGENTS.md` 保持单一来源（属于转述官方，不是他本人的建议）。— [Simon Willison 2025-10-25](https://simonwillison.net/2025/Oct/25/claude-docs)

### Inferences
- 本项目 AGENTS.md 采用"规则摘要 + 指向 `docs/05-工程规范/`"的结构，方向与官方及 HumanLayer 一致。但它是文字指针，不是导入：Codex 只有在主动打开规范文件时才能看到细则，和"在 CLAUDE.md 里文字告诉 Claude 去读"的行为一样，读不读由模型决定。如果某条细则每个任务都必需，应写进 AGENTS.md 正文或任务提示词；只与部分任务相关的，应在任务提示词中显式点名要读的规范文件和章节。
- 规范中可以机器检查的条目（通配符导入、Lombok、`System.out`、Flyway 命名）已由 Spotless 和架构测试覆盖，可以考虑从 AGENTS.md 中删去，只保留"运行 `mvn verify`"这一条命令，与 Cursor、HumanLayer 的"不当 linter"建议一致。

### Gaps
- Armin Ronacher、Mitchell Hashimoto、Builder.io 关于 AGENTS.md 的专门文章，本次搜索没有找到原文，未纳入。
- 没有找到 OpenAI 官方给出的 AGENTS.md 行数或长度建议，除了 32 KiB 这一硬上限。

## 问题 4：量化证据

### Takeaway
目前只有两项直接对照研究，结论方向不一致。一项是 ETH/LogicStar（ICLR 2026）：上下文文件对成功率没有显著提升，推理成本增加约 20%，LLM 生成的文件比开发者手写的差约 7 个百分点。另一项是 Lulla 等（ICSE JAWs 2026，仅 Codex）：有 AGENTS.md 时，中位运行时间降低 28.64%，输出 token 降低 16.58%，但没有测正确性。两者都基于 Python 或小改动样本，外推需谨慎。

### Cited Findings

**Gloaguen 等，"Evaluating AGENTS.md: Are Repository-Level Context Files Helpful for Coding Agents?"（ETH Zurich + LogicStar.ai，arXiv 2602.11988，ICLR 2026）**
- 设置：SWE-bench Lite 300 个任务（11 个仓库，使用 LLM 生成的上下文文件），加上新建基准 138 个任务（12 个带开发者提交上下文文件的仓库，从 5,694 个 PR 中筛出）。代理与模型：Claude Code + Sonnet-4.5、Codex + GPT-5.2、Codex + GPT-5.1 mini、Qwen Code + Qwen3-30b-coder。— [arXiv HTML](https://arxiv.org/html/2602.11988)
- 成功率：LLM 生成的文件平均 −0.5%（SWE-bench，p=0.87）和 −2%（新基准，p=0.37），均不显著。开发者手写文件相对无文件 +2.4%（p=0.21），也不显著。开发者手写相对 LLM 生成约 +7%（p=0.038），显著。用更强的模型生成文件也没有带来改善。— [arXiv HTML](https://arxiv.org/html/2602.11988)
- 成本：LLM 生成的文件使步数增加 2.45 和 3.92，成本增加约 20% 和 23%，均显著。开发者手写文件使步数增加 3.34，成本增加最多 19%。摘要原话："increasing inference cost by over 20%"。— [arXiv HTML](https://arxiv.org/html/2602.11988)
- 机制：
  - 代理确实遵循指令。文件中提到 `uv` 时，每个实例平均使用 1.6 次，未提到时不到 0.01 次；文件中提到的仓库专用工具使用 2.5 次，未提到时不到 0.05 次。
  - 代理测试更多、搜索和读取的文件更多。推理 token 增加，GPT-5.2 上 LLM 生成文件 +22%，开发者手写文件 +20%。
  - **代码库概览没有帮助**：12 份开发者文件中有 8 份含概览，但"首次触达相关文件之前的步数"没有明显下降。
  — [arXiv HTML](https://arxiv.org/html/2602.11988)
- 消融实验：文件长度与成功率、成本均无明显相关。删除"测试"章节显著降低成本（p=0.023 / 0.0035），准确率不变。删除仓库文档后，LLM 生成的文件反而提升 2.7%，作者据此推断上下文文件主要在文档缺失时有用。— [arXiv HTML](https://arxiv.org/html/2602.11988)
- 作者建议：暂时不用 LLM 生成的上下文文件；手写文件只写 README 里没有的内容，例如特定约定和非功能需求；采用前要严格评估。局限：只覆盖 Python，只测了解决率。— [arXiv HTML](https://arxiv.org/html/2602.11988)
- **版本差异提示**：搜索摘要和 ICLR 页面引用的版本把基准称为 "AGENTbench"，并写开发者手写文件约 +4%；当前 arXiv HTML 称 "CTXbench"，数值为 +2.4%（p=0.21）。上文以当前 arXiv HTML 为准。— [ICLR 2026 页面](https://iclr.cc/virtual/2026/10021235)；[arXiv HTML](https://arxiv.org/html/2602.11988)

**Lulla 等，"On the Impact of AGENTS.md Files on the Efficiency of AI Coding Agents"（arXiv 2601.20404 v2，2026-03-30；ICSE JAWs 2026 workshop，5 页）**
- 设置：10 个仓库、124 个 PR。只选根目录恰有一个 AGENTS.md 的仓库；PR 改动 ≤100 行、≤5 个文件、只改代码。Codex CLI + gpt-5.2-codex；配对设计，有和无 AGENTS.md 各跑一次，每次使用全新的 Docker 环境。许多任务描述由 gpt-oss-120b 从 PR diff 生成。— [arXiv 2601.20404v2](https://arxiv.org/html/2601.20404v2)
- 结果（中位数）：
  - 墙钟时间 98.57s → 70.34s，**降低 28.64%**，显著（Wilcoxon，p<0.05）。
  - 输出 token 2,925 → 2,440，**降低 16.58%**，显著。
  - 输入 token 的中位数略升 3.41%，总 token 中位数约持平（+1.29%），均不显著。
  - 均值降幅：时间 −20.27%，输出 token −20.08%，总 token −9.93%。
  — [同上](https://arxiv.org/html/2601.20404v2)
- 局限：只用了单一代理和单一模型；没有评估正确性；只做关联分析，"减少探索"的机制属于推测；没有报告效应量和多重比较校正；按 10×15 应有 150 个 PR，实际 124 个，差额未解释。— [同上](https://arxiv.org/html/2601.20404v2)

**Chatlatanagulchai 等，"Agent READMEs: An Empirical Study of Context Files for Agentic Coding"（arXiv 2511.12884，2025-11）**
- 分析了 1,925 个仓库中的 2,303 个上下文文件（Claude Code、Codex、Copilot）。59%–67% 的文件经历过多次修改。"System Overview" 出现在 59.0% 的文件中。属于描述性研究，没有测量效果。— [arXiv 2511.12884](https://arxiv.org/html/2511.12884v1)（本次只读了摘要级信息，未读全文）

**指令数量与长上下文的相关研究**
- IFScale（Jaroslawicz 等，arXiv 2507.11538，2025-07）：测试了 7 家厂商的 20 个模型，指令密度最高到 500 条；最好的前沿模型在 500 条指令下也只有 68% 准确率；模型偏向遵循靠前的指令。HumanLayer "150–200 条"的说法就引自这篇。— [arXiv 2507.11538](https://arxiv.org/abs/2507.11538)
- Chroma "Context Rot"（2025-07-14）：测试 18 个模型，"model performance consistently degrades with increasing input length"，且退化不均匀。问题与答案语义相似度越低，退化越快。干扰项越多越差。逻辑连贯的 haystack 反而比打乱的更差。LongMemEval 中，聚焦 prompt（约 300 tokens）的得分一致高于完整 prompt（约 113k tokens）。— [Chroma Research: Context Rot](https://www.trychroma.com/research/context-rot)

### Inferences
- 两项研究可以调和：ETH 测的是成功率和成本，对象是 Python 基准任务和较强模型；Lulla 测的是 Codex 在小 PR 上的速度。合在一起看，AGENTS.md 让代理更快收敛，但不一定更对；而且内容一旦让代理"多测、多探索"，成本就会上升。对本项目的直接启示：
  1. 不要用 `/init` 或 LLM 生成 AGENTS.md；
  2. 去掉代码库概览；
  3. 测试指令只写"跑哪个命令"，不写"多跑测试"，因为删除测试章节可以降成本且不损准确率；
  4. 任何改动都应在本项目自己的任务上做有和无的对照，至少统计 token 和墙钟时间。
- 本项目的特殊性：业务规则多、非 Python、有大量 README 推不出来的约定（tenant_id、Flyway 命名、信封格式）。这正是 ETH 所说"手写、只写非显而易见内容"会生效的场景，但目前没有直接证据。
- IFScale 和 Context Rot 都提示：AGENTS.md 加长篇任务提示词加规范文件，总指令数可能远超 150 条。靠前的指令被优先遵循，因此最关键的禁令应放在靠前位置，或改由 CI、hook 执行。

### Gaps
- 没有找到针对 Java / Spring 或大型业务单体的 AGENTS.md 对照实验。
- 没有找到量化比较"skills / 渐进式披露 vs 全文 AGENTS.md"的研究。
- Lulla 研究的 124 次配对运行是否重复多次以控制随机性，文中不明。

## 问题 5：反模式与失效模式

### Takeaway
主要失效模式有六类：文件膨胀（不相关内容拖低整体遵循率）；规则矛盾（模型任意挑一条）；靠强调词补救；把确定性规则交给模型执行；代码片段与概览过时；长上下文退化（context rot、前部偏好）。对应的修复都是三件事：缩短、拆分到按需加载层、把硬规则移到工具执行。

### Cited Findings
- 膨胀：文件越长，遵循度越低，"Either the file has grown long enough that the line gets lost, or the line is a rule that can't have exceptions"。— [Claude Code docs: best practices](https://code.claude.com/docs/en/best-practices)；"Longer files consume more context and reduce adherence"。— [Claude Code docs: memory](https://code.claude.com/docs/en/memory)
- 矛盾：同层冲突时 "Claude may pick one arbitrarily"；用户规则与项目规则冲突时 "Claude may follow either one"。— [Claude Code docs: memory](https://code.claude.com/docs/en/memory)
- 不相关内容导致整份文件被忽略（实践者观点）。— [HumanLayer](https://www.humanlayer.dev/blog/writing-a-good-claude-md)
- 过时：代码片段很快过时，应用 `file:line` 引用（实践者观点）。— [HumanLayer](https://www.humanlayer.dev/blog/writing-a-good-claude-md)；Cursor 官方也建议引用文件而不是复制内容。— [Cursor docs: Rules](https://cursor.com/docs/context/rules)
- LLM 生成的上下文文件让成本增加约 20%，成功率不升反降（不显著）。— [arXiv 2602.11988](https://arxiv.org/html/2602.11988)
- 文件本身会不断增长：59%–67% 的上下文文件被多次修改。— [arXiv 2511.12884](https://arxiv.org/html/2511.12884v1)
- 静默截断：Codex 超过 32 KiB 后停止加载后续文件。— [Codex docs](https://learn.chatgpt.com/docs/agent-configuration/agents-md)
- 前部偏好：模型偏向遵循靠前的指令。— [IFScale](https://arxiv.org/abs/2507.11538)；长输入导致性能下降，干扰项会叠加。— [Chroma Context Rot](https://www.trychroma.com/research/context-rot)
- 过度执行 hook 也是一种反模式："If you ever find yourself at forty of them, most are probably preferences"。— [Claude Code docs: best practices](https://code.claude.com/docs/en/best-practices)

### Inferences
- 本项目有三处潜在风险：
  1. AGENTS.md 声明"冲突时以工程规范为准"，但 Codex 不一定会读规范。一旦两者不一致，代理很可能只按 AGENTS.md 执行。
  2. 长任务提示词与 AGENTS.md 重复相同规则，增加 token，同时制造不一致的机会。
  3. 多个 `docs/05` 规范之间如有矛盾，模型会任意选择一条。建议定期做一次一致性审计（Claude Code 有 `/doctor prompt-audit`）。

### Gaps
- "Lost in the Middle"（Liu 等，arXiv 2307.03172，2023，早于 2025，可能过时）本次未抓取原文，未作为引用依据。其"中段信息利用较差"的结论在新模型上是否仍然成立，需看 Chroma 等更新的研究；Chroma 报告称针的位置"no notable effect"，与该经典结论不完全一致。
- 没有量化研究专门测量"规则矛盾"对代理成功率的影响。
