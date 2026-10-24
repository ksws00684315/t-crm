# 任务 47B 前端本地检查结果

## 1022 后续简化调整

达人入口最终仅金额输入，抵扣选择移至财务待审核补录；申请和补录分别提交，未补齐禁用通过。当前管理端 frozen install、lint、typecheck、build、notices:check 通过；完整 Vitest 82 文件／523 用例通过，命令 `node node_modules/vitest/vitest.mjs run --maxWorkers=2`。构建 Vite 18.45 秒。新增最简申请无隐式抵扣、金额校验、财务补录 multipart／false 值与审批拦截／补录后可审批验证。达人端本次未改，不重复运行其既有检查。

本轮独立测试代理更新申请测试后触及额度限制；后续验证由主代理补完。默认并行初次有超时，限工作进程重新完整执行通过，未降低断言或修改超时门槛。已真实登录既有预览并截图核对申请弹窗，未提交申请，无新业务数据；新后端契约仍未实现。

## 原阶段检查

检查日期：2026-10-22。工作树：47-prepay-frontend。执行工具固定为 `npx --yes pnpm@10.33.4`。

| 检查 | admin-ui | kol-portal |
|---|---|---|
| install --frozen-lockfile（CI=true） | 通过 | 通过 |
| lint | 通过 | 通过 |
| typecheck | 通过 | 通过 |
| test（Vitest 默认完整执行） | 82 个文件、519 个用例全部通过；125.03 秒 | 25 个文件、183 个用例全部通过；66.67 秒 |
| build | 通过；Vite 10.39 秒 | 通过；Vite 14.28 秒 |
| notices:check | 通过 | 通过 |

Windows 默认 cmd 无法执行 notices:check 中的 POSIX mkdir/diff，因此以临时环境变量 `npm_config_script_shell=C:/Program Files/Git/bin/bash.exe` 执行原命令，未修改 package.json。两端原 THIRD-PARTY-NOTICES 与生成文件忽略 CR 后逐字相同；仅归一化原文件换行为 LF，最终原命令退出码 0。

新增验证覆盖：JSON Blob multipart、字符串 ID/金额和 UUID 保留、权限分离、撤回条件、金额与文件边界、申请和付款重试 UUID 不变、差额确认及必填说明、409 后刷新、初始详情深链、跨达人账户请求竞态、财务列表筛选分页与导出权限、达人申请入口、四类预付通知深链和权限、达人端付款结果/负余额/流水/错误，以及扣减流水缺少余额时不显示“钱包未变化”的误导说明。

同步更新了新增页面对应的精确路由/菜单/文案期待。硬编码文本测试只将 Windows 路径分隔符标准化为正斜杠，未增加豁免或降低断言。最初的导航期待、表单定位与错误展示等待问题已修正；最终完整执行无跳过或弱化测试。

上述为前端本地自动检查；真实后端 multipart/数据库/文件权限与完整业务联调不由这些模拟测试证明。
