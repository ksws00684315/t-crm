# PR116 最新主线提现契约浏览器冒烟（2026-10-26）

真实后端为含#127的main，前端18961、后端18960，沿用负责人已授权的隔离环境。只使用t60_人工测试数据。完成申请后真实撤回，恢复可提余额2000、占用0。

- 最新后端健康与真实登录刷新
- 条件version和unverifiedCount为数字
- 真实提现列表使用amount与account.id
- 资金页真实金额无NaN且驳回重提预填100
- 真实前端申请及响应使用amount/account.id
- 真实撤回后金额占用释放
- 375资金页无金额异常和页面横溢

浏览器JS异常：0。

- [merge-review-funds-contract127-1440x900-en-US.png](merge-review-funds-contract127-1440x900-en-US.png)
- [merge-review-funds-contract127-375x812-en-US.png](merge-review-funds-contract127-375x812-en-US.png)
