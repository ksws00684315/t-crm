# 任务60浏览器验收，2026-10-26

## 结论

前端版本 `f5d400c`；完整浏览器矩阵所连后端为主线任务62、63、64、67（`0a472a1`）的clean运行包。正常数据矩阵46张，每张均先等待网络空闲、可见骨架与加载遮罩消失，再等待对应页面真实数据标记。`real-browser-evidence.json` 的46条readyStates均记录skeletons=0。

共21项交互/接口检查，21通过、0失败；页面JS异常0，正常矩阵页面横向溢出0。已查看375英文资金图，五个入口完整，My information为两行整词，手机资金流水为六字段卡片。共62张截图，清单见下。

## 环境和数据边界

- 唯一工作树：`.worktrees/60-kol-portal`；只使用负责人明确授权的独立临时`tcrm-t60-postgres`、`tcrm-t60-redis`及`tcrm-t60-network`，端口55460、46360；后端18960，达人端18961。未停止、重建或修改现有预览。
- t60_账号及数据均为本任务人工IT式夹具。佣金200、预付100、可提2000、冻结200只用于验证接口映射和交互；绝不代表微店外部结算字段已经核实。钱包2200信用、-200已付提现台账与2000余额一致。
- 核心正常流程连接真实后端；明确注入状态只有资金接口失败、冻结条件、长邮箱/名称，以及“后端真实接受后丢弃客户端响应”。后者二次POST实际返回同一申请ID、同一幂等键，实际占用仅250，随后真实撤回。
- 身份验证：无登录GET items返回401；当前达人GET/PUT另一测试达人的提现均返回404。不会把前端隐藏当作服务端权限证明。
- 之前正常backend package运行2003项时有两个主线OrderCommissionSyncTest/MyBatis缓存错误，未修改源码或声称后端全测通过。浏览器运行包通过clean -DskipTests package产出，跳测试仅用于运行包。

## 收尾主线任务68升级

已将运行后端升级为含任务68的当前main（4be1bcd）clean包，保留原测试库；真实接口及登录刷新/申请撤回冒烟通过。详见 real-backend-task68-smoke.json。

## 逐项结果

| 检查 | 结果 |
|---|---|
| real login and refresh session | PASS |
| API finance separation commissionPaid 200 prepayPaid 100 totalPaid 300 | PASS |
| real withdrawal conditions available 2000 frozen 200 | PASS |
| My items real search and copied tracking URL | PASS |
| My items empty search | PASS |
| Orders real order-number filter | PASS |
| Withdrawal popup real manual PayPal and invalid amount | PASS |
| Withdrawal real apply | PASS |
| Withdrawal real withdraw confirmation | PASS |
| Rejected resubmit sends previousId and can withdraw again | PASS |
| Profile real edit and read-only fields | PASS |
| Injected funds failure displays recoverable error | PASS |
| PayPal account real maintenance | PASS |
| USDT account and QR real upload/save | PASS |
| USDT withdrawal rejects invalid QR and submits real uploaded string file id | PASS |
| Mobile USDT popup no horizontal page overflow | PASS |
| Real API rejects unauthenticated and another creator withdrawal access | PASS |
| Real accepted withdrawal survives lost response and UI retry reuses idempotency key | PASS |
| Injected frozen condition visibly blocks the application | PASS |
| Real history size change requests once and phone history uses six-field cards | PASS |
| Injected long email and name remain within the phone layout | PASS |

## 截图与检查项

