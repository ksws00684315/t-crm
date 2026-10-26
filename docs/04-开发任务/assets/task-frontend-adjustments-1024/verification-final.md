# 前端调整1024：D3–D9基线验证结果

- 管理端：lint、typecheck、production build 全部通过；95个测试文件、677项通过。
- 达人端：lint、typecheck、production build 全部通过；31个测试文件、232项通过。
- Edge 浏览器矩阵：管理端中文/英文及1440、1280、2560宽度；达人端中文/英文及390、375宽度。包括有数据、空态、模拟API失败、权限、Token四状态、筛选和任务详情。
- API 全部由浏览器脚本拦截：管理端168条、达人端9条，无未预期请求或真实后端请求。唯一非GET请求为mock Token reveal，不写入业务数据。
- `generate-license-file` 校验因本机缺少可用 pnpm launcher（指向不存在的 pnpm.mjs）未运行；没有改依赖或 NOTICE 文件。

截图：

- [Token四状态与作废按钮](tokens-statuses-long-name-1440-zh-CN.png)
- [店铺更多菜单](shops-more-1440-zh-CN.png)
- [商品任务明细十列](copyjob-details-1440-zh-CN.png)
- [达人端账号身份](portal-account-long-name-390-zh-CN.png)

按 PR #122 审查要求，机器生成的逐请求 JSON 报告已删除；检查范围、请求数量和结论保留在本文，截图继续保留。订单列表、详情弹窗和链路的后续验收另见[2026-10-26 订单浏览器验收记录](order-browser-verification-1026.md)。
