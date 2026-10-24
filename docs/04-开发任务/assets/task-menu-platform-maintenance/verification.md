# 菜单平台维护入口调整：独立检查与浏览器复核

日期：2026-10-24。工作树：`D:/1-达人分销CRM/代码核对/t-crm/.worktrees/menu-platform-maintenance`。

## 检查结果

| 检查 | 结果 | 证据或说明 |
|---|---|---|
| frozen-lockfile 安装 | 通过 | pnpm 10.33.4；未修改依赖清单和锁文件 |
| 管理端 lint | 通过 | `lint.log`，ESLint 与 Prettier 全量通过 |
| 管理端 typecheck | 通过 | `typecheck.log`；build 也再次执行 vue-tsc |
| 管理端全量 test，单 worker | 部分通过，环境差异已复验 | 94 文件：93 通过、1 失败；673 测试：669 通过、4 失败；647.90s。4 项均是两个前端目录 CRLF/LF 字节差异，详见下文 |
| 换行差异专项复验 | 通过 | `tokens-recheck.log`，tokens.spec.ts 10/10 通过；无修改断言、测试超时或覆盖率门槛 |
| 管理端 build | 通过 | `build.log`；Vite 35.63s |
| notices | 通过 | 生成清单与现有 THIRD-PARTY-NOTICES 逐字一致，无新增产品依赖 |
| 浏览器菜单矩阵 | 通过 | Edge、独立前端端口 18867；6 个超级管理员画面与 3 个拒绝访问角色，详见 browser-results.json |

初始 Windows checkout 带 CRLF，首轮 lint 产生全目录换行错误；经主代理授权，仅将本工作树前端文本临时归一 LF。单 worker 全量测试的 4 个失败集中于 `src/style/__tests__/tokens.spec.ts`：tokens.css 字节一致、字体间距令牌一致、DataTable 与 CopyText 样式一致。将本树 kol-portal 对应 3 个比较文件临时归一 LF 后，该 spec 10 项全部通过。全部未改文本验证后恢复原 checkout CRLF，业务差异仍仅原 3 个前端文件。无全局或共享 Git 配置变更。

默认 Corepack 缓存遭遇 EXDEV，改用本树 node_modules 缓存安装；notices 工具内部调用 pnpm 命中了本机损坏 shim，改用本树临时 shim 指向已安装的同版本 pnpm。均为验证环境处理，未更改产品源码或仓库依赖。全量测试初轮并发存在资源争用，最终证据采用单 worker 轮次；未降低测试断言、超时和门槛。

## 需求逐条复核

| 需求原文 | 实现位置 | 是否满足 | 差异或说明 |
|---|---|---|---|
| 平台维护入口调整到商品管理的二级入口 | admin-ui/src/router/routes.ts 的 ProductGroup.children | 满足 | 第四项为 SystemLinkTemplates |
| 与店铺、TOKEN、商品任务并列，商品管理变为 4 个入口 | 同上；权限测试与 browser-results.json | 满足 | 超级管理员四项依次为店铺管理、Token管理、商品任务、平台维护；中文英文均验证 |
| 系统管理不重复显示平台维护 | SystemGroup.children；浏览器 DOM 检查 | 满足 | 系统菜单没有重复平台维护项 |
| 保留原页面、名称和旧路径 | SystemLinkTemplates，绝对路径 /system/link-templates | 满足 | 旧地址直达、从店铺页点击回平台维护均有效；面包屑归商品管理，菜单高亮正确 |
| 保持权限，不扩大角色可见性 | 原 meta.superAdmin 与现有 guard/permission 测试 | 满足 | 公司超级管理员可访问；平台管理员、运营和商务不可见该项，旧路径访问均 403 |
| 仅前端，不改后端、数据库、接口和达人端 | Git diff 与文件范围 | 满足 | 只有管理端路由及两份已有测试的预期修改；kol-portal 仅验证期间临时换行处理且已复原 |

## 浏览器验收范围与证据

这是**隔离 API 桩的前端模拟导航验收，不是真实后端联调**。所有 /admin-api 请求均在浏览器内拦截，使用 `t_menu` 虚构身份和空数据平台列表（共 0 条、暂无数据）；没有调用业务后端、没有写数据库、没有提交平台新增或编辑。页面标题明确带 mock API。

超级管理员中文/英文各验证 1440×900、1280×800、2560×1440：商品管理四个入口及顺序、平台维护高亮、商品管理/平台维护面包屑、旧路径直达和点击导航、系统无重复；六个画面均无 pageerror。平台管理员、运营、商务在 1440×900 中文下验证菜单不显示平台维护，直接输入旧地址均到 /403。

| 截图 | 检查项 |
|---|---|
| menu-platform-1440x900-zh-CN.png | 中文标准宽度，四项菜单、平台维护高亮、面包屑 |
| menu-platform-1280x800-zh-CN.png | 中文较小桌面宽度 |
| menu-platform-2560x1440-zh-CN.png | 中文宽屏 |
| menu-platform-1440x900-en-US.png | 英文标准宽度 |
| menu-platform-1280x800-en-US.png | 英文较小桌面宽度 |
| menu-platform-2560x1440-en-US.png | 英文宽屏 |
| menu-role-platform-1440x900-zh-CN.png | 平台管理员禁止访问 |
| menu-role-operator-1440x900-zh-CN.png | 运营禁止访问 |
| menu-role-bd-1440x900-zh-CN.png | 商务禁止访问 |

首次浏览器桩漏 /auth/me 完整身份响应，页面挂载刷新身份后导致菜单丢失；补全验证夹具后上述最终矩阵全部通过。该过程没有修改业务代码。最终 browser.log 和 browser-results.json 只保留成功轮次。现成 Chromium 二进制不可启动，使用本机已安装 Edge 完成验收。

本轮仅移动菜单，未变更表格、业务表单或浮层；未扩展验收未改的新增/编辑、长内容、接口异常和达人门户业务。达人不属于管理端登录角色，无新增管理端访问权限。真实后端接口与数据联调未执行。测试启动的 18867 前端服务已结束，未影响其他端口服务。

