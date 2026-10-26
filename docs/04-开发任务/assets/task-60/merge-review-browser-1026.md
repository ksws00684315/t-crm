# PR116 合并复审浏览器验收（2026-10-26）

使用真实登录和本任务已授权隔离预览，前端18961、后端18960；人工注入仅用于长身份、接口失败和空数据，不修改数据库。正常截图均等待网络空闲、骨架和加载遮罩消失及可编辑资料字段加载。

共14项检查，13通过；JS异常0；页面横向溢出0。

| 检查 | 结果 |
|---|---|
| 真实登录后刷新仍保持会话 | 通过 |
| zh-CN 1440 五入口、身份纵排与编辑字段 | 通过 |
| zh-CN 1280 五入口、身份纵排与编辑字段 | 通过 |
| zh-CN 390 五入口、身份纵排与编辑字段 | 通过 |
| zh-CN 375 五入口、身份纵排与编辑字段 | 通过 |
| zh-CN 2560 我的商品可进入 | 通过 |
| en-US 1440 五入口、身份纵排与编辑字段 | 通过 |
| en-US 1280 五入口、身份纵排与编辑字段 | 通过 |
| en-US 390 五入口、身份纵排与编辑字段 | 通过 |
| en-US 375 五入口、身份纵排与编辑字段 | 通过 |
| en-US 2560 我的商品可进入 | 通过 |
| 375长身份换行不遮挡编辑字段 | 通过 |
| 个人资料接口失败显示错误与重试 | 通过 |
| 商品空数据保留五入口及空状态 | 失败：缺空状态 |

## 截图

- [merge-review-account-1440x900-zh-CN.png](merge-review-account-1440x900-zh-CN.png)
- [merge-review-account-1280x800-zh-CN.png](merge-review-account-1280x800-zh-CN.png)
- [merge-review-account-390x844-zh-CN.png](merge-review-account-390x844-zh-CN.png)
- [merge-review-account-375x812-zh-CN.png](merge-review-account-375x812-zh-CN.png)
- [merge-review-items-2560x1440-zh-CN.png](merge-review-items-2560x1440-zh-CN.png)
- [merge-review-account-1440x900-en-US.png](merge-review-account-1440x900-en-US.png)
- [merge-review-account-1280x800-en-US.png](merge-review-account-1280x800-en-US.png)
- [merge-review-account-390x844-en-US.png](merge-review-account-390x844-en-US.png)
- [merge-review-account-375x812-en-US.png](merge-review-account-375x812-en-US.png)
- [merge-review-items-2560x1440-en-US.png](merge-review-items-2560x1440-en-US.png)
- [merge-review-account-long-375x812-en-US.png](merge-review-account-long-375x812-en-US.png)
- [merge-review-account-error-375x812-en-US.png](merge-review-account-error-375x812-en-US.png)
- [merge-review-items-empty-375x812-en-US.png](merge-review-items-empty-375x812-en-US.png)




## 既有问题

额外空数据检查发现：我的商品手机卡片在无数据时不显示空状态文案。页面的空状态仅位于桌面 DataTable 的 empty 插槽内；手机隐藏该表后，空的 item-list 仅留下分页。此问题同样存在于当前 main 的商品页，不由本轮合并引入。已报告负责人，未在浏览器子任务中修改产品源码。
