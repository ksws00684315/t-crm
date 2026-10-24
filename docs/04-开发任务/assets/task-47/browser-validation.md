# 47B 浏览器 smoke 与真实联调阻塞记录

验证方式：Windows 本机 Edge headless，通过原生 CDP WebSocket 访问真实 Vite 服务；没有安装依赖、没有 mock API、没有注入登录态。管理端 127.0.0.1:8897，达人端 127.0.0.1:8898，真实后端代理目标 127.0.0.1:18087（当前没有后端监听）。

已检查：未登录访问管理端 `/fund/prepays` 与达人端 `/income/prepays`，跳转登录页并保留原路由 redirect。中文、英文；管理端 1440×900、1280×800、2560×1440；达人端 1440×900、1280×800、390×844、375×812。截图仅证明登录守卫、两端启动与登录页排版；不能证明预付业务页面、权限或业务操作通过。实际 URL、标题、页面文本与水平溢出结果见 `browser-smoke-results.json`。管理端验证码因真实后端不可达显示“加载失败，点击重试”/“Failed to load. Click to retry.”。

截图对应矩阵：`admin-unauth-prepays-{1440,1280,2560}-{zh,en}.png` 六张；`portal-unauth-prepays-{1440,1280,390,375}-{zh,en}.png` 八张。

真实联调阻塞：Docker Engine 29.8.2 可访问，但无运行中容器，无 tcrm-local 共享 PostgreSQL/Redis；55432、46391 无监听；主仓库 `deploy/.env.local` 不存在。git worktree list、git branch -a、git ls-remote --heads origin '*47*' 及 gh pr list 均未发现 47A 后端。main 仍为 00c06ed，未合入预付接口。因此所有真实业务角色、有数据/空数据/长内容、校验失败、业务接口失败、弹窗抽屉、支付与回执持久化矩阵均未完成，必须在 47A 合入且共享环境可用后补验。

未停止或修改现有预览、容器、中间件；未创建测试账号或业务数据，故没有数据库清理项。仅启动任务自身两个 Vite 进程及隔离用户目录 Edge，完成后关闭。
