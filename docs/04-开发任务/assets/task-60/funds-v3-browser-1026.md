# 达人端资金概览 V3 浏览器验收（2026-10-26）

真实隔离预览为18961/18960，正常数据使用已有t60_人工夹具；未改数据库。正常10个尺寸/语言状态均等待网络空闲、骨架和遮罩消失。注入数据仅通过浏览器拦截明确构造，退出后撤销。提现仅打开真实条件弹窗，未提交申请。

共19项，19通过，0失败；JS异常0；页面横向溢出0。

| 检查 | 结果 |
|---|---|
| 真实数据 1440x900-zh-CN | 通过 |
| 真实数据 1280x800-zh-CN | 通过 |
| 真实数据 390x844-zh-CN | 通过 |
| 真实数据 375x812-zh-CN | 通过 |
| 真实数据 2560x1440-zh-CN | 通过 |
| 真实数据 1440x900-en-US | 通过 |
| 真实数据 1280x800-en-US | 通过 |
| 真实数据 390x844-en-US | 通过 |
| 真实数据 375x812-en-US | 通过 |
| 真实数据 2560x1440-en-US | 通过 |
| 注入 zero-income 375英文 | 通过 |
| 注入 no-received-advance 375英文 | 通过 |
| 注入 negative-and-deduction 375英文 | 通过 |
| 注入 long-amount 375英文 | 通过 |
| 注入 接口失败 375英文 | 通过 |
| 注入 空历史 375英文 | 通过 |
| 真实提现条件弹窗 1440英文 | 通过 |
| 375中文真实滚动后提现中与抵扣均在底栏上方可读 | 通过 |
| 可提现正常蓝色、负可提现与抵扣红色符合共享令牌 | 通过 |

## 截图

- [funds-v3-1440x900-zh-CN.png](funds-v3-1440x900-zh-CN.png)
- [funds-v3-1280x800-zh-CN.png](funds-v3-1280x800-zh-CN.png)
- [funds-v3-390x844-zh-CN.png](funds-v3-390x844-zh-CN.png)
- [funds-v3-375x812-zh-CN.png](funds-v3-375x812-zh-CN.png)
- [funds-v3-2560x1440-zh-CN.png](funds-v3-2560x1440-zh-CN.png)
- [funds-v3-1440x900-en-US.png](funds-v3-1440x900-en-US.png)
- [funds-v3-1280x800-en-US.png](funds-v3-1280x800-en-US.png)
- [funds-v3-390x844-en-US.png](funds-v3-390x844-en-US.png)
- [funds-v3-375x812-en-US.png](funds-v3-375x812-en-US.png)
- [funds-v3-2560x1440-en-US.png](funds-v3-2560x1440-en-US.png)
- [funds-v3-zero-income-375x812-en-US.png](funds-v3-zero-income-375x812-en-US.png)
- [funds-v3-no-received-advance-375x812-en-US.png](funds-v3-no-received-advance-375x812-en-US.png)
- [funds-v3-negative-and-deduction-375x812-en-US.png](funds-v3-negative-and-deduction-375x812-en-US.png)
- [funds-v3-long-amount-375x812-en-US.png](funds-v3-long-amount-375x812-en-US.png)
- [funds-v3-overview-error-375x812-en-US.png](funds-v3-overview-error-375x812-en-US.png)
- [funds-v3-empty-history-375x812-en-US.png](funds-v3-empty-history-375x812-en-US.png)
- [funds-v3-withdrawal-dialog-1440x900-en-US.png](funds-v3-withdrawal-dialog-1440x900-en-US.png)

- [funds-v3-bottom-visible-375x812-zh-CN.png](funds-v3-bottom-visible-375x812-zh-CN.png)
- [funds-v3-negative-visible-375x812-zh-CN.png](funds-v3-negative-visible-375x812-zh-CN.png)

手机组件裁剪截图会包含固定底部导航，其末行遮挡属于裁剪方式；补充viewport截图实际滚动后确认提现中和抵扣均可阅读，未隐藏导航、未改变产品样式。正常可提现顶栏/卡片计算颜色均为rgb(36, 87, 214)，负可提现顶栏/卡片及抵扣均为rgb(180, 35, 24)。