| 截图 | 对应检查项 |
|---|---|
| [real-en-US-1440x900-home.png](real-en-US-1440x900-home.png) | en-US，1440x900，工作台；真实数据就绪、骨架消失后的正常矩阵 |
| [real-en-US-1440x900-items.png](real-en-US-1440x900-items.png) | en-US，1440x900，我的商品；真实数据就绪、骨架消失后的正常矩阵 |
| [real-en-US-1440x900-orders.png](real-en-US-1440x900-orders.png) | en-US，1440x900，订单明细；真实数据就绪、骨架消失后的正常矩阵 |
| [real-en-US-1440x900-funds.png](real-en-US-1440x900-funds.png) | en-US，1440x900，资金明细；真实数据就绪、骨架消失后的正常矩阵 |
| [real-en-US-1440x900-account.png](real-en-US-1440x900-account.png) | en-US，1440x900，我的信息；真实数据就绪、骨架消失后的正常矩阵 |
| [real-en-US-1280x800-home.png](real-en-US-1280x800-home.png) | en-US，1280x800，工作台；真实数据就绪、骨架消失后的正常矩阵 |
| [real-en-US-1280x800-items.png](real-en-US-1280x800-items.png) | en-US，1280x800，我的商品；真实数据就绪、骨架消失后的正常矩阵 |
| [real-en-US-1280x800-orders.png](real-en-US-1280x800-orders.png) | en-US，1280x800，订单明细；真实数据就绪、骨架消失后的正常矩阵 |
| [real-en-US-1280x800-funds.png](real-en-US-1280x800-funds.png) | en-US，1280x800，资金明细；真实数据就绪、骨架消失后的正常矩阵 |
| [real-en-US-1280x800-account.png](real-en-US-1280x800-account.png) | en-US，1280x800，我的信息；真实数据就绪、骨架消失后的正常矩阵 |
| [real-en-US-390x844-home.png](real-en-US-390x844-home.png) | en-US，390x844，工作台；真实数据就绪、骨架消失后的正常矩阵 |
| [real-en-US-390x844-items.png](real-en-US-390x844-items.png) | en-US，390x844，我的商品；真实数据就绪、骨架消失后的正常矩阵 |
| [real-en-US-390x844-orders.png](real-en-US-390x844-orders.png) | en-US，390x844，订单明细；真实数据就绪、骨架消失后的正常矩阵 |
| [real-en-US-390x844-funds.png](real-en-US-390x844-funds.png) | en-US，390x844，资金明细；真实数据就绪、骨架消失后的正常矩阵 |
| [real-en-US-390x844-account.png](real-en-US-390x844-account.png) | en-US，390x844，我的信息；真实数据就绪、骨架消失后的正常矩阵 |
| [real-en-US-375x812-home.png](real-en-US-375x812-home.png) | en-US，375x812，工作台；真实数据就绪、骨架消失后的正常矩阵 |
| [real-en-US-375x812-items.png](real-en-US-375x812-items.png) | en-US，375x812，我的商品；真实数据就绪、骨架消失后的正常矩阵 |
| [real-en-US-375x812-orders.png](real-en-US-375x812-orders.png) | en-US，375x812，订单明细；真实数据就绪、骨架消失后的正常矩阵 |
| [real-en-US-375x812-funds.png](real-en-US-375x812-funds.png) | en-US，375x812，资金明细；真实数据就绪、骨架消失后的正常矩阵 |
| [real-en-US-375x812-account.png](real-en-US-375x812-account.png) | en-US，375x812，我的信息；真实数据就绪、骨架消失后的正常矩阵 |
| [real-en-US-2560x1440-items.png](real-en-US-2560x1440-items.png) | en-US，2560x1440，我的商品；真实数据就绪、骨架消失后的正常矩阵 |
| [real-en-US-2560x1440-orders.png](real-en-US-2560x1440-orders.png) | en-US，2560x1440，订单明细；真实数据就绪、骨架消失后的正常矩阵 |
| [real-en-US-2560x1440-funds.png](real-en-US-2560x1440-funds.png) | en-US，2560x1440，资金明细；真实数据就绪、骨架消失后的正常矩阵 |
| [real-zh-CN-1440x900-home.png](real-zh-CN-1440x900-home.png) | zh-CN，1440x900，工作台；真实数据就绪、骨架消失后的正常矩阵 |
| [real-zh-CN-1440x900-items.png](real-zh-CN-1440x900-items.png) | zh-CN，1440x900，我的商品；真实数据就绪、骨架消失后的正常矩阵 |
| [real-zh-CN-1440x900-orders.png](real-zh-CN-1440x900-orders.png) | zh-CN，1440x900，订单明细；真实数据就绪、骨架消失后的正常矩阵 |
| [real-zh-CN-1440x900-funds.png](real-zh-CN-1440x900-funds.png) | zh-CN，1440x900，资金明细；真实数据就绪、骨架消失后的正常矩阵 |
| [real-zh-CN-1440x900-account.png](real-zh-CN-1440x900-account.png) | zh-CN，1440x900，我的信息；真实数据就绪、骨架消失后的正常矩阵 |
| [real-zh-CN-1280x800-home.png](real-zh-CN-1280x800-home.png) | zh-CN，1280x800，工作台；真实数据就绪、骨架消失后的正常矩阵 |
| [real-zh-CN-1280x800-items.png](real-zh-CN-1280x800-items.png) | zh-CN，1280x800，我的商品；真实数据就绪、骨架消失后的正常矩阵 |
| [real-zh-CN-1280x800-orders.png](real-zh-CN-1280x800-orders.png) | zh-CN，1280x800，订单明细；真实数据就绪、骨架消失后的正常矩阵 |
| [real-zh-CN-1280x800-funds.png](real-zh-CN-1280x800-funds.png) | zh-CN，1280x800，资金明细；真实数据就绪、骨架消失后的正常矩阵 |
| [real-zh-CN-1280x800-account.png](real-zh-CN-1280x800-account.png) | zh-CN，1280x800，我的信息；真实数据就绪、骨架消失后的正常矩阵 |
| [real-zh-CN-390x844-home.png](real-zh-CN-390x844-home.png) | zh-CN，390x844，工作台；真实数据就绪、骨架消失后的正常矩阵 |
| [real-zh-CN-390x844-items.png](real-zh-CN-390x844-items.png) | zh-CN，390x844，我的商品；真实数据就绪、骨架消失后的正常矩阵 |
| [real-zh-CN-390x844-orders.png](real-zh-CN-390x844-orders.png) | zh-CN，390x844，订单明细；真实数据就绪、骨架消失后的正常矩阵 |
| [real-zh-CN-390x844-funds.png](real-zh-CN-390x844-funds.png) | zh-CN，390x844，资金明细；真实数据就绪、骨架消失后的正常矩阵 |
| [real-zh-CN-390x844-account.png](real-zh-CN-390x844-account.png) | zh-CN，390x844，我的信息；真实数据就绪、骨架消失后的正常矩阵 |
| [real-zh-CN-375x812-home.png](real-zh-CN-375x812-home.png) | zh-CN，375x812，工作台；真实数据就绪、骨架消失后的正常矩阵 |
| [real-zh-CN-375x812-items.png](real-zh-CN-375x812-items.png) | zh-CN，375x812，我的商品；真实数据就绪、骨架消失后的正常矩阵 |
| [real-zh-CN-375x812-orders.png](real-zh-CN-375x812-orders.png) | zh-CN，375x812，订单明细；真实数据就绪、骨架消失后的正常矩阵 |
| [real-zh-CN-375x812-funds.png](real-zh-CN-375x812-funds.png) | zh-CN，375x812，资金明细；真实数据就绪、骨架消失后的正常矩阵 |
| [real-zh-CN-375x812-account.png](real-zh-CN-375x812-account.png) | zh-CN，375x812，我的信息；真实数据就绪、骨架消失后的正常矩阵 |
| [real-zh-CN-2560x1440-items.png](real-zh-CN-2560x1440-items.png) | zh-CN，2560x1440，我的商品；真实数据就绪、骨架消失后的正常矩阵 |
| [real-zh-CN-2560x1440-orders.png](real-zh-CN-2560x1440-orders.png) | zh-CN，2560x1440，订单明细；真实数据就绪、骨架消失后的正常矩阵 |
| [real-zh-CN-2560x1440-funds.png](real-zh-CN-2560x1440-funds.png) | zh-CN，2560x1440，资金明细；真实数据就绪、骨架消失后的正常矩阵 |
| [real-items-empty.png](real-items-empty.png) | 商品不存在的关键词查询，真实空结果 |
| [real-withdrawal-invalid.png](real-withdrawal-invalid.png) | 金额校验失败，不提交申请 |
| [real-withdrawal-applied.png](real-withdrawal-applied.png) | 真实提现申请成功并显示最新历史 |
| [real-withdrawal-confirm.png](real-withdrawal-confirm.png) | 撤回提现的二次确认 |
| [real-account-profile-saved.png](real-account-profile-saved.png) | 资料修改通过真实PUT接口，邮箱与系统ID只读 |
| [real-funds-injected-error.png](real-funds-injected-error.png) | 明确注入的前端边界状态；未修改真实后端资金数据 |
| [real-account-paypal-saved.png](real-account-paypal-saved.png) | PayPal账户通过真实接口维护成功 |
| [real-account-usdt-saved.png](real-account-usdt-saved.png) | USDT账户及二维码通过真实接口维护成功 |
| [real-withdrawal-usdt-qr-invalid.png](real-withdrawal-usdt-qr-invalid.png) | 非法二维码文件被前端拦截，未发上传请求 |
| [real-withdrawal-usdt-ready.png](real-withdrawal-usdt-ready.png) | 真实上传后待提交的USDT快照，文件ID为字符串 |
| [real-375x812-withdrawal-usdt.png](real-375x812-withdrawal-usdt.png) | 375×812 USDT申请弹窗，网络与二维码字段完整，无页面横溢 |
| [real-withdrawal-real-lost-response.png](real-withdrawal-real-lost-response.png) | 真实后端接受申请后丢弃客户端响应；重试复用幂等键且只占用一次 |
| [real-withdrawal-injected-frozen.png](real-withdrawal-injected-frozen.png) | 明确注入的前端边界状态；未修改真实后端资金数据 |
| [real-375x812-history-real-pagination.png](real-375x812-history-real-pagination.png) | 真实资金记录六字段手机卡片与分页；桌面更改每页大小只请求一次 |
| [real-375x812-account-injected-long.png](real-375x812-account-injected-long.png) | 明确注入的前端边界状态；未修改真实后端资金数据 |
| [real-task68-smoke-funds-1440-en-US.png](real-task68-smoke-funds-1440-en-US.png) | 主线任务68升级运行包后的真实资金页冒烟，佣金200+预付100=总收入300 |

## 保留与清理

按负责人要求保留本任务临时预览及t60_账号，供本机验收。仅达人预览URL与测试账号密码保存在忽略目录`.task60-temp/preview-login-1026.txt`，不提交、不在截图或报告打印密码/token。关闭时只停止本任务Java/Vite进程，并删除`tcrm-t60-postgres`、`tcrm-t60-redis`和`tcrm-t60-network`；不执行全局prune或影响其他预览的清理。
